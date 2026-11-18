"""End-to-end test: WING Monitor in the Android emulator against the WING mock.

What it does:
  1. starts tools/wing-mock/mock_wing.py on 127.0.0.1 with a JSONL event log,
  2. forwards UDP 1241 (live meters) into the emulator,
  3. installs the debug APK, clears its data and injects settings: Aux 1, main instrument
     Zpěvy / Zpěv 1 on CH 16, own threshold −41 dBFS, noise gate and clip protection on,
  4. starts the app and checks in the mock log what the app sends to the "console".

Needs: a running emulator (AVD), the debug APK (android: gradlew :app:assembleDebug)
and free ports 2222/2223/2229 (no other mock running).

Run:  py -3.13 -m unittest -v test_emulator_e2e   (in tests/e2e)
"""

from __future__ import annotations

import json
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

from e2e_common import (ACTIVITY, APK, METER_UDP_PORT, MOCK, PACKAGE, adb, emulator_serial,
                        pedal)
from feed_levels import feed
from rename_channel import rename
from ui_text import dump as ui_dump

CHANNEL = 16
SEND_LVL = f"ch.{CHANNEL}.send.1.lvl"
SEND_ON = f"ch.{CHANNEL}.send.1.on"
PREFS = f"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="instrument_schema_version" value="2" />
    <boolean name="selected_bus_set" value="true" />
    <int name="selected_bus" value="0" />
    <boolean name="primary_set" value="true" />
    <int name="primary_group" value="2" />
    <int name="primary_slot" value="0" />
    <int name="primary_ch" value="0" />
    <int name="assign_zpevy.1" value="{CHANNEL}" />
    <int name="thr_inst:zpevy.1" value="-41" />
    <boolean name="gate_inst:zpevy.1" value="true" />
    <boolean name="clip_inst:zpevy.1" value="true" />
