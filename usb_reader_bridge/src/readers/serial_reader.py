import logging
from typing import Iterator

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
