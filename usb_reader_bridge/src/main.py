import logging
import signal
import sys
import time
from pathlib import Path

try:
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).parent.parent / '.env')
except ImportError:
    pass

from config import Config
from readers import build_reader


def setup_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format='%(asctime)s [%(levelname)s] %(name)s: %(message)s',
        stream=sys.stderr,  # logs on stderr; scan lines go to stdout for piping
    )


def main() -> None:
    config = Config.from_env()
    setup_logging(config.log_level)
    logger = logging.getLogger(__name__)
    logger.info("Starting USB reader bridge [mode=%s]", config.reader_mode)

    reader = build_reader(config)

    def on_shutdown(signum, _frame):
        logger.info("Received signal %d, shutting down", signum)
        reader.close()
        sys.exit(0)

    signal.signal(signal.SIGTERM, on_shutdown)
    signal.signal(signal.SIGINT, on_shutdown)

    block_seconds = config.block_seconds
    last_scan_at: dict[str, float] = {}

    try:
        for line in reader.read_lines():
            dato1 = line.split('|', 1)[0]
            now = time.monotonic()
            remaining = block_seconds - (now - last_scan_at.get(dato1, 0.0))
            if remaining > 0:
                logger.debug("Scan discarded — input blocked for dato1=%r (%.1fs remaining)", dato1, remaining)
                continue
            last_scan_at[dato1] = now
            logger.debug("Forwarding line [length=%d, dato1=%r]", len(line), dato1)
            print(line, flush=True)   # flush=True is critical: prevents buffering in pipes
    except KeyboardInterrupt:
        pass
    finally:
        reader.close()
        logger.info("USB reader bridge stopped")


if __name__ == '__main__':
    main()