</map>
"""
RIGHT = 3


def port_open(port: int) -> bool:
    with socket.socket() as sock:
        sock.settimeout(0.5)
        return sock.connect_ex(("127.0.0.1", port)) == 0


class EmulatorEndToEndTest(unittest.TestCase):
    serial: str
    log: Path
    mock: subprocess.Popen

    @classmethod
    def setUpClass(cls) -> None:
        serial = emulator_serial()
        if serial is None:
            raise unittest.SkipTest("no running emulator (adb devices)")
        if not APK.is_file():
            raise unittest.SkipTest(f"APK missing, build it first: {APK}")
        if port_open(2222):
            raise RuntimeError("TCP 2222 is already in use - stop the other WING mock first")
        cls.serial = serial

        workdir = Path(tempfile.mkdtemp(prefix="wingmonitor-e2e-"))
        cls.log = workdir / "wing_events.jsonl"
        cls.mock_out = (workdir / "mock.out").open("w", encoding="utf-8")
        cls.mock = subprocess.Popen(
            [sys.executable, str(MOCK), "--bind", "127.0.0.1", "--log-file", str(cls.log)],
            cwd=MOCK.parent, stdout=cls.mock_out, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 15
        while not port_open(2222):
            if cls.mock.poll() is not None or time.monotonic() > deadline:
                cls._stop_mock()
                raise RuntimeError(f"WING mock did not start, see {workdir / 'mock.out'}")
            time.sleep(0.2)

        redirects = adb(serial, "emu", "redir", "list", check=False)
        if f"udp:{METER_UDP_PORT}" not in redirects:
            result = adb(serial, "emu", "redir", "add", f"udp:{METER_UDP_PORT}:{METER_UDP_PORT}",
                         check=False)
            if "KO" in result:
                cls._stop_mock()
                raise RuntimeError(f"UDP {METER_UDP_PORT} redirect failed ({result.strip()}); "
                                   "restart the emulator and run the test again")

        adb(serial, "install", "-r", str(APK), timeout=180)
        adb(serial, "shell", "pm", "clear", PACKAGE)
        prefs_file = workdir / "MainActivity.xml"
        prefs_file.write_text(PREFS, encoding="utf-8")
        adb(serial, "push", str(prefs_file), "/data/local/tmp/wingmonitor_prefs.xml")
        adb(serial, "shell", "chmod", "644", "/data/local/tmp/wingmonitor_prefs.xml")
        adb(serial, "shell", "run-as", PACKAGE, "sh", "-c",
            "'mkdir -p shared_prefs && cat /data/local/tmp/wingmonitor_prefs.xml"
            " > shared_prefs/MainActivity.xml'")
        written = adb(serial, "shell", "run-as", PACKAGE, "cat", "shared_prefs/MainActivity.xml")
        if "assign_zpevy.1" not in written:
            cls._stop_mock()
            raise RuntimeError("could not write the app settings with run-as")

        adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP", check=False)
        adb(serial, "shell", "wm", "dismiss-keyguard", check=False)
        adb(serial, "shell", "am", "start", "-n", ACTIVITY)
        print(f"\nmock log: {cls.log}", flush=True)

    @classmethod
    def tearDownClass(cls) -> None:
        cls._stop_mock()

    @classmethod
    def _stop_mock(cls) -> None:
        mock = getattr(cls, "mock", None)
        if mock is not None and mock.poll() is None:
            mock.terminate()
            try:
                mock.wait(timeout=5)
            except subprocess.TimeoutExpired:
                mock.kill()
        out = getattr(cls, "mock_out", None)
        if out is not None and not out.closed:
            out.close()

    # ------------------------------------------------------------------ log helpers
    def events(self) -> list[dict]:
        if not self.log.exists():
            return []
        with self.log.open(encoding="utf-8") as fh:
            return [json.loads(line) for line in fh if line.strip()]

    def wait_for(self, predicate, timeout: float, since: int = 0) -> dict | None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for event in self.events()[since:]:
                if predicate(event):
                    return event
            time.sleep(0.2)
        return None

    def sets(self, path: str, since: int, until: int | None = None) -> list[dict]:
        return [e for e in self.events()[since:until]
                if e.get("op") == "set" and e.get("path") == path]

    def last_level(self) -> float:
        levels = [e["value"] for e in self.events() if e.get("op") == "set" and e.get("path") == SEND_LVL]
        return levels[-1] if levels else -12.0

    # ------------------------------------------------------------------ tests (run in name order)
    def test_1_connects_and_subscribes_meters(self) -> None:
        self.assertIsNotNone(self.wait_for(lambda e: e.get("op") == "dump", 30),
                             "the app did not request the full console state")
        subscribe = self.wait_for(lambda e: e.get("op") == "meter_subscribe", 15)
        self.assertIsNotNone(subscribe, "the app did not subscribe to meters")
        self.assertEqual(len(subscribe["channels"]), 40)
        self.assertTrue(subscribe["udp"].endswith(f":{METER_UDP_PORT}"), subscribe["udp"])
        time.sleep(2)

    def test_2_pedal_right_raises_level(self) -> None:
        start = len(self.events())
        before = self.last_level()
        pedal(self.serial, RIGHT, True)
        time.sleep(0.15)
        pedal(self.serial, RIGHT, False)
        event = self.wait_for(lambda e: e.get("op") == "set" and e.get("path") == SEND_LVL, 5, start)
        self.assertIsNotNone(event, "pedal RIGHT did not change the send level")
        self.assertEqual(event["value"], before + 1.0)
        if before == -12.0:
            # -11.0 dB as float32 on ch.16.send.1.lvl, exactly as WING-Q writes it.
            self.assertEqual(event["cmd_hex"], "d7 13 08 21 40 d5 c1 30 00 00")

    def test_3_noise_gate_mutes_and_reopens(self) -> None:
        feed(CHANNEL, [(2.5, -20.0, -20.0)], verbose=False)
        silent_from = len(self.events())
        feed(CHANNEL, [(3.0, -50.0, -50.0)], verbose=False)
        playing_from = len(self.events())
        feed(CHANNEL, [(2.0, -20.0, -20.0)], verbose=False)
        time.sleep(0.5)

        muted = [e["value"] for e in self.sets(SEND_ON, silent_from, playing_from)]
        self.assertIn(0, muted, "silence below the threshold did not mute the send")
        reopened = [e["value"] for e in self.sets(SEND_ON, playing_from)]
        self.assertIn(1, reopened, "playing above the threshold did not unmute the send")

    def test_4_clip_protection_lowers_send(self) -> None:
        before = self.last_level()
        start = len(self.events())
        feed(CHANNEL, [(0.4, -20.0, 0.0), (2.5, -20.0, -20.0)], verbose=False)
        levels = [e["value"] for e in self.sets(SEND_LVL, start)]
        self.assertEqual(levels, [before - 3.0], "a 0 dBFS peak should lower the send by 3 dB once")

    def test_5_channel_rename_reaches_app(self) -> None:
        rename(CHANNEL, "Lead Vox")
        try:
            deadline = time.monotonic() + 8
            texts: list[str] = []
            while time.monotonic() < deadline:
                texts = [text for text, _x, _y in ui_dump(self.serial)]
                if any("Lead Vox" in text for text in texts):
                    break
                time.sleep(0.5)
            self.assertTrue(any("Lead Vox" in text for text in texts),
                            f"new channel name not shown, screen texts: {texts}")
        finally:
            rename(CHANNEL, "Vox 1")


if __name__ == "__main__":
    unittest.main(verbosity=2)
