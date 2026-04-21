# ms-gates

Java microservice that powers turnstile access control. Phase 1 reads pipe-delimited scans from a physical USB reader over `stdin`, extracts the numeric credential (≥5 digits) from one of the attributes, and prints it.

---

## Table of contents

- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Build](#build)
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
- [Production deployment notes](#production-deployment-notes)

---

## Architecture

Clean / hexagonal layering. The microservice does **not** know it is talking to a USB reader — its only contract is "newline-terminated, pipe-delimited text on `stdin`". Hardware bridging lives in an OS-level wrapper.

```
┌─────────────────────┐     ┌──────────────────────┐     ┌────────────────────┐
│ USB Reader hardware │ ──▶ │ OS wrapper (cat /    │ ──▶ │ Java microservice  │
│ (HID or serial CDC) │     │ dev/ttyUSB0 | ...)   │     │ reads System.in    │
└─────────────────────┘     └──────────────────────┘     └────────────────────┘
         supervised by launchd / systemd (auto-start, auto-restart on crash)
```

### Package layout

```
src/main/java/com/gates/msgates/
├── MsGatesApplication.java                    Spring Boot entry point
├── config/
│   └── BeanConfiguration.java                 wires domain beans
├── domain/                                    framework-free
│   ├── model/
│   │   ├── RawReading.java                    record: payload + receivedAt
│   │   └── Credential.java                    record: validated numeric (≥5 digits)
│   └── usecase/
│       ├── ReadingParser.java                 pure parser
│       ├── ProcessReadingUseCase.java         orchestration
│       └── port/
│           └── CredentialPublisherPort.java   output port
├── adapters/
│   └── ConsoleCredentialPublisher.java        prints credential to stdout
└── entrypoints/
    └── StdinReaderRunner.java                 daemon reader thread
```

`domain/*` has zero Spring annotations. Only `adapters/` and `entrypoints/` touch the framework. Beans are wired in `config/BeanConfiguration.java`.

### Runtime flow

1. Spring boots → `StdinReaderRunner.run()` launches a daemon thread named `stdin-reader`.
2. The thread loops `BufferedReader.readLine()` on UTF-8 `System.in`.
3. Each non-blank line ≤1 KiB becomes a `RawReading(payload, Instant.now())`.
4. `ProcessReadingUseCase` calls `ReadingParser`, which splits on `|` and finds the first token containing `\d{5,}`.
5. On hit → `ConsoleCredentialPublisher.publish()` logs `INFO` and prints the numeric to stdout.
6. On miss → `WARN` log, reading dropped, loop continues.
7. Any `RuntimeException` is caught and logged — the reader thread is never killed by bad data.
8. On EOF or `IOException` → `SpringApplication.exit()` so the process supervisor can restart it.

---

## Prerequisites

- **JDK 20 or later** (Corretto 21 verified).
- **Maven 3.9+** (or use IntelliJ's bundled Maven).

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

| Input                                         | Result                                                  |
| --------------------------------------------- | ------------------------------------------------------- |
| `EVENT\|CARD\|12345678\|DOOR-01`              | prints `12345678`, INFO log                             |
| `EVENT\|CARD\|999\|DOOR-02`                   | no print, WARN "no numeric attribute of 5+ digits found" |
| `\|\|\|`                                      | no print, WARN                                          |
| `BADGE-SCAN\|USER\|ABC123456\|TURNSTILE-A`    | prints `123456` (extracted from inside `ABC123456`)     |

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

Expected: four numeric lines printed (`12345678`, `87654321`, `55554444`, `99999999`), blank line silently skipped, the others produce WARN logs and are dropped. Process exits 0 at EOF.

Keep this fixture in the repo — it becomes the base for future JUnit tests.

---

### Level 4 — Simulate a live reader with a FIFO

Closest simulation of the real USB flow without hardware. A FIFO behaves exactly like the pipe that `cat /dev/tty.usbserial-X | java -jar …` creates.

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

Terminal A prints each numeric as it arrives and keeps waiting — exactly the production behavior.

**Test stream interruption** — close the writer side so Java sees EOF:

```bash
# Terminal B
exec 3>/tmp/reader.fifo
echo 'EVENT|CARD|55554444|DOOR-03' >&3
exec 3>&-   # close FD 3 → Java sees EOF
```

Terminal A logs `stdin closed (EOF), reader thread exiting` and the JVM exits 0. In production, `launchd`/`systemd` would restart it immediately.

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
- **No diff**, but scanning into TextEdit "types" characters → HID keyboard mode (needs a bridge, Step 2b).

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

`stdin` cannot see HID keyboard events directly (they go to the focused window). You need a small bridge process — typically ~15 lines of Python using `hidapi` — that reads the HID interface and writes one scan per line to stdout. Pipe that bridge into the Java process the same way as Step 2a.

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

| Level   | Message                                          | Meaning                                           |
| ------- | ------------------------------------------------ | ------------------------------------------------- |
| `INFO`  | `Starting stdin reader thread`                   | runner wired correctly                            |
| `DEBUG` | `Received line [length=…]`                       | reader is flowing                                 |
| `INFO`  | `Credential extracted: …`                        | happy path                                        |
| `WARN`  | `Discarded reading: no numeric attribute…`       | parser couldn't find `\d{5,}`                     |
| `WARN`  | `Dropping oversized line`                        | defensive 1 KiB cap hit                           |
| `ERROR` | `Unexpected failure while processing reading`    | bug — should not happen with current code         |
| `ERROR` | `stdin read failed…`                             | IO death — let supervisor restart                 |
| `INFO`  | `stdin closed (EOF)…`                            | clean end — wrapper closed or `Ctrl+D`            |

---

## Input contract

| Concern            | Value                                        |
| ------------------ | -------------------------------------------- |
| Encoding           | UTF-8                                        |
| Record separator   | `\n` (or `\r\n`, handled by `readLine()`)    |
| Field separator    | `\|`                                         |
| One reading per    | line                                         |
| Trailing fields    | allowed                                      |
| Max line length    | 1 KiB (oversize dropped with WARN)           |
| Malformed lines    | tolerated, logged, dropped                   |

Extraction rule: the parser returns the first `\d{5,}` **subsequence** found inside any pipe-delimited token (via `Matcher.find()`). So `CARD-ID:12345678` yields `12345678`.

---

## Production deployment notes

The process is intentionally short-lived on error — EOF or `IOException` triggers `SpringApplication.exit()`. Supervise it so unplug/replug cycles auto-recover:

**macOS — `launchd`:** create `~/Library/LaunchAgents/com.gates.msgates.plist` with `KeepAlive=true` and a `ProgramArguments` entry that runs a shell wrapper piping the device into the jar.

**Linux — `systemd`:** create `/etc/systemd/system/ms-gates.service` with `Restart=always` and `ExecStart=/bin/sh -c 'stdbuf -oL cat /dev/ttyUSB0 | java -jar /opt/ms-gates/ms-gates.jar'`.

Neither the Java code nor its configuration changes between dev and prod — only the wrapper around `stdin` does.
