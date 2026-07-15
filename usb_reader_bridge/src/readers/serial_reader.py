import logging
import queue
import threading
from typing import Iterator, List, Tuple

import serial

from .base import BaseReader

logger = logging.getLogger(__name__)


class SerialReader(BaseReader):
    """
    Reads from a serial-CDC USB reader (device registers as /dev/ttyUSB* or /dev/ttyACM*).
    Each CR/LF-terminated line from the device becomes one scan.
    Requires the running user to be in the 'dialout' group on Linux:
        sudo usermod -aG dialout <user>
    """

    def __init__(self, device: str, baud: int = 9600) -> None:
        logger.info("Opening serial device %s at %d baud", device, baud)
        self._port = serial.Serial(device, baud, timeout=1)

    def read_lines(self) -> Iterator[str]:
        while True:
            try:
                raw = self._port.readline()
                if not raw:
                    continue
                line = raw.decode('utf-8', errors='replace').strip()
                if line:
                    yield line
            except serial.SerialException as exc:
                logger.error("Serial read error: %s", exc)
                break

    def close(self) -> None:
        if self._port.is_open:
            self._port.close()
            logger.info("Serial port closed")


# Sentinel pushed to the queue by a worker when its port dies, so the
# multiplexer can tell "no data right now" apart from "this reader is gone".
_WORKER_DONE = object()


class MultiSerialReader(BaseReader):
    """
    Reads from several serial-CDC USB readers at once (one COM/tty port each)
    and multiplexes their scans into a single stream of lines.

    One background thread per port pushes whole CR/LF-terminated lines onto a
    shared queue; read_lines() drains that queue, so two people scanning at the
    same time on different readers never interleave characters — every line is
    emitted whole. If one reader is unplugged mid-run it is dropped and the rest
    keep working; when every reader is gone the stream ends (systemd/NSSM then
    restarts the process). Mirrors the multi-device behaviour of HidReader for
    Windows, where each reader is a separate COM port instead of one VID/PID.
    """

    def __init__(self, devices: List[str], baud: int = 9600) -> None:
        self._baud = baud
        # Each item is (device, line); line is the sentinel _WORKER_DONE when
        # that port's worker exits.
        self._queue: "queue.Queue[Tuple[str, object]]" = queue.Queue()
        self._stop = threading.Event()
        self._ports: List[serial.Serial] = []
        self._threads: List[threading.Thread] = []

        for device in devices:
            try:
                port = serial.Serial(device, baud, timeout=1)
            except serial.SerialException as exc:
                logger.error("Could not open serial device %s: %s", device, exc)
                continue
            self._ports.append(port)
            logger.info("Opened serial device %s at %d baud", device, baud)

        if not self._ports:
            raise RuntimeError(
                "No serial reader could be opened. "
                "Check the COM/tty paths in READER_SERIAL_DEVICE, that each "
                "reader is connected and in serial/CDC mode, and that no other "
                "program is holding the port."
            )

        logger.info("Listening on %d serial device(s)", len(self._ports))

    def read_lines(self) -> Iterator[str]:
        for port in self._ports:
            thread = threading.Thread(
                target=self._pump, args=(port,), name=f"serial-{port.port}", daemon=True
            )
            thread.start()
            self._threads.append(thread)

        alive = len(self._ports)
        while alive > 0:
            device, line = self._queue.get()
            if line is _WORKER_DONE:
                alive -= 1
                continue
            if line:
                yield line

        logger.error("All serial devices disconnected; stopping serial reader")

    def _pump(self, port: serial.Serial) -> None:
        """Read one port in its own thread; push each line to the shared queue."""
        try:
            while not self._stop.is_set():
                try:
                    raw = port.readline()
                except serial.SerialException as exc:
                    logger.error("Serial device %s read error: %s", port.port, exc)
                    break
                if not raw:
                    continue  # readline timeout (1s): no data, keep polling
                line = raw.decode('utf-8', errors='replace').strip()
                if line:
                    self._queue.put((port.port, line))
        finally:
            self._queue.put((port.port, _WORKER_DONE))

    def close(self) -> None:
        self._stop.set()
        for port in self._ports:
            try:
                if port.is_open:
                    port.close()
            except Exception:
                pass
        logger.info("Serial device(s) released")
