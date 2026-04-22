import logging
import sys
from typing import Iterator, Optional

from .base import BaseReader

logger = logging.getLogger(__name__)


def _build_key_map() -> dict:
    """US keyboard layout: evdev key code -> (normal_char, shifted_char)."""
    from evdev import ecodes as e
    return {
        e.KEY_GRAVE: ('`', '~'),
        e.KEY_1: ('1', '!'),  e.KEY_2: ('2', '@'),  e.KEY_3: ('3', '#'),
        e.KEY_4: ('4', '$'),  e.KEY_5: ('5', '%'),  e.KEY_6: ('6', '^'),
        e.KEY_7: ('7', '&'),  e.KEY_8: ('8', '*'),  e.KEY_9: ('9', '('),
        e.KEY_0: ('0', ')'),
        e.KEY_MINUS: ('-', '_'),      e.KEY_EQUAL: ('=', '+'),
        e.KEY_Q: ('q', 'Q'),  e.KEY_W: ('w', 'W'),  e.KEY_E: ('e', 'E'),
        e.KEY_R: ('r', 'R'),  e.KEY_T: ('t', 'T'),  e.KEY_Y: ('y', 'Y'),
        e.KEY_U: ('u', 'U'),  e.KEY_I: ('i', 'I'),  e.KEY_O: ('o', 'O'),
        e.KEY_P: ('p', 'P'),
        e.KEY_LEFTBRACE: ('[', '{'),  e.KEY_RIGHTBRACE: (']', '}'),
        e.KEY_A: ('a', 'A'),  e.KEY_S: ('s', 'S'),  e.KEY_D: ('d', 'D'),
        e.KEY_F: ('f', 'F'),  e.KEY_G: ('g', 'G'),  e.KEY_H: ('h', 'H'),
        e.KEY_J: ('j', 'J'),  e.KEY_K: ('k', 'K'),  e.KEY_L: ('l', 'L'),
        e.KEY_SEMICOLON: (';', ':'),  e.KEY_APOSTROPHE: ("'", '"'),
        e.KEY_BACKSLASH: ('\\', '|'),   # Shift+backslash = pipe '|'
        e.KEY_Z: ('z', 'Z'),  e.KEY_X: ('x', 'X'),  e.KEY_C: ('c', 'C'),
        e.KEY_V: ('v', 'V'),  e.KEY_B: ('b', 'B'),  e.KEY_N: ('n', 'N'),
        e.KEY_M: ('m', 'M'),
        e.KEY_COMMA: (',', '<'),  e.KEY_DOT: ('.', '>'),  e.KEY_SLASH: ('/', '?'),
        e.KEY_SPACE: (' ', ' '),
        e.KEY_TAB: ('\t', '\t'),
    }


class HidReader(BaseReader):
    """
    Reads from a USB HID keyboard-emulating reader via Linux evdev.
    Grabs the device exclusively so scans do not type into other applications.

    Requires the running user to be in the 'input' group on Linux:
        sudo usermod -aG input <user>
    Or run with elevated privileges (not recommended for production).
    """

    def __init__(
        self,
        device_path: Optional[str] = None,
        vendor_id: Optional[int] = None,
        product_id: Optional[int] = None,
    ) -> None:
        if sys.platform != 'linux':
            raise RuntimeError(
                "HidReader uses Linux evdev and cannot run on this OS. "
                "Set READER_MODE=simulate for development on macOS/Windows."
            )

        import evdev
        self._key_map = _build_key_map()

        if device_path:
            self._device = evdev.InputDevice(device_path)
        else:
            self._device = _find_hid_device(evdev, vendor_id, product_id)

        self._device.grab()
        logger.info("Grabbed HID device: %s  name=%s", self._device.path, self._device.name)

    def read_lines(self) -> Iterator[str]:
        from evdev import categorize, ecodes, KeyEvent

        current: list[str] = []
        shift_held = False

        for event in self._device.read_loop():
            if event.type != ecodes.EV_KEY:
                continue

            key_event = categorize(event)

            if key_event.scancode in (ecodes.KEY_LEFTSHIFT, ecodes.KEY_RIGHTSHIFT):
                shift_held = key_event.keystate != KeyEvent.key_up
                continue

            if key_event.keystate not in (KeyEvent.key_down, KeyEvent.key_hold):
                continue

            if key_event.scancode == ecodes.KEY_ENTER:
                if current:
                    yield ''.join(current)
                    current.clear()
                continue

            pair = self._key_map.get(key_event.scancode)
            if pair:
                current.append(pair[1] if shift_held else pair[0])

    def close(self) -> None:
        try:
            self._device.ungrab()
            self._device.close()
            logger.info("HID device released")
        except Exception:
            pass


def _find_hid_device(evdev_module, vendor_id: Optional[int], product_id: Optional[int]):
    from evdev import InputDevice, ecodes

    candidates = []
    for path in evdev_module.list_devices():
        try:
            dev = InputDevice(path)
        except (OSError, PermissionError):
            continue

        caps = dev.capabilities()
        if ecodes.EV_KEY not in caps:
            continue

        # If VID+PID are specified, match exactly and return immediately.
        if vendor_id is not None and product_id is not None:
            if dev.info.vendor == vendor_id and dev.info.product == product_id:
                return dev
            continue

        # Heuristic: device that has digit keys and Enter is likely a reader.
        keys = caps.get(ecodes.EV_KEY, [])
        if ecodes.KEY_ENTER in keys and ecodes.KEY_1 in keys:
            candidates.append(dev)

    if not candidates:
        raise RuntimeError(
            "No HID keyboard device found. "
            "Options:\n"
            "  - Set READER_HID_DEVICE=/dev/input/eventX (run `evtest` to find the right one)\n"
            "  - Set READER_HID_VID=0xXXXX and READER_HID_PID=0xXXXX\n"
            "  - Check group permissions: sudo usermod -aG input $USER"
        )

    if len(candidates) > 1:
        logger.warning(
            "%d HID keyboard devices found; using first: %s. "
            "Set READER_HID_DEVICE or READER_HID_VID/PID to be explicit.",
            len(candidates), candidates[0].path,
        )

    return candidates[0]
