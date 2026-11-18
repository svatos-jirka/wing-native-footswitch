"""Print the visible texts of the current Android screen with their centre coordinates.

Handy for checking the app state from scripts without screenshots.

Usage: py -3.13 ui_text.py
"""

from __future__ import annotations

import re
import subprocess
import sys
import xml.etree.ElementTree as ET

from e2e_common import ADB, emulator_serial


def dump(serial: str) -> list[tuple[str, int, int]]:
    subprocess.run([ADB, "-s", serial, "shell", "rm", "-f", "/sdcard/ui.xml"], capture_output=True)
    result = subprocess.run([ADB, "-s", serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                            capture_output=True, text=True)
    if "ERROR" in (result.stdout + result.stderr):
        # "could not get idle state" = the screen keeps redrawing (e.g. live meters); pause the level feed.
        raise RuntimeError("uiautomator: " + (result.stdout + result.stderr).strip())
    xml = subprocess.run([ADB, "-s", serial, "exec-out", "cat", "/sdcard/ui.xml"],
                         capture_output=True, check=True).stdout.decode("utf-8", errors="replace")
    items = []
    for node in ET.fromstring(xml).iter("node"):
        text = node.get("text") or ""
        if not text.strip():
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        x = (int(m.group(1)) + int(m.group(3))) // 2 if m else -1
        y = (int(m.group(2)) + int(m.group(4))) // 2 if m else -1
        items.append((text.replace("\n", " | "), x, y))
    return items


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    serial = emulator_serial()
    if serial is None:
        raise SystemExit("No emulator found (adb devices)")
    try:
        items = dump(serial)
    except RuntimeError as error:
        raise SystemExit(str(error))
    for text, x, y in items:
        print(f"{x:5d},{y:5d}  {text}")


if __name__ == "__main__":
    main()
