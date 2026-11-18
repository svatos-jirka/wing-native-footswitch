"""Shared helpers for the emulator end-to-end tests."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools" / "pc-simulator"))
sys.path.insert(0, str(REPO / "tools" / "wing-mock"))

from adb_tools import find_adb  # noqa: E402

PACKAGE = "cz.svatjir.wingmonitor"
ACTIVITY = f"{PACKAGE}/.MainActivity"
PEDAL_ACTION = f"{PACKAGE}.PEDAL"
PEDAL_RECEIVER = f"{PACKAGE}/.PedalBroadcastReceiver"
APK = REPO / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
MOCK = REPO / "tools" / "wing-mock" / "mock_wing.py"
METER_FEED = ("127.0.0.1", 2229)
METER_UDP_PORT = 1241

ADB = find_adb()


def emulator_serial() -> str | None:
    """ANDROID_SERIAL if set, otherwise the first running emulator."""
    if os.environ.get("ANDROID_SERIAL"):
        return os.environ["ANDROID_SERIAL"]
    try:
        out = subprocess.run([ADB, "devices"], capture_output=True, text=True, timeout=15).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1] == "device" and parts[0].startswith("emulator-"):
            return parts[0]
    return None


def adb(serial: str, *args: str, check: bool = True, timeout: float = 60) -> str:
    result = subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stdout}{result.stderr}")
    return result.stdout + result.stderr


def pedal(serial: str, button: int, pressed: bool) -> None:
    """Same press/release edge the PC pedal simulator sends (1 = left, 2 = centre, 3 = right)."""
    adb(serial, "shell", "am", "broadcast", "-a", PEDAL_ACTION, "-n", PEDAL_RECEIVER,
        "--ei", "button", str(button), "--ei", "pressed", "1" if pressed else "0")
