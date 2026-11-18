"""Three-button Bluetooth LE foot-pedal simulator for the WING Monitor app."""

from __future__ import annotations

import asyncio
import queue
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from bleak import BleakClient, BleakScanner

from adb_tools import find_adb

SERVICE_UUID = "8d53dc1d-1db7-4cd3-868b-8a527460aa84"
COMMAND_UUID = "8d53dc1d-1db7-4cd3-868b-8a527460aa85"
PEDAL_ACTION = "cz.svatjir.wingmonitor.PEDAL"
PEDAL_RECEIVER = "cz.svatjir.wingmonitor/.PedalBroadcastReceiver"
ROOT = Path(__file__).resolve().parent

LEFT = 1
CENTER = 2
RIGHT = 3

BACKGROUND = "#101319"
SURFACE = "#1c2129"
FOREGROUND = "#f2f5f8"
ACCENT = "#62d6a3"


class PedalSimulator:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("WING Monitor · Pedálový simulátor")
        self.root.configure(bg=BACKGROUND)
        self.root.geometry("800x440")
        self.root.minsize(650, 380)

        self.loop = asyncio.new_event_loop()
        self.loop_thread = threading.Thread(target=self._run_loop, daemon=True)
        self.loop_thread.start()
        self.client: BleakClient | None = None
        self.is_connecting = False
        self.mode: str | None = None
        self.adb_path: str | None = None
        self.adb_target: str | None = None
        self.input_simulator_process: subprocess.Popen | None = None
        self.buttons_down: set[int] = set()
        # ADB pedal edges must reach the app in order, so one worker sends them sequentially.
        self.adb_queue: queue.Queue[list[str]] = queue.Queue()
        threading.Thread(target=self._adb_worker, daemon=True).start()

        heading = tk.Label(
            root,
            text="TŘÍTLAČÍTKOVÝ PEDÁL",
            bg=BACKGROUND,
            fg=FOREGROUND,
            font=("Segoe UI", 18, "bold"),
        )
        heading.pack(pady=(18, 4))

        self.status = tk.Label(
            root,
            text="Emulátor: automatické ADB · skutečný telefon: BLE režim",
            bg=BACKGROUND,
            fg="#ffbe5c",
            font=("Segoe UI", 10),
        )
        self.status.pack(pady=(0, 10))

        tk.Label(
            root, text="ADB = emulátor · BLE = skutečný telefon",
            bg=BACKGROUND, fg="#a8b2bf", font=("Segoe UI", 9),
        ).pack(pady=(0, 8))

        connect_row = tk.Frame(root, bg=BACKGROUND)
        connect_row.pack(pady=(0, 16))
        self.connect_button = tk.Button(
            connect_row, text="BLE K TELEFONU", command=self.connect,
            bg=ACCENT, fg=BACKGROUND, activebackground="#7ce4b5", relief="flat",
            font=("Segoe UI", 11, "bold"), padx=18, pady=9,
        )
        self.connect_button.pack(side="left", padx=5)
        self.adb_button = tk.Button(
            connect_row, text="OVLÁDAT EMULÁTOR (ADB)", command=self.connect_adb,
            bg=SURFACE, fg=FOREGROUND, activebackground="#303945", relief="flat",
            font=("Segoe UI", 11, "bold"), padx=18, pady=9,
        )
        self.adb_button.pack(side="left", padx=5)
        self.input_button = tk.Button(
            connect_row, text="SIMULÁTOR VSTUPŮ", command=self.open_input_simulator,
            bg=SURFACE, fg=ACCENT, activebackground="#303945", relief="flat",
            font=("Segoe UI", 11, "bold"), padx=18, pady=9,
        )
        self.input_button.pack(side="left", padx=5)

        row = tk.Frame(root, bg=BACKGROUND)
        row.pack(fill="both", expand=True, padx=18, pady=(0, 18))

        self._make_pedal(row, LEFT, "◀", "LEVÁ", "Předchozí / tišeji").pack(
            side="left", fill="both", expand=True, padx=(0, 7)
        )
        self._make_pedal(row, CENTER, "●", "STŘED", "Vybrat / zpět\nPodržet: mute").pack(
            side="left", fill="both", expand=True, padx=7
        )
        self._make_pedal(row, RIGHT, "▶", "PRAVÁ", "Další / hlasitěji").pack(
            side="left", fill="both", expand=True, padx=(7, 0)
        )

        self.root.protocol("WM_DELETE_WINDOW", self.close)
        # Prefer the running emulator automatically; BLE remains available for a real phone.
        self.root.after(500, self.connect_adb)

    def _make_pedal(self, parent: tk.Widget, button_id: int, icon: str,
                    title: str, hint: str) -> tk.Button:
        button = tk.Button(
            parent,
            text=f"{icon}\n{title}\n\n{hint}",
            bg=SURFACE,
            fg=FOREGROUND,
            activebackground="#303945",
            activeforeground=ACCENT,
            relief="flat",
            wraplength=180,
            justify="center",
            font=("Segoe UI", 13, "bold"),
            command=lambda: None,
        )
        button.bind("<ButtonPress-1>", lambda event: self.press(button_id))
        button.bind("<ButtonRelease-1>", lambda event: self.release(button_id))
        button.bind("<Leave>", lambda event: self.release(button_id))
        return button

    def _run_loop(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    def connect(self) -> None:
        if self.mode == "adb":
            self.mode = None
            self.adb_target = None
            self._connection_failed("Režim ADB odpojen")
            return
        if self.is_connecting:
            return
        if self.client and self.client.is_connected:
            asyncio.run_coroutine_threadsafe(self._disconnect(), self.loop)
            return
        self.is_connecting = True
        self.connect_button.configure(state="disabled", text="HLEDÁM TELEFON…")
        self._set_status("Hledám telefon s WING Foot Pedal BLE…", "#ffbe5c")
        asyncio.run_coroutine_threadsafe(self._connect(), self.loop)

    def connect_adb(self) -> None:
        if self.mode == "adb":
            self.mode = None
            self.adb_target = None
            self._connection_failed("Režim ADB odpojen")
            return

        adb = find_adb()
        if not Path(adb).is_file():
            self._set_status("adb.exe nebyl nalezen", "#ffbe5c")
            return

        try:
            result = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=15)
            devices = [line.split()[0] for line in result.stdout.splitlines()
                       if "\tdevice" in line]
            emulators = [device for device in devices if device.startswith("emulator-")]
            if not devices:
                self._set_status("ADB zařízení nenalezeno — počkej, až emulátor naběhne", "#ffbe5c")
                return
            self.adb_path = adb
            self.adb_target = (emulators or devices)[0]
            self.mode = "adb"
            self.adb_button.configure(text="ADB ODPOJIT")
            self.connect_button.configure(state="normal", text="PŘIPOJIT BLE K TELEFONU")
            self._set_status(f"Ovládám Android přes ADB: {self.adb_target}", ACCENT)
        except (OSError, subprocess.SubprocessError) as error:
            self._set_status(f"ADB chyba: {error}", "#ffbe5c")

    def open_input_simulator(self) -> None:
        script = ROOT / "input_simulator.py"
        if self.input_simulator_process and self.input_simulator_process.poll() is None:
            self._set_status("Simulátor vstupů už běží", ACCENT)
            return
        try:
            self.input_simulator_process = subprocess.Popen(
                [sys.executable, str(script)], cwd=str(ROOT),
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            self._set_status("Otevřen simulátor Potassium vstupů", ACCENT)
        except OSError as error:
            self._set_status(f"Simulátor vstupů nelze spustit: {error}", "#ffbe5c")

    async def _connect(self) -> None:
        try:
            device = await BleakScanner.find_device_by_filter(
                lambda candidate, advertisement: SERVICE_UUID.lower()
                in [uuid.lower() for uuid in (advertisement.service_uuids or [])],
                timeout=12.0,
            )
            if device is None:
                self._ui(lambda: self._connection_failed(
                    "Pedálová BLE služba nenalezena. Zapni BLE v aplikaci na telefonu."
                ))
                return

            client = BleakClient(device, disconnected_callback=self._disconnected)
            await client.connect()
            self.client = client
            self._ui(lambda: self._connection_ready(device.name or device.address))
        except Exception as error:  # BLE adapter/OS errors are surfaced in the UI.
            self._ui(lambda message=str(error): self._connection_failed(message))

    async def _disconnect(self) -> None:
        try:
            if self.client and self.client.is_connected:
                await self.client.disconnect()
        finally:
            self.client = None
            self._ui(lambda: self._connection_failed("Odpojeno"))

    def _disconnected(self, _client: BleakClient) -> None:
        self.client = None
        self.buttons_down.clear()
        self._ui(lambda: self._connection_failed("Bluetooth spojení bylo přerušeno"))

    def _connection_ready(self, name: str) -> None:
        self.is_connecting = False
        self.mode = "ble"
        self.connect_button.configure(state="normal", text="BLE ODPOJIT")
        self.adb_button.configure(text="OVLÁDAT EMULÁTOR (ADB)")
        self._set_status(f"Připojeno: {name}", ACCENT)

    def _connection_failed(self, reason: str) -> None:
        self.is_connecting = False
        self.mode = None
        self.connect_button.configure(state="normal", text="PŘIPOJIT BLE K TELEFONU")
        self.adb_button.configure(text="OVLÁDAT EMULÁTOR (ADB)")
        self._set_status(reason, "#ffbe5c")

    def press(self, button_id: int) -> None:
        if button_id in self.buttons_down:
            return
        self.buttons_down.add(button_id)
        if self.mode == "adb":
            self._send_adb_event(button_id, True)
        else:
            self._send(button_id, True)

    def release(self, button_id: int) -> None:
        if button_id not in self.buttons_down:
            return
        self.buttons_down.discard(button_id)
        if self.mode == "adb":
            self._send_adb_event(button_id, False)
        else:
            self._send(button_id, False)

    def _send_adb_event(self, button_id: int, pressed: bool) -> None:
        """Send the same press/release edge as the BLE pedal; the app handles hold and repeat."""
        if not self.adb_path or not self.adb_target:
            return
        self.adb_queue.put([
            self.adb_path, "-s", self.adb_target, "shell", "am", "broadcast",
            "-a", PEDAL_ACTION, "-n", PEDAL_RECEIVER,
            "--ei", "button", str(button_id), "--ei", "pressed", "1" if pressed else "0",
        ])

    def _adb_worker(self) -> None:
        while True:
            command = self.adb_queue.get()
            try:
                subprocess.run(command, capture_output=True, timeout=15, check=True)
            except (OSError, subprocess.SubprocessError) as error:
                self._ui(lambda message=str(error): self._set_status(
                    f"ADB povel selhal: {message}", "#ffbe5c"))

    def _send(self, button_id: int, pressed: bool) -> None:
        if not self.client or not self.client.is_connected:
            self._set_status("Nejprve se připoj k telefonu", "#ffbe5c")
            return
        payload = bytes((button_id, 1 if pressed else 0))
        asyncio.run_coroutine_threadsafe(self._write(payload), self.loop)

    async def _write(self, payload: bytes) -> None:
        try:
            if self.client and self.client.is_connected:
                await self.client.write_gatt_char(COMMAND_UUID, payload, response=True)
        except Exception as error:
            self._ui(lambda message=str(error): self._set_status(
                f"Chyba BLE zápisu: {message}", "#ffbe5c"))

    def _ui(self, callback) -> None:
        self.root.after(0, callback)

    def _set_status(self, text: str, color: str) -> None:
        self.status.configure(text=text, fg=color)

    def close(self) -> None:
        for button_id in tuple(self.buttons_down):
            self.release(button_id)
        if self.client and self.client.is_connected:
            asyncio.run_coroutine_threadsafe(self._disconnect(), self.loop)
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    PedalSimulator(root)
    root.mainloop()


if __name__ == "__main__":
    main()
