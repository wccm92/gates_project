import logging
import signal
import sys

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

    try:
        for line in reader.read_lines():
            logger.debug("Forwarding line [length=%d]", len(line))
            print(line, flush=True)   # flush=True is critical: prevents buffering in pipes
    except KeyboardInterrupt:
        pass
    finally:
        reader.close()
        logger.info("USB reader bridge stopped")


if __name__ == '__main__':
    main()
