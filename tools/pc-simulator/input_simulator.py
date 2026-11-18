"""Play the CC0 Potassium master and send aligned stem meters to WING Monitor."""

from __future__ import annotations

import base64
import json
import queue
import random
import socket
import subprocess
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import ttk

import miniaudio

from adb_tools import find_adb

ROOT = Path(__file__).resolve().parent
TRACK_DIR = ROOT / "audio" / "potassium"
ACTION = "cz.svatjir.wingmonitor.SIMULATED_METER"
# Input levels are also fed to the WING mock, which streams them to the apps like a real console.
MOCK_METER_FEED = ("127.0.0.1", 2229)
HALL_NOISE_DBFS = -50.0
POP_SECONDS = 0.4
SAMPLE_RATE = 44_100
TRACKS = {
    "potassium": {
        "title": "Potassium · CC0 multitrack",
        "master": TRACK_DIR / "master.mp3",
        "envelopes": TRACK_DIR / "meter_envelopes.json",
    },
    "chopin": {
        "title": "Chopin Nocturne · CC0 piano",
        "master": ROOT / "audio" / "chopin-nocturne.mp3",
        "envelopes": ROOT / "audio" / "chopin-meter.json",
    },
}
# WING input channel for each stem, matching the band layout in ../wing-mock/mock_wing.py.
STEM_WING_CHANNELS = {
    "kick": 1,
    "snare": 2,
    "hats": 3,
    "bass_pad": 9,
    "keys": 13,
    "piano": 13,
    "lead_vocal": 16,
}

BACKGROUND = "#101319"
SURFACE = "#1c2129"
TEXT = "#f2f5f8"
MUTED = "#a8b2bf"
ACCENT = "#62d6a3"


