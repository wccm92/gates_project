# ms-gates

Java microservice that powers turnstile access control. Reads pipe/backtick-delimited scans from a physical USB reader over `stdin`, extracts the **reader id (`id_lector`)** and the **numeric credential (≥5 digits)**, validates the credential against a PostgreSQL database, resolves the physical **door id (`id_puerto`)** from an in-memory reader cache, notifies an external system via HTTP when access is granted, and updates the visitor state across two databases.

---

## Table of contents

- [Architecture](#architecture)
  - [Hexagonal overview](#hexagonal-overview)
  - [Dual-database strategy — Composite Adapter pattern](#dual-database-strategy--composite-adapter-pattern)
  - [Reader cache (catalog DB)](#reader-cache-catalog-db)
  - [Alias admission (prefix `0`) — async event](#alias-admission-prefix-0--async-event)
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
  - [Persisting & exporting logs (log collector)](#persisting--exporting-logs-log-collector)

---

## Architecture

### Hexagonal overview

Clean / hexagonal layering. The microservice does **not** know it is talking to a USB reader — its only contract is "newline-terminated, pipe/backtick-delimited text on `stdin`". Hardware bridging lives in an OS-level wrapper.

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

                                         ┌──────────────────────────────────────┐
              reader → door mapping ──▶  │  LectorxTribunaCache  (catalog DB)    │
                     (loaded at startup) │  id_lector ──▶ id_puerto              │
                                         └──────────────────────────────────────┘

                                         ┌──────────────────┐
                       HTTP notify  ──▶  │  External system  │
                                         │  POST /api/access │
                                         │  {"id_port":"…"}  │
                                         └──────────────────┘
```

The domain use case (`CheckAccessUseCase`) is aware of three output ports: `VisitorRepositoryPort` (DB), `ReaderCachePort` (reader→door mapping) and `AccessNotifierPort` (HTTP). It never learns that two visitor databases exist or that the reader cache is backed by a third database — that is the job of the adapters described below.

---

### Dual-database strategy — Composite Adapter pattern

The service must keep two independent PostgreSQL databases in sync for the visitor state: a **local** one (on the same machine) and a **remote** one (on the same private network). Naively, you might add a second port to the domain or make the use case call each database explicitly. Both approaches leak infrastructure topology into business logic.

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

> The `[S002]` success line is emitted **once**, by the use case (`CheckAccessUseCase`) after `updateEstado` returns — the business layer owns business status codes. The composite only logs the remote failure `[E006]`.

**Why this design?**

| Concern | Decision |
| --- | --- |
| Domain isolation | Use case never imports JDBC, knows nothing about local vs. remote |
| Failure tolerance | A remote DB outage does not block admissions |
| Extensibility | Adding a third visitor database requires only a new adapter + one line in the composite; no domain change |
| Testability | Each adapter can be unit-tested independently; the composite can be tested with mocks |

**Key classes:**

| Class | Package | Role |
| --- | --- | --- |
| `VisitorRepositoryPort` | `domain.usecase.port` | Output port — the only visitor-DB abstraction the domain sees |
| `JdbcVisitorRepository` | `adapters` | Local PostgreSQL — `find` + `update` (implements the port) |
| `RemoteJdbcVisitorRepository` | `adapters` | Remote PostgreSQL — `update` only (plain class, not the port) |
| `CompositeVisitorRepository` | `adapters` | Implements the port; routes reads to local, writes to both |

The composition is wired entirely in `BeanConfiguration` — no Spring annotations in domain or adapter classes.

---

### Reader cache (catalog DB)

Each physical reader (`id_lector`) maps to a physical door/port (`id_puerto`). That mapping lives in a **third** database — the **catalog** DB — in the `lectorxtribuna` table, scoped by a tribune id (`id_tribuna`).

- `LectorxTribunaCache` (implements `ReaderCachePort`) loads the full `id_lector → id_puerto` map **once, at startup**, for the configured `READER_ID_TRIBUNA`, and keeps it in an immutable in-memory `Map`. There is no per-scan query to the catalog DB.
- **Fail-fast:** if the cache cannot be loaded at startup (catalog DB unreachable, bad query, etc.) it logs `[E000]` and throws — **the service does not start**. This is deliberate: a gate that cannot resolve its doors must not run.
- At access time, `CheckAccessUseCase` calls `readerCache.findPortId(idLector)`. A miss logs `[E008]` and throws a `BusinessException` — no HTTP notification is sent.

```
Domain use case
      │  findPortId(idLector)
      ▼
ReaderCachePort  ──implemented by──▶  LectorxTribunaCache   (catalog DB, loaded at startup)
```

---

### Alias admission (prefix `0`) — async event

Some visitors exist in the database under a second "alias" credential formed by prefixing the scanned credential with `"0"` (e.g. scan `12345678` → alias `012345678`). When a visitor is admitted, that alias — if present and not yet admitted — must also be marked as admitted.

This is handled **asynchronously and out of the critical path**:

1. On a successful admission, `CheckAccessUseCase` publishes a Spring `VisitorAdmittedEvent`.
2. `VisitorAliasUpdaterListener` (`@Async @EventListener`) reacts on a separate thread:
   - Looks up `"0" + credential`.
   - Not found → no-op (silent).
   - Found but already admitted → `[S003]`.
   - Found and pending → `updateEstado` and log `[S004]`; on failure log `[E007]`.

Because it is `@Async`, alias updating never delays the reader loop or the primary admission response. `@EnableAsync` is on `BeanConfiguration`.

---

### Package layout

```
src/main/java/com/gates/msgates/
├── MsGatesApplication.java                       Spring Boot entry point
├── config/
│   ├── BeanConfiguration.java                    wires domain beans + infrastructure (@EnableAsync)
│   ├── AccessHttpProperties.java                 typed config for HTTP notifier
│   ├── LocalDbProperties.java                    typed config for local PostgreSQL
│   ├── RemoteDbProperties.java                   typed config for remote PostgreSQL
│   ├── CatalogDbProperties.java                  typed config for catalog PostgreSQL (reader cache)
│   └── ReaderProperties.java                     typed config for id_tribuna
├── domain/                                       framework-free
│   ├── model/
│   │   ├── RawReading.java                       record: payload + receivedAt
│   │   ├── ScanReading.java                      record: credential + idLector
│   │   ├── Credential.java                       record: validated numeric (≥5 digits)
│   │   ├── Visitante.java                        record: maps invitados table row
│   │   └── VisitorAdmittedEvent.java             record: published on successful admission
│   ├── exception/
│   │   └── BusinessException.java                domain business error with code
│   └── usecase/
│       ├── ReadingParser.java                    pure parser (id_lector + credential)
│       ├── ProcessReadingUseCase.java            parses reading, publishes credential to stdout
│       ├── CheckAccessUseCase.java               validates, resolves door, notifies, updates
│       └── port/
│           ├── CredentialPublisherPort.java      output port: stdout
│           ├── VisitorRepositoryPort.java        output port: visitor DB lookup + update
│           ├── ReaderCachePort.java              output port: id_lector → id_puerto
│           └── AccessNotifierPort.java           output port: HTTP notification
├── adapters/
│   ├── ConsoleCredentialPublisher.java           prints credential to stdout
│   ├── JdbcVisitorRepository.java               local PostgreSQL adapter (find + update)
│   ├── RemoteJdbcVisitorRepository.java         remote PostgreSQL adapter (update only)
│   ├── CompositeVisitorRepository.java          routes reads to local, writes to both
│   ├── LectorxTribunaCache.java                 reader→door cache from catalog DB (startup load)
│   ├── VisitorAliasUpdaterListener.java         @Async listener: admits the "0"-prefixed alias
│   └── HttpAccessNotifier.java                  HTTP POST adapter
└── entrypoints/
    └── StdinReaderRunner.java                    daemon reader thread
```

`domain/*` has zero Spring annotations. Only `adapters/`, `entrypoints/` and `config/` touch the framework. Domain beans are wired in `config/BeanConfiguration.java`; `@Component` is used only on framework-facing adapters/entrypoints.

---

### Runtime flow

1. Spring boots → `LectorxTribunaCache` loads the reader→door map from the catalog DB (`[E000]` + hard fail if it can't).
2. `StdinReaderRunner.run()` launches a daemon thread named `stdin-reader`.
3. The thread loops `BufferedReader.readLine()` on UTF-8 `System.in`.
4. Each non-blank line ≤1 KiB becomes a `RawReading(payload, Instant.now())`.
5. `ProcessReadingUseCase` calls `ReadingParser`, which splits on `|` or backtick and expects:
   - **token[0]** = `id_lector` (must parse as an integer),
   - the first remaining token containing `\d{5,}` = the credential.
   Result: `ScanReading(credential, idLector)`.
6. On hit → `ConsoleCredentialPublisher.publish()` logs `INFO` and prints the numeric to stdout. Returns `Optional<ScanReading>`.
7. On miss (blank, `<2` tokens, non-numeric token[0], or no credential) → `WARN` log, reading dropped, loop continues.
8. If a `ScanReading` was produced, `CheckAccessUseCase.handle()` runs:
   - Queries `invitados` by `id_visitante` (via composite → local DB).
   - Not found → logs `[E001]`, throws `BusinessException("E001", …)`.
   - Found, `estado` non-blank → logs `[E002]`, throws `BusinessException("E002", …)`.
   - Found, `estado` null/blank → logs `[S000]`, then resolves the door:
     - `readerCache.findPortId(idLector)` empty → logs `[E008]`, throws `BusinessException("E008", …)`. No HTTP call.
     - Found → calls `HttpAccessNotifier.notify(idPuerto)` with body `{"id_port":"<idPuerto>"}`.
       - HTTP non-200 → logs `[E003]`. No DB update.
       - HTTP 200 → logs `[S001]`, then `updateEstado("1")` via composite:
         - Local update fails → logs `[E005]`, `[S002]` is not emitted.
         - Local update succeeds → logs `[S002]` and publishes `VisitorAdmittedEvent` (remote sync happens inside the composite; failure there is `[E006]`, non-blocking).
9. `VisitorAdmittedEvent` triggers the async alias updater (`[S003]`/`[S004]`/`[E007]`), off the reader thread.
10. `BusinessException` is caught at the entrypoint — the reader loop is never interrupted.
11. Any unexpected `RuntimeException` inside the use case is caught and logged as `[E004]`.
12. On EOF or `IOException` → `SpringApplication.exit()` so the process supervisor can restart it.

---

## Prerequisites

- **JDK 20 or later** (Corretto 21 verified).
- **Maven 3.9+** (or use IntelliJ's bundled Maven).
- **PostgreSQL** — local, remote and catalog instances accessible from the machine running the service. (In simple deployments the catalog can be the same instance/DB as local.)

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

**Local DB** (same machine as the service — authoritative reads + critical writes):

| Variable | Default | Description |
| --- | --- | --- |
| `LOCAL_DB_HOST` | `localhost` | Host |
| `LOCAL_DB_PORT` | `5432` | Port |
| `LOCAL_DB_NAME` | `edc` | Database name |
| `LOCAL_DB_USERNAME` | `postgres` | User |
| `LOCAL_DB_PASSWORD` | `postgres` | Password |
| `LOCAL_DB_TABLE` | `invitados` | Visitor table name |

**Remote DB** (external, same private network — best-effort writes):

| Variable | Default | Description |
| --- | --- | --- |
| `REMOTE_DB_HOST` | `localhost` | Host |
| `REMOTE_DB_PORT` | `5432` | Port |
| `REMOTE_DB_NAME` | `edc` | Database name |
| `REMOTE_DB_USERNAME` | `postgres` | User |
| `REMOTE_DB_PASSWORD` | `postgres` | Password |
| `REMOTE_DB_TABLE` | `invitados` | Visitor table name |

**Catalog DB** (reader → door mapping, loaded once at startup):

| Variable | Default | Description |
| --- | --- | --- |
| `CATALOG_DB_HOST` | `localhost` | Host |
| `CATALOG_DB_PORT` | `5432` | Port |
| `CATALOG_DB_NAME` | `edc` | Database name |
| `CATALOG_DB_USERNAME` | `postgres` | User |
| `CATALOG_DB_PASSWORD` | `postgres` | Password |

**Reader:**

| Variable | Default | Description |
| --- | --- | --- |
| `READER_ID_TRIBUNA` | `tribune` | Tribune id used to filter `lectorxtribuna` at startup. **Must be an integer at runtime** (`app.reader.id-tribuna` binds to `int`) |

> ⚠️ The default `tribune` is a placeholder for local wiring; a real deployment must set `READER_ID_TRIBUNA` to the numeric tribune id, otherwise startup fails binding the property.

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

# Catalog PostgreSQL (reader → door mapping)
CATALOG_DB_HOST=localhost
CATALOG_DB_PORT=5432
CATALOG_DB_NAME=edc
CATALOG_DB_USERNAME=myuser
CATALOG_DB_PASSWORD=secret

# Reader
READER_ID_TRIBUNA=1

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
LOCAL_DB_PASSWORD=secret REMOTE_DB_HOST=192.168.x.x READER_ID_TRIBUNA=1 \
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

The process keeps running until `stdin` closes (`Ctrl+D`, pipe closed) or a `SIGTERM` arrives (`Ctrl+C`). It will **refuse to start** if the catalog reader cache can't be loaded (`[E000]`).

---

## Testing guide

Progressive levels — start at Level 1 and stop once you've reached the confidence you need.

> Every reading now needs a leading `id_lector` token. `EVENT|CARD|12345678|DOOR-01` will **not** parse (token[0] `EVENT` is not an integer). Use e.g. `7|CARD|12345678|DOOR-01`, where `7` is a reader id present in the catalog cache.

### Level 1 — Sanity check: does the context start?

```bash
mvn spring-boot:run
```

Expected log lines:

```
[S_INIT] caché de lectores cargada — id_tribuna=…, entradas=…
Started MsGatesApplication in X.X seconds
Starting stdin reader thread
```

Type a line and press Enter (`7` = a reader id in the cache):

```
7|CARD|12345678|DOOR-01
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
7|CARD|12345678|DOOR-01
7|CARD|999|DOOR-02
|||
7|USER|ABC123456|TURNSTILE-A
EVENT|CARD|12345678|DOOR-01
EOF
```

| Input | Result |
| --- | --- |
| `7\|CARD\|12345678\|DOOR-01` | prints `12345678`, then access check runs for reader 7 |
| `7\|CARD\|999\|DOOR-02` | no print, WARN "could not parse id_lector or credential" |
| `\|\|\|` | no print, WARN |
| `7\|USER\|ABC123456\|TURNSTILE-A` | prints `123456`, then access check runs |
| `EVENT\|CARD\|12345678\|DOOR-01` | no print, WARN — token[0] `EVENT` is not an integer |

After the last line, EOF closes stdin, runner logs `stdin closed (EOF)` and Spring exits with code 0.

**One-liner:**

```bash
echo '5`CARD-ID:00012345`DOOR-5' | java -jar target/ms-gates-0.0.1-SNAPSHOT.jar
```

Expected stdout: `00012345` (backtick delimiter, reader id `5`).

---

### Level 3 — Fixture file

Representative test set covering happy and malformed cases.

```bash
mkdir -p fixtures
cat > fixtures/readings.txt <<'EOF'
7|CARD|12345678|DOOR-01
7|CARD|87654321|DOOR-02
GARBAGE_NO_PIPES
7|CARD||DOOR-03
7|CARD|12|DOOR-04
7|CARD-ID:55554444|DOOR-05

7|CARD|99999999|DOOR-06
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
echo '7|CARD|12345678|DOOR-01' > /tmp/reader.fifo
sleep 1
echo '7|CARD|87654321|DOOR-02' > /tmp/reader.fifo
```

Terminal A prints each numeric as it arrives, runs the access check, and keeps waiting — exactly the production behavior.

**Test stream interruption** — close the writer side so Java sees EOF:

```bash
# Terminal B
exec 3>/tmp/reader.fifo
echo '7|CARD|55554444|DOOR-03' >&3
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
python3 -c "print('7|' + 'X' * 2000 + '|12345678')" | java -jar target/ms-gates-0.0.1-SNAPSHOT.jar
```

Expected: WARN `Dropping oversized line`, no stdout, process exits at EOF.

**Binary garbage** (verifies the "never crash on bad input" guarantee):

```bash
head -c 200 /dev/urandom | java -jar target/ms-gates-0.0.1-SNAPSHOT.jar
```

Should log WARNs and exit without a stack trace.

**Killing mid-stream:**

```bash
java -jar target/ms-gates-0.0.1-SNAPSHOT.jar < fixtures/readings.txt &
kill -TERM %1
```

`@PreDestroy` flips `running = false`, the reader loop exits, Spring shuts down cleanly. Exit code 143 (128 + SIGTERM) is expected.

**Catalog DB unreachable at startup:**

```bash
CATALOG_DB_PORT=1 java -jar target/ms-gates-0.0.1-SNAPSHOT.jar
```

Expected: `[E000] no se pudo cargar la caché de lectores…`, `IllegalStateException`, the context fails to start. This is the intended fail-fast behavior.

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

`stdin` cannot see HID keyboard events directly (they go to the focused window). A Python HID bridge process reads the USB HID interface and writes one scan per line to stdout — prefixed with the reader id and using `|` or backtick delimiters. Pipe that bridge into the Java process the same way as Step 2a.

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

### Startup, stdin reader & parser

| Level | Message | Meaning |
| --- | --- | --- |
| `INFO` | `[S_INIT] caché de lectores cargada` | reader→door cache loaded successfully at startup |
| `ERROR` | `[E000] no se pudo cargar la caché de lectores` | catalog cache load failed — service will not start |
| `INFO` | `Starting stdin reader thread` | runner wired correctly |
| `DEBUG` | `Received line [length=…]` | reader is flowing |
| `INFO` | `Credential extracted: …` | credential parsed and printed to stdout |
| `WARN` | `Discarded reading: could not parse id_lector or credential` | parser couldn't find an integer `id_lector` and a `\d{5,}` credential |
| `WARN` | `Dropping oversized line` | defensive 1 KiB cap hit |
| `ERROR` | `Unexpected failure while processing reading` | unexpected bug in parse/publish path |
| `ERROR` | `stdin read failed…` | IO death — supervisor will restart |
| `INFO` | `stdin closed (EOF)…` | clean end — bridge closed or `Ctrl+D` |

### Access check

| Code | Level | Message | Meaning |
| --- | --- | --- | --- |
| `S000` | `INFO` | `documento apto para ingresar` | credential found in DB, estado blank — proceeding to door resolution |
| `S001` | `INFO` | `notificación HTTP exitosa` | external system responded 200 |
| `S002` | `INFO` | `estado actualizado a '1' en DB` | local DB updated (emitted once by the use case); remote DB also updated unless `[E006]` appears |
| `S003` | `INFO` | `alias con prefijo '0' ya ingresado` | the `"0"`-prefixed alias visitor was already admitted (async) |
| `S004` | `INFO` | `alias con prefijo '0' actualizado` | the `"0"`-prefixed alias visitor was admitted too (async) |
| `E001` | `WARN` | `documento no presente` | credential not found in `invitados` table |
| `E002` | `WARN` | `documento ya ingresó` | credential found but `estado` is non-blank (already admitted) |
| `E003` | `ERROR` | `notificación HTTP fallida` | external system responded non-200; no DB update performed |
| `E004` | `ERROR` | `error inesperado en verificación de acceso` | unexpected runtime exception in access check |
| `E005` | `ERROR` | `error al actualizar estado en DB` | HTTP was 200 but the local DB update failed; `[S002]` will not appear |
| `E006` | `ERROR` | `error al actualizar estado en DB remota` | local DB updated but remote DB sync failed (best-effort — does not block admission) |
| `E007` | `ERROR` | `error al actualizar alias con prefijo '0'` | async alias update failed |
| `E008` | `WARN` | `id_puerto no encontrado para lector` | `id_lector` not present in the reader cache; no HTTP notification sent |

---

## Input contract

| Concern | Value |
| --- | --- |
| Encoding | UTF-8 |
| Record separator | `\n` (or `\r\n`, handled by `readLine()`) |
| Field separator | `\|` **or** backtick (`` ` ``) |
| First field (`token[0]`) | `id_lector` — must parse as an integer |
| Credential | first remaining token containing `\d{5,}` |
| One reading per | line |
| Trailing fields | allowed |
| Min tokens | 2 (id_lector + at least one more) |
| Max line length | 1 KiB (oversize dropped with WARN) |
| Malformed lines | tolerated, logged, dropped |

Extraction rule: after splitting, `token[0]` is parsed as the integer reader id; then the parser returns the first `\d{5,}` **subsequence** found inside any remaining token (via `Matcher.find()`). So `7|CARD-ID:12345678` yields reader `7` and credential `12345678`.

---

## Database contract

### Visitor tables (local + remote)

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

On successful admission the service writes `'1'` to `estado` in both databases via the Composite Adapter (see [Dual-database strategy](#dual-database-strategy--composite-adapter-pattern)). The `"0"`-prefixed alias, if present, is updated asynchronously (see [Alias admission](#alias-admission-prefix-0--async-event)).

### Catalog table (reader → door)

The catalog DB holds the reader-to-door mapping, loaded once at startup filtered by `READER_ID_TRIBUNA`:

```sql
SELECT id_lector, id_puerto FROM lectorxtribuna WHERE id_tribuna = ?;
```

| Column | Type | Meaning |
| --- | --- | --- |
| `id_lector` | INTEGER | physical reader id — matches `token[0]` of a scan |
| `id_puerto` | TEXT | physical door/port id — sent to the external system as `{"id_port":"…"}` |
| `id_tribuna` | INTEGER | tribune grouping — filter for this deployment |

---

## Production deployment notes

The process is intentionally short-lived on error — EOF or `IOException` triggers `SpringApplication.exit()`; a failure to load the reader cache at startup prevents boot entirely. The systemd unit supervises the full pipeline (Python HID bridge → Java service) with `Restart=always`.

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

> `journalctl -f` is live-only: closing the console or restarting the service loses the scrollback, and by default journald is volatile (wiped on reboot). To keep an exportable record, use the log collector below.

### Persisting & exporting logs (log collector)

A separate, self-contained systemd service — **`gates-log-collector`** — follows the `gates-pipeline` journal and appends every line to a persistent plain-text file, so logs survive console close, service restart and reboot, and can be exported later. It lives in `deploy/logging/` (at the `gates_project` root, since it covers the whole Python + Java pipeline, not just this Java service).

**How it stays reliable:** the collector runs `journalctl -u gates-pipeline -o short-iso -n all --follow --cursor-file=…`. The `--cursor-file` makes it resume **exactly** where it left off after any restart — no lost or duplicated lines. `logrotate` rotates the file daily (30 days kept, compressed); its `postrotate` restarts the collector so rotation is also lossless (the cursor guarantees continuity).

Output file:

```
/var/log/gates/gates-pipeline.log
```

**Install** (run on each Linux machine where the pipeline is deployed):

```bash
cd deploy/logging
sudo ./install.sh
```

This installs the scripts, the systemd unit and the logrotate rule, then enables and starts the collector (auto-starts on boot).

**Tail the persisted file** (equivalent to `journalctl -f`, but already saved to disk):

```bash
tail -f /var/log/gates/gates-pipeline.log
```

**Export** all history (current file + rotated `.gz`) into a single chronological `.txt`:

```bash
gates-logs-export.sh                 # -> ~/gates-pipeline-YYYYmmdd-HHMMSS.txt
gates-logs-export.sh /tmp/logs.txt   # explicit path
# then copy it off the box, e.g.:
scp user@machine:/tmp/logs.txt .
```

**Configurable** via environment variables in `gates-log-collector.service` (`GATES_UNIT`, `GATES_LOG_DIR`, `GATES_STATE_DIR`) and retention in `/etc/logrotate.d/gates-logs`. Requires systemd ≥ 245 (for `--cursor-file`). See `deploy/logging/README.md` for full details, including the optional hardening to make journald itself persistent.

### Environment files

- `/etc/gates/bridge.env` — Python HID bridge variables (USB VID/PID, log level, reader mode).
- `/etc/gates/ms-gates.env` — Java service variables (local/remote/catalog DB credentials, reader tribune, HTTP endpoint). See [Configuration](#configuration).

Permissions for the credentials file:

```bash
sudo chown root:gates /etc/gates/ms-gates.env
sudo chmod 640 /etc/gates/ms-gates.env
```
