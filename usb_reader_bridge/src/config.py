import os
from dataclasses import dataclass
from typing import Optional


@dataclass
class Config:
    reader_mode: str = 'auto'       # auto | hid | serial | simulate
    serial_device: str = ''         # e.g. /dev/ttyUSB0 or /dev/ttyACM0
    serial_baud: int = 9600
    hid_device_path: str = ''       # e.g. /dev/input/event3 (optional, skips auto-detect)
    hid_vendor_id: Optional[int] = None   # hex or decimal, e.g. 0x05e0
    hid_product_id: Optional[int] = None  # hex or decimal, e.g. 0x1200
    block_seconds: float = 6.0      # cooldown after each accepted scan
    log_level: str = 'INFO'

    @classmethod
    def from_env(cls) -> 'Config':
        return cls(
            reader_mode=os.getenv('READER_MODE', 'auto'),
            serial_device=os.getenv('READER_SERIAL_DEVICE', ''),
            serial_baud=int(os.getenv('READER_SERIAL_BAUD', '9600')),
            hid_device_path=os.getenv('READER_HID_DEVICE', ''),
            hid_vendor_id=_parse_int(os.getenv('READER_HID_VID')),
            hid_product_id=_parse_int(os.getenv('READER_HID_PID')),
            block_seconds=float(os.getenv('READER_BLOCK_SECONDS', '6')),
            log_level=os.getenv('LOG_LEVEL', 'INFO'),
        )


def _parse_int(value: Optional[str]) -> Optional[int]:
    if not value:
        return None
    return int(value, 0)  # handles 0x-prefixed hex and plain integers