class InputSimulator:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("WING Monitor · Simulátor vstupů")
        self.root.configure(bg=BACKGROUND)
        self.root.geometry("780x590")
        self.root.minsize(680, 500)

        self.track_key = "potassium"
        self.track_title = ""
        self.master_file = TRACKS[self.track_key]["master"]
        self.interval_ms = 100
        self.stems: dict = {}
        self.duration_ms = 0
        self.enabled: dict[str, tk.BooleanVar] = {}
        self.values: dict[str, float] = {}
        self.bars: dict[str, ttk.Progressbar] = {}
        self.level_labels: dict[str, tk.Label] = {}
        self.pop_until: dict[str, float] = {}
        self.hall_noise = tk.BooleanVar(value=False)
        self.feed_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

        self.adb_path = find_adb()
        self.adb_target = self._find_emulator()
        self.sender_queue: queue.Queue[dict | None] = queue.Queue(maxsize=1)
        self.sender_stopping = threading.Event()
        self.sender_thread = threading.Thread(target=self._sender_loop, daemon=True)
        self.sender_thread.start()

        self.playing = False
        self.frames_played = 0
        self.audio_lock = threading.Lock()
        self.audio_device: miniaudio.PlaybackDevice | None = None
        self.master_stream = None
        self.audio_generator = None
        self.started_at = 0.0
        self.last_send_at = 0.0
        self.update_after_id: str | None = None

        heading = tk.Label(root, text="SIMULÁTOR VSTUPŮ", bg=BACKGROUND, fg=TEXT,
                           font=("Segoe UI", 18, "bold"))
        heading.pack(pady=(16, 3))
        tk.Label(root, text="Výběr skladby · samostatné CC0 stopy · ADB do emulátoru",
                 bg=BACKGROUND, fg=MUTED, font=("Segoe UI", 10)).pack(pady=(0, 5))

        self.track_combo = ttk.Combobox(root, state="readonly", width=42,
                                        values=[item["title"] for item in TRACKS.values()])
        self.track_combo.current(0)
        self.track_combo.pack(pady=(0, 9))
        self.track_combo.bind("<<ComboboxSelected>>", self._on_track_selected)

        self.status = tk.Label(root, text=self._initial_status(), bg=BACKGROUND,
                               fg=ACCENT if self.adb_target else "#ffbe5c",
                               font=("Segoe UI", 10))
        self.status.pack(pady=(0, 8))

        controls = tk.Frame(root, bg=BACKGROUND)
        controls.pack(fill="x", padx=18, pady=(0, 12))
        self.play_button = tk.Button(controls, text="▶  PŘEHRÁT POTASSIUM",
                                     command=self.toggle_playback, bg=ACCENT, fg=BACKGROUND,
                                     activebackground="#7ce4b5", relief="flat",
                                     font=("Segoe UI", 12, "bold"), padx=20, pady=10)
        self.play_button.pack(side="left")
        tk.Checkbutton(controls, text="Šum sálu (lidé se baví, −50 dBFS)", variable=self.hall_noise,
                       bg=BACKGROUND, fg=TEXT, selectcolor=SURFACE, activebackground=BACKGROUND,
                       activeforeground=TEXT, font=("Segoe UI", 10)).pack(side="left", padx=14)

        self.list_frame = tk.Frame(root, bg=BACKGROUND)
        self.list_frame.pack(fill="both", expand=True, padx=18, pady=(0, 18))
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("Input.Horizontal.TProgressbar", troughcolor="#262d36",
                        background=ACCENT, bordercolor="#262d36", lightcolor=ACCENT,
                        darkcolor=ACCENT)

        self._load_track(self.track_key)
        self._render_stem_rows()
        root.protocol("WM_DELETE_WINDOW", self.close)

    def _load_track(self, key: str) -> None:
        self.track_key = key
        track = TRACKS[key]
        self.track_title = track["title"]
        self.master_file = track["master"]
        data = json.loads(track["envelopes"].read_text(encoding="utf-8"))
        self.interval_ms = int(data["intervalMs"])
        self.stems = data["stems"]
        self.duration_ms = max(len(stem["levelsDbfs"]) for stem in self.stems.values()) * self.interval_ms
        self.enabled = {name: tk.BooleanVar(value=True) for name in self.stems}
        self.values = {name: -72.0 for name in self.stems}

    def _render_stem_rows(self) -> None:
        for child in self.list_frame.winfo_children():
            child.destroy()
        self.bars.clear()
        self.level_labels.clear()
        for key, stem in self.stems.items():
            row = tk.Frame(self.list_frame, bg=SURFACE, padx=12, pady=8)
            row.pack(fill="x", pady=4)
            check = tk.Checkbutton(row, text="Měřit · " + stem["label"], variable=self.enabled[key],
                                   bg=SURFACE, fg=TEXT, selectcolor=SURFACE,
                                   activebackground=SURFACE, activeforeground=TEXT,
                                   font=("Segoe UI", 12, "bold"), width=15, anchor="w",
                                   command=self._publish_now)
            check.pack(side="left")
            bar = ttk.Progressbar(row, style="Input.Horizontal.TProgressbar",
                                  orient="horizontal", mode="determinate", maximum=60)
            bar.pack(side="left", fill="x", expand=True, padx=10)
            label = tk.Label(row, text="−∞ dBFS", width=10, bg=SURFACE, fg=MUTED,
                             font=("Consolas", 10, "bold"))
            label.pack(side="right")
            tk.Button(row, text="LUPNOUT", command=lambda name=key: self._pop(name),
                      bg="#b7444a", fg=TEXT, activebackground="#d0565c", relief="flat",
                      font=("Segoe UI", 9, "bold"), padx=8).pack(side="right", padx=(0, 8))
            self.bars[key] = bar
            self.level_labels[key] = label

    def _pop(self, key: str) -> None:
        """Simulate an overload/pop: the input peaks at 0 dBFS for a moment."""
        self.pop_until[key] = time.monotonic() + POP_SECONDS
        self._publish_now()

    def _on_track_selected(self, _event=None) -> None:
        if self.playing:
            self.stop_playback()
        selected_title = self.track_combo.get()
        self.track_key = next(key for key, value in TRACKS.items()
                              if value["title"] == selected_title)
        self._load_track(self.track_key)
        self._render_stem_rows()
        self.play_button.configure(text="▶  PŘEHRÁT " + self.track_title.upper())
        self.status.configure(text=self._initial_status(),
                              fg=ACCENT if self.adb_target else "#ffbe5c")

    def _find_emulator(self) -> str | None:
        if not Path(self.adb_path).exists():
            return None
        try:
            result = subprocess.run([self.adb_path, "devices"], capture_output=True,
                                    text=True, timeout=10)
            devices = [line.split()[0] for line in result.stdout.splitlines()
                       if "\tdevice" in line and line.split()[0].startswith("emulator-")]
            return devices[0] if devices else None
        except (OSError, subprocess.SubprocessError):
            return None

    def _initial_status(self) -> str:
        if self.adb_target:
            return f"Úrovně jdou do simulátoru pultu a přes ADB do {self.adb_target}"
        return "Úrovně jdou do simulátoru pultu (bez emulátoru, ADB nenalezeno)"

    def toggle_playback(self) -> None:
        if self.playing:
            self.stop_playback()
        else:
            self.start_playback()

    def start_playback(self) -> None:
        # Without an emulator the levels still reach the apps through the WING mock.
        if not self.adb_target:
            self.adb_target = self._find_emulator()
        try:
            self.master_stream = miniaudio.stream_file(
                str(self.master_file), output_format=miniaudio.SampleFormat.FLOAT32,
                nchannels=2, sample_rate=SAMPLE_RATE, frames_to_read=1024,
            )
            self.frames_played = 0
            self.playing = True
            self.started_at = time.monotonic()
            self.audio_generator = self._playback_callback()
            next(self.audio_generator)
            self.audio_device = miniaudio.PlaybackDevice(
                output_format=miniaudio.SampleFormat.FLOAT32,
                nchannels=2, sample_rate=SAMPLE_RATE, app_name="WING input simulator",
            )
            self.audio_device.start(self.audio_generator)
            self.play_button.configure(text="■  ZASTAVIT SKLADBU")
            self.status.configure(text="Přehrávám master; měřáky vycházejí ze samostatných stop",
                                  fg=ACCENT)
            self._schedule_update()
        except Exception as error:
            self.playing = False
            self.status.configure(text=f"Přehrání selhalo: {error}", fg="#ffbe5c")
            self._send_snapshot(False)

    def _playback_callback(self):
        frames = yield b""
        try:
            while self.playing:
                try:
                    samples = self.master_stream.send(frames)
                except StopIteration:
                    self.playing = False
                    return
                if not samples:
                    self.playing = False
                    return
                with self.audio_lock:
                    self.frames_played += len(samples) // 2
                frames = yield samples
        finally:
            self.playing = False

    def stop_playback(self) -> None:
        if self.update_after_id:
            self.root.after_cancel(self.update_after_id)
            self.update_after_id = None
        self.playing = False
        if self.audio_device:
            try:
                self.audio_device.stop()
                self.audio_device.close()
            except Exception:
                pass
        self.audio_device = None
        self.audio_generator = None
        self.master_stream = None
        for key in self.values:
            self.values[key] = -72.0
            self.bars[key]["value"] = 0
            self.level_labels[key].configure(text="−∞ dBFS")
        self.play_button.configure(text="▶  PŘEHRÁT " + self.track_title.upper())
        self.status.configure(text="Zastaveno · vypínám měřáky v aplikaci", fg=MUTED)
        self._send_snapshot(False)

    def _schedule_update(self) -> None:
        self._update_meters()
        if self.playing:
            self.update_after_id = self.root.after(100, self._schedule_update)
        elif self.audio_device:
            self.stop_playback()

    def _update_meters(self) -> None:
        with self.audio_lock:
            position_ms = int(self.frames_played * 1000 / SAMPLE_RATE)
        if position_ms >= self.duration_ms:
            self.stop_playback()
            return
        sample_index = position_ms // self.interval_ms
        for key, stem in self.stems.items():
            levels = stem["levelsDbfs"]
            value = float(levels[sample_index]) if sample_index < len(levels) else -72.0
            if not self.enabled[key].get():
                value = -72.0
            self.values[key] = value
            self.bars[key]["value"] = max(0.0, min(60.0, value + 60.0))
            self.level_labels[key].configure(
                text=f"{value:.0f} dBFS" if value > -60 else "−∞ dBFS",
                fg=ACCENT if value > -45 else MUTED,
            )
        now = time.monotonic()
        meters = self._current_meters()
        # The mock console streams levels to the apps every 50 ms; feed it at the envelope rate.
        self._feed_mock(meters)
        if now - self.last_send_at >= 0.18:
            self._send_snapshot(self.playing, meters)
            self.last_send_at = now

    def _current_meters(self) -> list[dict]:
        meters = []
        now = time.monotonic()
        for key, stem in self.stems.items():
            wing_channel = stem.get("wingChannel") or STEM_WING_CHANNELS.get(key)
            if not wing_channel:
                continue
            value = self.values.get(key, -72.0)
            if self.hall_noise.get():
                value = max(value, HALL_NOISE_DBFS + random.uniform(-3.0, 3.0))
            popping = self.pop_until.get(key, 0.0) > now
            meters.append({
                "ch": wing_channel,
                "dbfs": value,
                "peak": 0.0 if popping else value,
            })
        return meters

    def _publish_now(self) -> None:
        meters = self._current_meters()
        self._send_snapshot(self.playing, meters)
        self._feed_mock(meters if self.playing else [])

    def _feed_mock(self, meters: list[dict]) -> None:
        try:
            self.feed_socket.sendto(json.dumps({"meters": meters}).encode("utf-8"), MOCK_METER_FEED)
        except OSError:
            pass

    def _send_snapshot(self, playing: bool, meters: list[dict] | None = None) -> None:
        if not self.adb_target:
            return
        message = json.dumps({"playing": playing, "song": self.track_title,
                              "meters": meters or []}, separators=(",", ":"))
        token = base64.urlsafe_b64encode(message.encode("utf-8")).decode("ascii").rstrip("=")
        try:
            self.sender_queue.put_nowait({"token": token})
        except queue.Full:
            try:
                self.sender_queue.get_nowait()
            except queue.Empty:
                pass
            try:
                self.sender_queue.put_nowait({"token": token})
            except queue.Full:
                pass

    def _sender_loop(self) -> None:
        while not self.sender_stopping.is_set():
            try:
                item = self.sender_queue.get(timeout=0.25)
            except queue.Empty:
                continue
            if item is None:
                return
            command = [self.adb_path, "-s", self.adb_target, "shell", "am", "broadcast",
                       "-n", "cz.svatjir.wingmonitor/.InputMeterBroadcastReceiver",
                       "-a", ACTION, "--es", "meters_b64", item["token"]]
            try:
                result = subprocess.run(command, capture_output=True, text=True, timeout=8)
                if result.returncode != 0:
                    self.root.after(0, lambda text=result.stderr.strip(): self.status.configure(
                        text=f"ADB broadcast chyba: {text[:80]}", fg="#ffbe5c"))
            except (OSError, subprocess.SubprocessError) as error:
                self.root.after(0, lambda message=str(error): self.status.configure(
                    text=f"ADB timeout/chyba: {message}", fg="#ffbe5c"))

    def close(self) -> None:
        if self.playing:
            self.stop_playback()
        self._send_snapshot(False)
        self.sender_stopping.set()
        try:
            self.sender_queue.put_nowait(None)
        except queue.Full:
            pass
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    InputSimulator(root)
    root.mainloop()


if __name__ == "__main__":
    main()
