# usb-reader-bridge

Python service that reads from a physical USB access card/badge reader and forwards each scan as a plain-text line to stdout. Designed to be piped directly into `ms-gates`:

```
USB Reader  →  usb-reader-bridge (stdout)  →  ms-gates (stdin)
```

---

## Table of contents

- [How it works](#how-it-works)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Configuration](#configuration)
- [Testing without the USB reader](#testing-without-the-usb-reader)
  - [Simulate mode — manual input](#1-simulate-mode--manual-input)
  - [Simulate mode — scripted one-shot](#2-simulate-mode--scripted-one-shot)
  - [Simulate mode — fixture file](#3-simulate-mode--fixture-file)
  - [Simulate mode — FIFO (continuous stream)](#4-simulate-mode--fifo-continuous-stream)
  - [Full pipeline — one pipe, one terminal (quickest)](#5-full-pipeline--one-pipe-one-terminal-quickest)
  - [Full pipeline — two terminals via FIFO (separated logs)](#6-full-pipeline--two-terminals-via-fifo-separated-logs)
- [Testing with the USB reader](#testing-with-the-usb-reader)
  - [Step 1 — identify your reader type](#step-1--identify-your-reader-type)
  - [Serial-CDC reader](#serial-cdc-reader)
  - [HID keyboard-emulating reader](#hid-keyboard-emulating-reader)
  - [Auto-detect mode](#auto-detect-mode)
- [Error and edge-case scenarios](#error-and-edge-case-scenarios)
- [Log reference](#log-reference)
- [Environment variable reference](#environment-variable-reference)
- [Packaging the application](#packaging-the-application)
  - [Option 1 — venv + pip (recommended)](#option-1--venv--pip-recommended)
  - [Option 2 — PyInstaller single binary (JAR-like)](#option-2--pyinstaller-single-binary-jar-like)
- [Production deployment (Linux systemd)](#production-deployment-linux-systemd)
- [Troubleshooting](#troubleshooting)

---

## How it works

The bridge has four reader modes, selected via the `READER_MODE` env var:

| Mode | What it reads from | OS support |
|---|---|---|
| `simulate` | stdin — type or pipe lines manually | Linux, macOS, Windows |
| `serial` | `/dev/ttyUSB*` or `/dev/ttyACM*` via `pyserial` | Linux, macOS |
| `hid` | `/dev/input/eventX` via `evdev` | Linux only |
| `auto` | serial first, HID fallback; simulate on non-Linux | Linux (all modes), macOS (simulate only) |

In every mode the bridge writes one line per scan to **stdout** and all logs to **stderr**, making it safely pipeable.

---

## Prerequisites

- Python 3.9 or later
- pip

Check your version:

```bash
python3 --version
```

---

## Installation

```bash
cd usb_reader_bridge
python3 -m venv .venv
source .venv/bin/activate        # macOS / Linux
# .venv\Scripts\activate         # Windows

pip install -r requirements.txt
```

`evdev` is Linux-only and is skipped automatically on macOS/Windows (see `requirements.txt` platform marker).

---

## Configuration

All configuration is done through environment variables. For local development, export them in your shell or create a `.env` file and source it. For production, use the `EnvironmentFile` directive in the systemd unit.

| Variable | Default | Description |
|---|---|---|
| `READER_MODE` | `auto` | `auto` / `hid` / `serial` / `simulate` |
| `READER_SERIAL_DEVICE` | _(auto-detect)_ | Serial device path, e.g. `/dev/ttyUSB0` |
| `READER_SERIAL_BAUD` | `9600` | Serial baud rate |
| `READER_HID_DEVICE` | _(auto-detect)_ | HID device path, e.g. `/dev/input/event3` |
| `READER_HID_VID` | _(any)_ | HID vendor ID in hex, e.g. `0x05e0` |
| `READER_HID_PID` | _(any)_ | HID product ID in hex, e.g. `0x1200` |
| `LOG_LEVEL` | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |

---

## Testing without the USB reader

All scenarios below work on macOS and Linux without any hardware connected.

### 1. Simulate mode — manual input

Start the bridge and type scan lines interactively:

```bash
READER_MODE=simulate python3 -u src/main.py
```

Expected startup log (on stderr):

```
2024-01-15 10:00:00 [INFO] __main__: Starting USB reader bridge [mode=simulate]
2024-01-15 10:00:00 [INFO] readers.simulator: Simulator: reading from stdin — type or pipe lines to simulate USB scans
```

Now type a line and press Enter:

```
EVENT|CARD|12345678|DOOR-01
```

Expected stdout output:

```
EVENT|CARD|12345678|DOOR-01
```

Press `Ctrl+D` to send EOF and stop the bridge cleanly.

---

### 2. Simulate mode — scripted one-shot

Feed a single scan via `echo`:

```bash
echo 'EVENT|CARD|12345678|DOOR-01' | READER_MODE=simulate python3 -u src/main.py
```

Expected stdout:

```
EVENT|CARD|12345678|DOOR-01
```

Feed multiple scans via heredoc:

```bash
READER_MODE=simulate python3 -u src/main.py <<'EOF'
EVENT|CARD|12345678|DOOR-01
EVENT|CARD|87654321|DOOR-02
BADGE|USER|55554444|TURNSTILE-B
EOF
```

Expected stdout (three lines, one per scan):

```
EVENT|CARD|12345678|DOOR-01
EVENT|CARD|87654321|DOOR-02
BADGE|USER|55554444|TURNSTILE-B
```

---

### 3. Simulate mode — fixture file

Create a reusable fixture that covers all parser outcomes:

```bash
mkdir -p fixtures
cat > fixtures/scans.txt <<'EOF'
EVENT|CARD|12345678|DOOR-01
EVENT|CARD|87654321|DOOR-02
GARBAGE_NO_PIPES
EVENT|CARD||DOOR-03
EVENT|CARD|12|DOOR-04
EVENT|CARD|CARD-ID:55554444|DOOR-05

EVENT|CARD|99999999|DOOR-06
EOF
```

Run the bridge against it:

```bash
READER_MODE=simulate python3 -u src/main.py < fixtures/scans.txt
```

Expected stdout (bridge forwards every line it reads — ms-gates decides which to accept):

```
EVENT|CARD|12345678|DOOR-01
EVENT|CARD|87654321|DOOR-02
GARBAGE_NO_PIPES
EVENT|CARD||DOOR-03
EVENT|CARD|12|DOOR-04
EVENT|CARD|CARD-ID:55554444|DOOR-05
EVENT|CARD|99999999|DOOR-06
```

The blank line is swallowed by the simulator (blank lines are not forwarded). The bridge's job is transparent forwarding; ms-gates handles parsing and validation.

---

### 4. Simulate mode — FIFO (continuous stream)

A named pipe (FIFO) lets you push scans at any pace while the bridge stays running — the closest simulation to real hardware without a device.

```bash
mkfifo /tmp/usb-sim.fifo
```

**Terminal A** — start the bridge reading from the FIFO:

```bash
READER_MODE=simulate python3 -u src/main.py < /tmp/usb-sim.fifo
```

The bridge blocks and waits. In a second terminal push scans one at a time:

**Terminal B** — push scans:

```bash
echo 'EVENT|CARD|12345678|DOOR-01' > /tmp/usb-sim.fifo
sleep 2
echo 'EVENT|CARD|87654321|DOOR-02' > /tmp/usb-sim.fifo
```

Terminal A immediately forwards each line as it arrives — this is the real-time behavior.

**Test stream interruption** — simulate the reader disconnecting:

```bash
# In Terminal B: open the FIFO, send one scan, then close it
exec 3>/tmp/usb-sim.fifo
echo 'EVENT|CARD|55554444|DOOR-03' >&3
exec 3>&-   # closing FD 3 sends EOF to the bridge
```

Terminal A logs `USB reader bridge stopped` and exits 0. In production, `systemd` would restart it.

**Cleanup:**

```bash
rm /tmp/usb-sim.fifo
```

---

### 5. Full pipeline — one pipe, one terminal (quickest)

The fastest way to verify both services work together end to end. The bridge reads what you type (simulate mode) and pipes each line directly into ms-gates — no FIFO, no extra terminals.

**Prerequisites:** build the Java jar if you haven't yet:

```bash
cd ../ms_gates && mvn clean package -DskipTests && cd ../usb_reader_bridge
```

**Start both services together from the `gates_project` root:**

```bash
cd /path/to/gates_project

READER_MODE=simulate python3 -u usb_reader_bridge/src/main.py \
  | java -jar ms_gates/target/ms-gates-0.0.1-SNAPSHOT.jar
```

Once both are running you will see startup logs from both services in the same terminal. Type a scan line and press Enter:

```
EVENT|CARD|12345678|DOOR-01
```

Expected output:

```
# Bridge log (stderr):
2024-01-15 10:00:00 [INFO] __main__: Starting USB reader bridge [mode=simulate]
2024-01-15 10:00:00 [INFO] readers.simulator: Simulator: reading from stdin — type or pipe lines to simulate USB scans

# Java log (console):
INFO  ...MsGatesApplication : Started MsGatesApplication in 1.4 seconds
INFO  ...StdinReaderRunner  : Starting stdin reader thread

# After typing the scan line:
INFO  ...ConsoleCredentialPublisher : Credential extracted: 12345678
12345678   ← extracted credential printed to stdout
```

**How the data flows:**

```
You type a line
  → bridge stdin  (simulate mode)
  → bridge stdout (print + flush=True)
  → [OS pipe]
  → ms-gates stdin (BufferedReader.readLine())
  → ReadingParser splits on | and finds \d{5,}
  → ConsoleCredentialPublisher prints the credential
```

Bridge logs go to stderr and Java logs go to its console — both appear mixed in the same terminal, which is fine for testing. Press `Ctrl+C` to stop both services cleanly.

---

### 6. Full pipeline — two terminals via FIFO (separated logs)

Use this when you want bridge and Java output in separate windows, or when you need to script scan injection independently.

```bash
mkfifo /tmp/pipeline.fifo
```

**Terminal A** — start ms-gates reading from the FIFO:

```bash
java -jar ms_gates/target/ms-gates-0.0.1-SNAPSHOT.jar < /tmp/pipeline.fifo
```

**Terminal B** — start the bridge writing to the FIFO (type scan lines here):

```bash
READER_MODE=simulate python3 -u usb_reader_bridge/src/main.py > /tmp/pipeline.fifo
```

Type a scan line in Terminal B and watch ms-gates react in Terminal A. You should see `Credential extracted: 12345678` in Terminal A and `12345678` printed to its stdout.

**Cleanup:**

```bash
rm /tmp/pipeline.fifo
```

---

## Testing with the USB reader

### Step 1 — identify your reader type

Plug in the USB reader, then run:

**On Linux:**

```bash
# Check for serial device
ls /dev/tty{USB,ACM}* 2>/dev/null

# Check for HID input device
sudo evtest   # lists all input devices; look for one that appears after plugging in
```

**On macOS:**

```bash
ls /dev/tty.* > /tmp/before.txt
# Plug the reader in
ls /dev/tty.* > /tmp/after.txt
diff /tmp/before.txt /tmp/after.txt
```

**Result interpretation:**

| Observation | Reader type | Mode to use |
|---|---|---|
| New `/dev/ttyUSB*` or `/dev/ttyACM*` appears | Serial-CDC | `serial` |
| New `/dev/tty.usbserial-*` appears (macOS) | Serial-CDC | `serial` |
| No new device, but scanning "types" in a text editor | HID keyboard | `hid` |

---

### Serial-CDC reader

#### Check permissions (Linux only)

```bash
ls -la /dev/ttyUSB0   # or ttyACM0
# Should show: crw-rw---- 1 root dialout ...
# Your user must be in the 'dialout' group

groups $USER          # check current groups
sudo usermod -aG dialout $USER
# Log out and back in for the group to take effect
```

#### Find the exact device path

```bash
# Linux
ls /dev/tty{USB,ACM}*

# macOS
ls /dev/tty.usb*
```

#### Run the bridge

```bash
READER_MODE=serial \
READER_SERIAL_DEVICE=/dev/ttyUSB0 \
python3 -u src/main.py
```

Scan a card. Expected stdout (one line per scan, exactly as the reader sends it):

```
EVENT|CARD|12345678|DOOR-01
```

#### Baud rate mismatch

If the bridge starts but nothing appears when you scan, the baud rate is probably wrong. Common rates are 9600, 19200, 38400, 115200. Check your reader's manual, then:

```bash
READER_MODE=serial \
READER_SERIAL_DEVICE=/dev/ttyUSB0 \
READER_SERIAL_BAUD=115200 \
python3 -u src/main.py
```

On Linux you can also inspect the current port settings:

```bash
stty -F /dev/ttyUSB0
```

#### Verbose debug output

```bash
READER_MODE=serial \
READER_SERIAL_DEVICE=/dev/ttyUSB0 \
LOG_LEVEL=DEBUG \
python3 -u src/main.py
```

---

### HID keyboard-emulating reader

**Linux only.** The `evdev` library is not available on macOS — use `simulate` mode there.

#### Check permissions

```bash
ls -la /dev/input/event*
# Should show: crw-rw---- 1 root input ...
# Your user must be in the 'input' group

groups $USER
sudo usermod -aG input $USER
# Log out and back in for the group to take effect
```

#### Find the exact device path

List all input devices and find the one that matches your reader:

```bash
sudo evtest
# Shows a numbered list of /dev/input/eventX devices with their names.
# Plug/unplug the reader to see which entry appears and disappears.
```

Alternatively, watch udev events while plugging in:

```bash
udevadm monitor --udev --subsystem-match=input
# Plug in the reader. Note the DEVNAME that appears.
```

#### Find VID and PID (useful for pinning the device)

```bash
lsusb
# Example output:
# Bus 001 Device 003: ID 05e0:1200 Symbol Technologies Bar Code Scanner
#                        ^^^^ ^^^^
#                        VID  PID
```

#### Run with explicit device path (recommended)

```bash
READER_MODE=hid \
READER_HID_DEVICE=/dev/input/event3 \
python3 -u src/main.py
```

#### Run with VID/PID (survives device re-enumeration)

```bash
READER_MODE=hid \
READER_HID_VID=0x05e0 \
READER_HID_PID=0x1200 \
python3 -u src/main.py
```

#### Run with auto-detection (convenient, not for production)

```bash
READER_MODE=hid python3 -u src/main.py
```

The bridge picks the first input device that has digit keys and Enter — usually correct if your reader is the only non-keyboard HID device connected.

Scan a card. Expected stdout:

```
EVENT|CARD|12345678|DOOR-01
```

#### Verify exclusive grab is working

While the bridge is running, open a text editor and scan a card. If nothing is typed into the editor, the exclusive grab is working correctly. If characters still appear, the grab may have failed (check for permission errors in stderr).

---

### Auto-detect mode

`auto` tries serial first, then HID. Useful for plug-and-play on a Linux machine where only one reader is connected at a time.

```bash
READER_MODE=auto python3 -u src/main.py
```

Startup logs show which device was selected:

```
# Serial detected:
[INFO] readers: Auto-detected serial device: /dev/ttyUSB0

# No serial found, HID tried:
[INFO] readers: No serial device found, attempting HID auto-detection
[INFO] readers.hid_reader: Grabbed HID device: /dev/input/event3  name=FEIG USB-OEM Reader
```

For production, replace `auto` with the specific mode and device path after you have confirmed which type your reader is.

---

## Error and edge-case scenarios

### Reader disconnected mid-run

```bash
# Start the bridge, then physically unplug the reader

# Serial: pyserial raises SerialException → bridge logs ERROR and exits 0
# HID: evdev read_loop() terminates → bridge exits 0
# Both: systemd restarts the process (Restart=always in the service unit)
```

### No reader connected (serial mode)

```bash
READER_MODE=serial READER_SERIAL_DEVICE=/dev/ttyUSB0 python3 -u src/main.py
```

Expected stderr:

```
[ERROR] ...could not open port /dev/ttyUSB0: [Errno 2] No such file or directory
```

Bridge exits with a non-zero code. Systemd restarts with the configured `RestartSec` delay.

### No reader connected (HID mode, no VID/PID)

```bash
READER_MODE=hid python3 -u src/main.py
```

Expected stderr:

```
[ERROR] No HID keyboard device found.
  - Set READER_HID_DEVICE=/dev/input/eventX (run `evtest` to find the right one)
  ...
```

### Permission denied (missing group membership)

```bash
READER_MODE=hid READER_HID_DEVICE=/dev/input/event3 python3 -u src/main.py
```

Expected stderr:

```
PermissionError: [Errno 13] Permission denied: '/dev/input/event3'
```

Fix: `sudo usermod -aG input $USER` then log out and back in.

### Wrong READER_MODE value

```bash
READER_MODE=bluetooth python3 -u src/main.py
```

Expected stderr:

```
ValueError: Unknown READER_MODE: 'bluetooth'. Valid values: auto, hid, serial, simulate
```

### HID mode on macOS

```bash
READER_MODE=hid python3 -u src/main.py
```

Expected stderr:

```
RuntimeError: HidReader uses Linux evdev and cannot run on this OS.
Set READER_MODE=simulate for development on macOS/Windows.
```

---

## Log reference

All logs go to **stderr**. Stdout carries only scan lines. Enable `DEBUG` for line-level tracing:

```bash
LOG_LEVEL=DEBUG READER_MODE=simulate python3 -u src/main.py
```

| Level | Message | Meaning |
|---|---|---|
| `INFO` | `Starting USB reader bridge [mode=…]` | bridge started successfully |
| `INFO` | `Simulator: reading from stdin` | simulate mode active |
| `INFO` | `Opening serial device … at … baud` | serial port opened |
| `INFO` | `Grabbed HID device: … name=…` | HID device claimed exclusively |
| `INFO` | `Auto-detected serial device: …` | auto mode picked serial |
| `DEBUG` | `Forwarding line [length=…]` | one scan forwarded (verbose mode) |
| `WARNING` | `Auto-detect is Linux-only. Falling back to simulate` | running on macOS/Windows |
| `WARNING` | `%d HID keyboard devices found; using first: …` | ambiguous auto-detect |
| `ERROR` | `Serial read error: …` | serial IO failure, bridge will exit |
| `INFO` | `Serial port closed` / `HID device released` | clean shutdown |
| `INFO` | `USB reader bridge stopped` | process exiting cleanly |

---

## Environment variable reference

Recommended: create `/etc/gates/bridge.env` for production, or a local `.env` file for development:

```env
# /etc/gates/bridge.env  (production example — serial reader)
READER_MODE=serial
READER_SERIAL_DEVICE=/dev/ttyUSB0
READER_SERIAL_BAUD=9600
LOG_LEVEL=INFO
```

```env
# .env  (development example — HID reader with pinned VID/PID)
READER_MODE=hid
READER_HID_VID=0x05e0
READER_HID_PID=0x1200
LOG_LEVEL=DEBUG
```

Load it in your shell for a local run:

```bash
set -a; source .env; set +a
python3 -u src/main.py
```

---

## Packaging the application

Python does not compile to a single universal artifact the way Java does (`mvn package` → `.jar`). The table below shows the closest equivalents:

| Java | Python equivalent | Python runtime needed on server? |
|---|---|---|
| Fat JAR (all deps bundled) | PyInstaller single binary | No |
| Source + `mvn install` on server | venv + `pip install -r requirements.txt` | Yes (Python 3) |

---

### Option 1 — venv + pip (recommended)

The standard Python approach. Python 3 is pre-installed on every modern Linux server, so no extra tooling is needed on the target machine. This is the recommended option for this project because `evdev` is a C extension that can be tricky to bundle with PyInstaller.

**On the Linux server — one-time setup:**

```bash
git clone <repo> /opt/usb-reader-bridge
cd /opt/usb-reader-bridge
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

**Verify the installation:**

```bash
.venv/bin/python3 -u src/main.py --help 2>&1 || \
  READER_MODE=simulate .venv/bin/python3 -u src/main.py <<< 'EVENT|CARD|12345678|DOOR-01'
```

**To update after a new release:**

```bash
cd /opt/usb-reader-bridge
git pull
.venv/bin/pip install -r requirements.txt   # only needed if requirements.txt changed
sudo systemctl restart gates-pipeline       # or whichever unit name you used
```

The systemd unit's `ExecStart` must point to the venv Python, not the system Python:

```ini
ExecStart=/opt/usb-reader-bridge/.venv/bin/python3 -u src/main.py
```

---

### Option 2 — PyInstaller single binary (JAR-like)

Bundles the Python interpreter and all dependencies into one self-contained executable — no Python installation required on the target machine. Closest equivalent to a fat JAR.

**Important constraint:** PyInstaller binaries are OS and architecture specific. Since the target is Linux, **you must build on Linux** (use a Linux VM, Docker container, or the target server itself). A binary built on macOS will not run on Linux.

**Install PyInstaller in the project venv:**

```bash
pip install pyinstaller
```

**Build the binary:**

```bash
cd usb_reader_bridge
pyinstaller --onefile src/main.py --name usb-reader-bridge
```

This produces a single executable at `dist/usb-reader-bridge`.

**Copy to the server and run:**

```bash
scp dist/usb-reader-bridge user@server:/opt/usb-reader-bridge/
ssh user@server "chmod +x /opt/usb-reader-bridge/usb-reader-bridge"

# Run directly — no Python needed:
READER_MODE=serial /opt/usb-reader-bridge/usb-reader-bridge
```

**Systemd `ExecStart` for the binary:**

```ini
ExecStart=/opt/usb-reader-bridge/usb-reader-bridge
```

**When to prefer this over Option 1:**
- Deploying to many machines where Python version may vary
- Target machines where you cannot install packages (restricted environments)
- You want a single versioned artifact to ship (like a `.jar`)

**When to stick with Option 1:**
- Single known Linux machine (your case for now)
- `evdev` C extension causes bundling issues
- Easier to inspect and debug source on the server

---

## Production deployment (Linux systemd)

### Option A — combined pipeline unit (recommended for phase 1)

Both the bridge and ms-gates run as one process group. Any failure restarts both.

Create `/etc/systemd/system/gates-pipeline.service`:

```ini
[Unit]
Description=Gates Access Control Pipeline (USB bridge + microservice)
After=network.target

[Service]
Type=simple
User=gates
# gates user must be in 'dialout' (serial) or 'input' (HID) group
WorkingDirectory=/opt/usb-reader-bridge
ExecStart=/bin/sh -c '.venv/bin/python3 -u src/main.py | java -jar /opt/ms-gates/ms-gates.jar'
Restart=always
RestartSec=5
EnvironmentFile=/etc/gates/bridge.env
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable gates-pipeline
sudo systemctl start gates-pipeline
sudo journalctl -fu gates-pipeline   # follow logs
```

### Option B — two independent units via FIFO

Each service restarts independently. The Java service must start before the bridge (otherwise the bridge blocks trying to open the FIFO for writing).

```bash
sudo mkdir -p /run/gates
sudo mkfifo /run/gates/reader.fifo
sudo chown gates:gates /run/gates/reader.fifo
```

`/etc/systemd/system/ms-gates.service`:

```ini
[Unit]
Description=Gates Microservice
After=network.target

[Service]
Type=simple
User=gates
ExecStart=java -jar /opt/ms-gates/ms-gates.jar
StandardInput=file:/run/gates/reader.fifo
Restart=always
RestartSec=3
EnvironmentFile=/etc/gates/ms-gates.env
```

`/etc/systemd/system/usb-reader-bridge.service`:

```ini
[Unit]
Description=USB Reader Bridge
After=ms-gates.service
Requires=ms-gates.service

[Service]
Type=simple
User=gates
WorkingDirectory=/opt/usb-reader-bridge
ExecStart=/bin/sh -c '.venv/bin/python3 -u src/main.py > /run/gates/reader.fifo'
Restart=always
RestartSec=3
EnvironmentFile=/etc/gates/bridge.env
```

---

## Troubleshooting

### Bridge starts but nothing appears when I scan

1. Confirm the reader is the device you think it is: unplug it and verify the `/dev/ttyUSBX` or `/dev/input/eventX` entry disappears.
2. For serial: try a different baud rate (`READER_SERIAL_BAUD=115200`).
3. For HID: run `sudo evtest /dev/input/eventX` while scanning — you should see raw key events. If you don't, the reader may not be recognized as a keyboard by the kernel.
4. Enable `LOG_LEVEL=DEBUG` and check stderr for any warnings.

### Serial bridge starts but outputs garbage characters

The baud rate is wrong. Check the reader's manual and try common values: `9600`, `19200`, `38400`, `115200`.

### HID bridge grabs the device but output looks garbled

The key map in `hid_reader.py` assumes a US keyboard layout. If your reader sends in a different layout, the `_build_key_map()` function needs to be updated.

### Bridge exits immediately on Linux with `PermissionError`

Missing group membership. Run `groups $USER` and check for `dialout` (serial) or `input` (HID). Add with `sudo usermod -aG <group> $USER` and log out/in.

### Multiple HID keyboard devices found warning

The auto-detect heuristic picked the first keyboard-like device. Pin the exact device:

```bash
# Find your reader:
sudo evtest   # look for the reader by name

# Then set explicitly:
READER_HID_DEVICE=/dev/input/event3 READER_MODE=hid python3 -u src/main.py
```
