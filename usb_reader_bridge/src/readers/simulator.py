import logging
import sys
from typing import Iterator

from .base import BaseReader

logger = logging.getLogger(__name__)


class SimulatorReader(BaseReader):
    """
    Reads scan lines from stdin.
    Use this mode for development and CI on any OS (including macOS).
    Each line typed or piped in is treated as one USB scan.
    """

    def read_lines(self) -> Iterator[str]:
        logger.info("Simulator: reading from stdin — type or pipe lines to simulate USB scans")
        for raw in sys.stdin:
            line = raw.rstrip('\r\n')
            if line:
                yield line
