import logging
import sys
from selectors import DefaultSelector, EVENT_READ
from typing import Iterator, List, Optional

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


class _LineDecoder:
    """
    Per-device keystroke state machine.
    Each connected reader keeps its own decoder so simultaneous scans from
    different readers never get their characters interleaved: a line is only
    emitted (whole) when that device sends ENTER.
    """

    def __init__(self, key_map: dict) -> None:
        self._key_map = key_map
        self._current: List[str] = []
        self._shift_held = False

    def feed(self, key_event, ecodes, KeyEvent) -> Optional[str]:
        if key_event.scancode in (ecodes.KEY_LEFTSHIFT, ecodes.KEY_RIGHTSHIFT):
            self._shift_held = key_event.keystate != KeyEvent.key_up
            return None

        if key_event.keystate not in (KeyEvent.key_down, KeyEvent.key_hold):
            return None

        if key_event.scancode == ecodes.KEY_ENTER:
            if self._current:
                line = ''.join(self._current)
                self._current.clear()
                return line
            return None

        pair = self._key_map.get(key_event.scancode)
        if pair:
            self._current.append(pair[1] if self._shift_held else pair[0])
        return None


class HidReader(BaseReader):
    """
    Reads from one or more USB HID keyboard-emulating readers via Linux evdev.
    Grabs every matching device exclusively so scans do not type into other
    applications, and multiplexes them into a single stream of scan lines.

    Requires the running user to be in the 'input' group on Linux:
        sudo usermod -aG input <user>
    Or run with elevated privileges (not recommended for production).
    """

    def __init__(
        self,
        device_paths: Optional[List[str]] = None,
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

        if device_paths:
            self._devices = [evdev.InputDevice(path) for path in device_paths]
        else:
            self._devices = _find_hid_devices(evdev, vendor_id, product_id)

        for dev in self._devices:
            dev.grab()
            logger.info("Grabbed HID device: %s  name=%s", dev.path, dev.name)
        logger.info("Listening on %d HID device(s)", len(self._devices))

    def read_lines(self) -> Iterator[str]:
        from evdev import categorize, ecodes, KeyEvent

        selector = DefaultSelector()
        decoders = {}
        for dev in self._devices:
            selector.register(dev, EVENT_READ)
            decoders[dev.path] = _LineDecoder(self._key_map)

        while selector.get_map():
            for key, _mask in selector.select():
                device = key.fileobj
                decoder = decoders[device.path]
                try:
                    events = list(device.read())
                except OSError as exc:
                    # Device unplugged / read error: drop it and keep the rest alive.
                    logger.error("HID device %s read error: %s", device.path, exc)
                    selector.unregister(device)
                    continue

                for event in events:
                    if event.type != ecodes.EV_KEY:
                        continue
                    line = decoder.feed(categorize(event), ecodes, KeyEvent)
                    if line:
                        yield line

        logger.error("All HID devices disconnected; stopping HID reader")

    def close(self) -> None:
        for dev in getattr(self, '_devices', []):
            try:
                dev.ungrab()
                dev.close()
            except Exception:
                pass
        logger.info("HID device(s) released")


def _find_hid_devices(evdev_module, vendor_id: Optional[int], product_id: Optional[int]) -> list:
    from evdev import InputDevice, ecodes

    matches = []
    for path in evdev_module.list_devices():
        try:
            dev = InputDevice(path)
        except (OSError, PermissionError):
            continue

        caps = dev.capabilities()
        if ecodes.EV_KEY not in caps:
            continue

        # If VID+PID are specified, collect every device that matches them
        # (multiple identical readers share the same VID/PID).
        if vendor_id is not None and product_id is not None:
            if dev.info.vendor == vendor_id and dev.info.product == product_id:
                matches.append(dev)
            continue

        # Heuristic: a device that has digit keys and Enter is likely a reader.
        keys = caps.get(ecodes.EV_KEY, [])
        if ecodes.KEY_ENTER in keys and ecodes.KEY_1 in keys:
            matches.append(dev)

    if not matches:
        raise RuntimeError(
            "No HID keyboard device found. "
            "Options:\n"
            "  - Set READER_HID_DEVICE=/dev/input/eventX (run `evtest` to find the right one)\n"
            "  - Set READER_HID_VID=0xXXXX and READER_HID_PID=0xXXXX\n"
            "  - Check group permissions: sudo usermod -aG input $USER"
        )

    if vendor_id is None or product_id is None:
        logger.warning(
            "%d HID keyboard device(s) matched by heuristic: %s. "
            "This may include a real keyboard; set READER_HID_VID/PID to pin your readers.",
            len(matches), ", ".join(d.path for d in matches),
        )

    return matches
