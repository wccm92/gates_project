import logging
import sys

from config import Config  # absolute: src/ is on sys.path when running main.py

from .base import BaseReader

logger = logging.getLogger(__name__)


def build_reader(config: Config) -> BaseReader:
    mode = config.reader_mode

    if mode == 'simulate':
        from .simulator import SimulatorReader
        return SimulatorReader()

    if mode == 'serial':
        return _build_serial(config)

    if mode == 'hid':
        return _build_hid(config)

    if mode == 'auto':
        return _auto_detect(config)

    raise ValueError(
        f"Unknown READER_MODE: {mode!r}. Valid values: auto, hid, serial, simulate"
    )


# --- private helpers ---------------------------------------------------------

def _build_serial(config: Config) -> BaseReader:
    from .serial_reader import SerialReader
    device = config.serial_device or _detect_serial_device()
    if not device:
        raise RuntimeError(
            "No serial device found. "
            "Plug in the reader or set READER_SERIAL_DEVICE=/dev/ttyUSB0."
        )
    return SerialReader(device, config.serial_baud)


def _build_hid(config: Config) -> BaseReader:
    from .hid_reader import HidReader
    return HidReader(
        device_path=config.hid_device_path or None,
        vendor_id=config.hid_vendor_id,
        product_id=config.hid_product_id,
    )


def _auto_detect(config: Config) -> BaseReader:
    if sys.platform != 'linux':
        logger.warning(
            "Auto-detect is Linux-only. Falling back to simulate mode. "
            "Set READER_MODE=simulate to silence this warning on macOS/Windows."
        )
        from .simulator import SimulatorReader
        return SimulatorReader()

    device = _detect_serial_device()
    if device:
        logger.info("Auto-detected serial device: %s", device)
        from .serial_reader import SerialReader
        return SerialReader(device, config.serial_baud)

    logger.info("No serial device found, attempting HID auto-detection")
    return _build_hid(config)


def _detect_serial_device() -> str:
    try:
        import serial.tools.list_ports
        ports = list(serial.tools.list_ports.comports())
        if ports:
            return ports[0].device
    except Exception:
        pass
    return ''
