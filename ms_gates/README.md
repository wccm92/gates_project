# ms-gates

Java microservice that powers turnstile access control. Reads pipe-delimited scans from a physical USB reader over `stdin`, extracts the numeric credential (≥5 digits), validates it against a PostgreSQL database, and notifies an external system via HTTP when access is granted.

---

## Table of contents

- [Architecture](#architecture)
  - [Hexagonal overview](#hexagonal-overview)
  - [Dual-database strategy — Composite Adapter pattern](#dual-database-strategy--composite-adapter-pattern)
  - [Package layout](#package-layout)
  - [Runtime flow](#runtime-flow)
- [Prerequisites](#prerequisites)
- [Build](#build)
- [Configuration](#configuration)
- [Run](#run)
- [Testing guide](#testing-guide)
  - [Level 1 — Sanity check](#level-1--sanity-check-does-the-context-start)
  - [Level 2 — Scripted one-shot input](#level-2--scripted-one-shot-input)
  - [Level 3 — Fixture file](#level-3--fixture-file)
  - [Level 4 — Simulate a live reader with a FIFO](#level-4--simulate-a-live-reader-with-a-fifo)
  - [Level 5 — Error paths](#level-5--error-paths)
  - [Level 6 — Real USB reader](#level-6--real-usb-reader)
- [Log reference](#log-reference)
- [Input contract](#input-contract)
- [Database contract](#database-contract)
- [Production deployment notes](#production-deployment-notes)

---

## Architecture

### Hexagonal overview

Clean / hexagonal layering. The microservice does **not** know it is talking to a USB reader — its only contract is "newline-terminated, pipe-delimited text on `stdin`". Hardware bridging lives in an OS-level wrapper.

```
┌─────────────────────┐     ┌──────────────────────┐     ┌────────────────────┐
│ USB Reader hardware │ ──▶ │ OS wrapper / Python   │ ──▶ │ Java microservice  │
│ (HID or serial CDC) │     │ HID bridge            │     │ reads System.in    │
└─────────────────────┘     └──────────────────────┘     └────────────────────┘
         supervised by systemd (auto-start, auto-restart on crash)

                                         ┌──────────────────────────────────────┐
                           DB lookup ──▶ │  CompositeVisitorRepository          │
                           DB update     │  ├─ local  PostgreSQL  (critical)     │
                                         │  └─ remote PostgreSQL  (best-effort) │
                                         └──────────────────────────────────────┘

                                         ┌──────────────────┐
                       HTTP notify  ──▶  │  External system  │
                                         │  POST /api/access │
                                         └──────────────────┘
```

The domain use case (`CheckAccessUseCase`) is aware of **one** repository port. The fact that two physical databases exist is invisible to it — that is the job of the Composite Adapter described below.

---

### Dual-database strategy — Composite Adapter pattern

The service must keep two independent PostgreSQL databases in sync: a **local** one (on the same machine) and a **remote** one (on the same private network). Naively, you might add a second port to the domain or make the use case call each database explicitly. Both approaches leak infrastructure topology into business logic.

The chosen solution is the **Composite Adapter pattern**:

```
Domain use case
      │
      │  calls
      ▼
VisitorRepositoryPort          ← single output port, defined in the domain
      │
      │  implemented by
      ▼
CompositeVisitorRepository     ← adapter layer, invisible to the domain
      │
      ├── findByCredential ──────────▶ JdbcVisitorRepository       (local DB)
      │
      └── updateEstado ─────────────▶ JdbcVisitorRepository        (local DB)  ← critical
                        └────────────▶ RemoteJdbcVisitorRepository  (remote DB) ← best-effort
```

**How it works:**

- `CheckAccessUseCase` calls `visitorRepositoryPort.findByCredential()` and `visitorRepositoryPort.updateEstado()` — it is completely unaware of how many databases are behind the port.
- `CompositeVisitorRepository` implements that single port and internally delegates to both concrete JDBC adapters.
- **Reads** (`findByCredential`) go to the **local DB only** — the local database is the authoritative source for access decisions.
- **Writes** (`updateEstado`) go to **both databases**, with different failure semantics:
  - **Local DB** — treated as **critical**. If the update fails, the exception propagates to the use case, which logs `[E005]` and `[S002]` is not emitted.
  - **Remote DB** — treated as **best-effort**. If the update fails, `CompositeVisitorRepository` catches the exception, logs `[E006]`, and does **not** re-throw. The use case proceeds normally and logs `[S002]`.

**Why this design?**

| Concern | Decision |
| --- | --- |
| Domain isolation | Use case never imports JDBC, knows nothing about local vs. remote |
| Failure tolerance | A remote DB outage does not block admissions |
| Extensibility | Adding a third database requires only a new adapter + one line in the composite; no domain change |
| Testability | Each adapter can be unit-tested independently; the composite can be tested with mocks |

**Key classes:**

| Class | Package | Role |
| --- | --- | --- |
| `VisitorRepositoryPort` | `domain.usecase.port` | Output port — the only DB abstraction the domain sees |
| `JdbcVisitorRepository` | `adapters` | Local PostgreSQL — `find` + `update` |
| `RemoteJdbcVisitorRepository` | `adapters` | Remote PostgreSQL — `update` only |
| `CompositeVisitorRepository` | `adapters` | Implements the port; routes calls to both adapters |

The composition is wired entirely in `BeanConfiguration` — no Spring annotations in domain or adapter classes.

---

### Package layout

```
src/main/java/com/gates/msgates/
├── MsGatesApplication.java                       Spring Boot entry point
├── config/
│   ├── BeanConfiguration.java                    wires domain beans and infrastructure
│   ├── AccessHttpProperties.java                 typed config for HTTP notifier
│   ├── LocalDbProperties.java                    typed config for local PostgreSQL
│   └── RemoteDbProperties.java                   typed config for remote PostgreSQL
├── domain/                                       framework-free
│   ├── model/
│   │   ├── RawReading.java                       record: payload + receivedAt
│   │   ├── Credential.java                       record: validated numeric (≥5 digits)
│   │   └── Visitante.java                        record: maps invitados table row
│   ├── exception/
│   │   └── BusinessException.java                domain business error with code
│   └── usecase/
│       ├── ReadingParser.java                    pure parser
│       ├── ProcessReadingUseCase.java            extracts credential, publishes to stdout
│       ├── CheckAccessUseCase.java               validates credential and notifies
│       └── port/
│           ├── CredentialPublisherPort.java      output port: stdout
│           ├── VisitorRepositoryPort.java        output port: DB lookup + update
│           └── AccessNotifierPort.java           output port: HTTP notification
├── adapters/
│   ├── ConsoleCredentialPublisher.java           prints credential to stdout
│   ├── JdbcVisitorRepository.java               local PostgreSQL adapter (find + update)
│   ├── RemoteJdbcVisitorRepository.java         remote PostgreSQL adapter (update only)
│   ├── CompositeVisitorRepository.java          routes reads to local, writes to both
│   └── HttpAccessNotifier.java                  HTTP POST adapter
└── entrypoints/
    └── StdinReaderRunner.java                    daemon reader thread
```

`domain/*` has zero Spring annotations. Only `adapters/` and `entrypoints/` touch the framework. Beans are wired in `config/BeanConfiguration.java`.

---

### Runtime flow

1. Spring boots → `StdinReaderRunner.run()` launches a daemon thread named `stdin-reader`.
2. The thread loops `BufferedReader.readLine()` on UTF-8 `System.in`.
3. Each non-blank line ≤1 KiB becomes a `RawReading(payload, Instant.now())`.
4. `ProcessReadingUseCase` calls `ReadingParser`, which splits on `|` and finds the first token containing `\d{5,}`.
5. On hit → `ConsoleCredentialPublisher.publish()` logs `INFO` and prints the numeric to stdout. Returns `Optional<Credential>`.
6. On miss → `WARN` log, reading dropped, loop continues.
7. If a credential was extracted, `CheckAccessUseCase.handle()` runs:
   - Queries `invitados` by `id_visitante` (via composite → local DB).
   - Not found → logs `[E001]`, throws `BusinessException("documento no presente")`.
   - Found, `estado` non-blank → logs `[E002]`, throws `BusinessException("documento ya ingresó")`.
   - Found, `estado` null/blank → logs `[S000]`, calls `HttpAccessNotifier` with `{"doc":"<value>"}`.
     - HTTP non-200 → logs `[E003]`. No DB update.
     - HTTP 200 → logs `[S001]`, then calls `updateEstado("1")` via composite:
       - Local update fails → logs `[E005]`, `[S002]` is not emitted.
       - Local update succeeds, remote fails → composite logs `[E006]`, use case logs `[S002]`.
       - Both succeed → use case logs `[S002]`.
8. `BusinessException` is caught at the entrypoint — the reader loop is never interrupted.
9. Any unexpected `RuntimeException` inside the use case is caught and logged as `[E004]`.
10. On EOF or `IOException` → `SpringApplication.exit()` so the process supervisor can restart it.

---

## Prerequisites

- **JDK 20 or later** (Corretto 21 verified).
- **Maven 3.9+** (or use IntelliJ's bundled Maven).
- **PostgreSQL** — both local and remote instances accessible from the machine running the service.

Install Maven on macOS:

```bash
brew install maven
mvn -v
```

---

## Build

From the project root:

```bash
mvn clean package -DskipTests
```

Produces `target/ms-gates-0.0.1-SNAPSHOT.jar`.

---

## Configuration

All values are read from environment variables. Defaults are shown and apply when the variable is absent (useful for local development).

### Environment variables

**Local DB** (same machine as the service):

| Variable | Default | Description |
| --- | --- | --- |
| `LOCAL_DB_HOST` | `localhost` | Host |
| `LOCAL_DB_PORT` | `5432` | Port |
| `LOCAL_DB_NAME` | `edc` | Database name |
| `LOCAL_DB_USERNAME` | `postgres` | User |
| `LOCAL_DB_PASSWORD` | `postgres` | Password |
| `LOCAL_DB_TABLE` | `invitados` | Table name |

**Remote DB** (external, same private network):

| Variable | Default | Description |
| --- | --- | --- |
| `REMOTE_DB_HOST` | `localhost` | Host |
| `REMOTE_DB_PORT` | `5432` | Port |
| `REMOTE_DB_NAME` | `edc` | Database name |
| `REMOTE_DB_USERNAME` | `postgres` | User |
| `REMOTE_DB_PASSWORD` | `postgres` | Password |
| `REMOTE_DB_TABLE` | `invitados` | Table name |

**HTTP notifier:**

| Variable | Default | Description |
| --- | --- | --- |
| `ACCESS_BASE_URL` | `http://localhost:8080` | Base URL of the HTTP access notification endpoint |
| `ACCESS_PATH` | `/api/access` | Path for the HTTP POST request |
| `ACCESS_TIMEOUT_SECONDS` | `5` | HTTP client timeout in seconds |

### Systemd deployment (Linux)

The service is supervised by systemd and configured via two `EnvironmentFile` entries — one for the Python USB bridge, one for the Java service:

```ini
EnvironmentFile=/etc/gates/bridge.env
EnvironmentFile=/etc/gates/ms-gates.env
```

Create `/etc/gates/ms-gates.env`:

```ini
# Local PostgreSQL (same machine)
LOCAL_DB_HOST=localhost
LOCAL_DB_PORT=5432
LOCAL_DB_NAME=edc
LOCAL_DB_USERNAME=myuser
LOCAL_DB_PASSWORD=secret
LOCAL_DB_TABLE=invitados

# Remote PostgreSQL (same private network)
REMOTE_DB_HOST=192.168.x.x
REMOTE_DB_PORT=5432
REMOTE_DB_NAME=edc
REMOTE_DB_USERNAME=myuser
REMOTE_DB_PASSWORD=secret
REMOTE_DB_TABLE=invitados

# HTTP notifier
ACCESS_BASE_URL=http://192.168.x.x:8080
ACCESS_PATH=/api/access
ACCESS_TIMEOUT_SECONDS=5
```

Lock down the file (contains credentials):

```bash
sudo chown root:gates /etc/gates/ms-gates.env
sudo chmod 640 /etc/gates/ms-gates.env
```

### Overriding at runtime (dev/testing)

```bash
LOCAL_DB_PASSWORD=secret REMOTE_DB_HOST=192.168.x.x \
  java -jar target/ms-gates-0.0.1-SNAPSHOT.jar
```

---

## Run

Three equivalent ways:

```bash
# Dev mode (auto-rebuild not included — restart on code change)
mvn spring-boot:run

# From the built jar
java -jar target/ms-gates-0.0.1-SNAPSHOT.jar

# With stdin fed from a file
java -jar target/ms-gates-0.0.1-SNAPSHOT.jar < fixtures/readings.txt
```

The process keeps running until `stdin` closes (`Ctrl+D`, pipe closed) or a `SIGTERM` arrives (`Ctrl+C`).

---

## Testing guide

Progressive levels — start at Level 1 and stop once you've reached the confidence you need.

### Level 1 — Sanity check: does the context start?

```bash
mvn spring-boot:run
```

Expected log lines:

```
Started MsGatesApplication in X.X seconds
Starting stdin reader thread
```

Type a line and press Enter:

```
EVENT|CARD|12345678|DOOR-01
```

Expected:

- `INFO  ...ConsoleCredentialPublisher : Credential extracted: 12345678`
- `12345678` printed on its own line (the `System.out.println` from the publisher).
- `INFO  ...CheckAccessUseCase : [S000] documento apto para ingresar` (if credential exists in DB and estado is blank).

`Ctrl+D` → clean shutdown via EOF. `Ctrl+C` → clean shutdown via SIGTERM.

---

### Level 2 — Scripted one-shot input

Fastest feedback loop.

**Heredoc:**

```bash
java -jar target/ms-gates-0.0.1-SNAPSHOT.jar <<'EOF'
EVENT|CARD|12345678|DOOR-01
EVENT|CARD|999|DOOR-02
|||
BADGE-SCAN|USER|ABC123456|TURNSTILE-A
EOF
```

| Input | Result |
| --- | --- |
| `EVENT\|CARD\|12345678\|DOOR-01` | prints `12345678`, then access check runs |
| `EVENT\|CARD\|999\|DOOR-02` | no print, WARN "no numeric attribute of 5+ digits found" |
| `\|\|\|` | no print, WARN |
| `BADGE-SCAN\|USER\|ABC123456\|TURNSTILE-A` | prints `123456`, then access check runs |

After the last line, EOF closes stdin, runner logs `stdin closed (EOF)` and Spring exits with code 0.

**One-liner:**

```bash
echo 'HDR|CARD-ID:00012345|DOOR-5' | java -jar target/ms-gates-0.0.1-SNAPSHOT.jar
```

Expected stdout: `00012345`.

---

### Level 3 — Fixture file

Representative test set covering happy and malformed cases.

```bash
mkdir -p fixtures
cat > fixtures/readings.txt <<'EOF'
EVENT|CARD|12345678|DOOR-01
EVENT|CARD|87654321|DOOR-02
GARBAGE_NO_PIPES
EVENT|CARD||DOOR-03
EVENT|CARD|12|DOOR-04
EVENT|CARD|CARD-ID:55554444|DOOR-05

EVENT|CARD|99999999|DOOR-06
EOF

java -jar target/ms-gates-0.0.1-SNAPSHOT.jar < fixtures/readings.txt
```

Expected: four numeric lines printed (`12345678`, `87654321`, `55554444`, `99999999`), blank line silently skipped, the others produce WARN logs and are dropped. Each extracted credential then goes through the access check. Process exits 0 at EOF.

---

### Level 4 — Simulate a live reader with a FIFO

Closest simulation of the real USB flow without hardware. A FIFO behaves exactly like the pipe that the Python HID bridge creates.

```bash
mkfifo /tmp/reader.fifo
```

**Terminal A** — start the service reading from the FIFO:

```bash
java -jar target/ms-gates-0.0.1-SNAPSHOT.jar < /tmp/reader.fifo
```

**Terminal B** — push scans as if the reader is emitting them:

```bash
echo 'EVENT|CARD|12345678|DOOR-01' > /tmp/reader.fifo
sleep 1
echo 'EVENT|CARD|87654321|DOOR-02' > /tmp/reader.fifo
```

Terminal A prints each numeric as it arrives, runs the access check, and keeps waiting — exactly the production behavior.

**Test stream interruption** — close the writer side so Java sees EOF:

```bash
# Terminal B
exec 3>/tmp/reader.fifo
echo 'EVENT|CARD|55554444|DOOR-03' >&3
exec 3>&-   # close FD 3 → Java sees EOF
```

Terminal A logs `stdin closed (EOF), reader thread exiting` and the JVM exits 0. systemd restarts it immediately.

**Cleanup:**

```bash
rm /tmp/reader.fifo
```

---

### Level 5 — Error paths

**Oversized line (>1 KiB):**

```bash
python3 -c "print('X' * 2000 + '|12345678')" | java -jar target/ms-gates-0.0.1-SNAPSHOT.jar
```

Expected: WARN `Dropping oversized line`, no stdout, process exits at EOF.

**Binary garbage** (verifies the "never crash on bad input" guarantee):

```bash
head -c 200 /dev/urandom | java -jar target/ms-gates-0.0.1-SNAPSHOT.jar
```

Should log WARNs (or occasional INFO if random bytes happen to contain 5+ digits) and exit without a stack trace.

**Killing mid-stream:**

```bash
java -jar target/ms-gates-0.0.1-SNAPSHOT.jar < fixtures/readings.txt &
kill -TERM %1
```

`@PreDestroy` flips `running = false`, the reader loop exits, Spring shuts down cleanly. Exit code 143 (128 + SIGTERM) is expected.

---

### Level 6 — Real USB reader

#### Step 1 — Identify the device type

```bash
ls /dev/tty.* > /tmp/before.txt
# Plug the reader in
ls /dev/tty.* > /tmp/after.txt
diff /tmp/before.txt /tmp/after.txt
```

- **Diff shows `/dev/tty.usbserial-*` or `/dev/tty.usbmodem*`** → serial CDC (easy, Step 2a).
- **No diff**, but scanning into a text editor "types" characters → HID keyboard mode (needs a bridge, Step 2b).

#### Step 2a — Serial-CDC reader

```bash
stdbuf -oL cat /dev/tty.usbserial-1410 | java -jar target/ms-gates-0.0.1-SNAPSHOT.jar
```

`stdbuf -oL` forces line-buffered output so readings arrive immediately.

If nothing arrives when scanning, the reader is likely on a non-default baud. Consult its manual, then:

```bash
stty -f /dev/tty.usbserial-1410 9600 raw -echo
stdbuf -oL cat /dev/tty.usbserial-1410 | java -jar target/ms-gates-0.0.1-SNAPSHOT.jar
```

Common rates: 9600, 19200, 38400, 115200.

#### Step 2b — HID keyboard-emulating reader

`stdin` cannot see HID keyboard events directly (they go to the focused window). A Python HID bridge process reads the USB HID interface and writes one scan per line to stdout. Pipe that bridge into the Java process the same way as Step 2a.

---

## Log reference

Enable DEBUG temporarily via:

```bash
java -Dlogging.level.com.gates.msgates=DEBUG -jar target/ms-gates-0.0.1-SNAPSHOT.jar < fixtures/readings.txt
```

Or add to `application.yml`:

```yaml
logging:
  level:
    com.gates.msgates: DEBUG
```

### Stdin reader & parser

| Level | Message | Meaning |
| --- | --- | --- |
| `INFO` | `Starting stdin reader thread` | runner wired correctly |
| `DEBUG` | `Received line [length=…]` | reader is flowing |
| `INFO` | `Credential extracted: …` | credential parsed and printed to stdout |
| `WARN` | `Discarded reading: no numeric attribute…` | parser couldn't find `\d{5,}` |
| `WARN` | `Dropping oversized line` | defensive 1 KiB cap hit |
| `ERROR` | `Unexpected failure while processing reading` | unexpected bug in parse/publish path |
| `ERROR` | `stdin read failed…` | IO death — supervisor will restart |
| `INFO` | `stdin closed (EOF)…` | clean end — bridge closed or `Ctrl+D` |

### Access check

| Code | Level | Message | Meaning |
| --- | --- | --- | --- |
| `S000` | `INFO` | `documento apto para ingresar` | credential found in DB, estado blank — HTTP notification about to be sent |
| `S001` | `INFO` | `notificación HTTP exitosa` | external system responded 200 |
| `S002` | `INFO` | `estado actualizado a '1' en DB` | local DB updated; remote DB also updated unless `[E006]` appears |
| `E001` | `WARN` | `documento no presente` | credential not found in `invitados` table |
| `E002` | `WARN` | `documento ya ingresó` | credential found but `estado` is non-blank (already admitted) |
| `E003` | `ERROR` | `notificación HTTP fallida` | external system responded non-200; no DB update performed |
| `E004` | `ERROR` | `error inesperado en verificación de acceso` | unexpected runtime exception in access check |
| `E005` | `ERROR` | `error al actualizar estado en DB` | HTTP was 200 but the local DB update failed; `[S002]` will not appear |
| `E006` | `ERROR` | `error al actualizar estado en DB remota` | local DB updated but remote DB sync failed (best-effort — does not block admission) |

---

## Input contract

| Concern | Value |
| --- | --- |
| Encoding | UTF-8 |
| Record separator | `\n` (or `\r\n`, handled by `readLine()`) |
| Field separator | `\|` |
| One reading per | line |
| Trailing fields | allowed |
| Max line length | 1 KiB (oversize dropped with WARN) |
| Malformed lines | tolerated, logged, dropped |

Extraction rule: the parser returns the first `\d{5,}` **subsequence** found inside any pipe-delimited token (via `Matcher.find()`). So `CARD-ID:12345678` yields `12345678`.

---

## Database contract

Both the local and remote databases share the same schema. The table name is independently configurable for each via `LOCAL_DB_TABLE` and `REMOTE_DB_TABLE`.

```sql
CREATE TABLE IF NOT EXISTS invitados (
    id_visitante  TEXT         NOT NULL,
    id_evento     INTEGER      NOT NULL,
    id_suite      TEXT         NOT NULL,
    estado        CHARACTER(1),
    obsingreso    CHARACTER(50),
    CONSTRAINT visitantexevento_pkey PRIMARY KEY (id_visitante, id_evento)
);
```

**Query strategy:** the service queries by `id_visitante`, ordering by `id_evento DESC` and taking the most recent record (`LIMIT 1`). Updates target the exact `(id_visitante, id_evento)` pair returned by that query, so only the relevant event row is modified.

**`estado` semantics:**

| Value | Meaning |
| --- | --- |
| `NULL` or blank (space-padded) | Visitor not yet admitted — access allowed |
| Any non-blank character | Visitor already admitted — access denied (`[E002]`) |

On successful admission the service writes `'1'` to `estado` in both databases via the Composite Adapter (see [Dual-database strategy](#dual-database-strategy--composite-adapter-pattern)).

---

## Production deployment notes

The process is intentionally short-lived on error — EOF or `IOException` triggers `SpringApplication.exit()`. The systemd unit supervises the full pipeline (Python HID bridge → Java service) with `Restart=always`.

### systemd unit example

```ini
[Unit]
Description=Gates Access Control Pipeline (USB bridge + microservice)
After=network.target

[Service]
Type=simple
User=gates
WorkingDirectory=/opt/usb_reader_bridge
ExecStart=/bin/sh -c '.venv/bin/python3 -u src/main.py | java -jar /opt/ms-gates/ms-gates.jar'
Restart=always
RestartSec=5
EnvironmentFile=/etc/gates/bridge.env
EnvironmentFile=/etc/gates/ms-gates.env
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

View live logs:

```bash
sudo journalctl -u gates-pipeline -f
```

### Environment files

- `/etc/gates/bridge.env` — Python HID bridge variables (USB VID/PID, log level, reader mode).
- `/etc/gates/ms-gates.env` — Java service variables (DB credentials, HTTP endpoint). See [Configuration](#configuration).

Permissions for the credentials file:

```bash
sudo chown root:gates /etc/gates/ms-gates.env
sudo chmod 640 /etc/gates/ms-gates.env
```
