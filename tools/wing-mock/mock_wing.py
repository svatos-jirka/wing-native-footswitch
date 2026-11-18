#!/usr/bin/env python3
"""Local Behringer WING simulator supporting both WING Native (WebSocket + TCP) and OSC.

 Ports (matching a real Behringer WING console):
   - UDP 2222: discovery probe ("WING?")
   - TCP 2222: WING Native binary protocol — auto-detects both WebSocket
               (used by WING-Q and WING Monitor) and raw TCP (used by libwing/wapi)
   - UDP 2223: documented OSC subset (/ch/{1..48}/send/{1..16}/lvl|on, /bus/{1..16}/fdr|mute)

No messages are ever forwarded to physical hardware.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import socket
import struct
import threading
import time
from dataclasses import dataclass
from pathlib import Path

OSC_PORT = 2223
DISCOVERY_PORT = 2222
NATIVE_PORT = 2222
# Local-only UDP port where the PC input simulator feeds input levels (JSON) into the mock.
METER_FEED_PORT = 2229
CHANNEL_COUNT = 48
NATIVE_INPUT_CHANNELS = 40
AUX_INPUT_CHANNELS = 8
BUS_COUNT = 16

# Meter stream as used by WING-Q (metermonger.js): group 0xab = input channel, 11 int16 values each.
METER_GROUP_CHANNEL = 0xAB
METER_GROUPS_WITHOUT_INDEX = (0xA9, 0xAA)
METER_VALUES_PER_CHANNEL = 11
METER_INTERVAL_S = 0.05
METER_SUBSCRIPTION_TTL_S = 10.0
METER_FEED_TIMEOUT_S = 1.0
METER_FLOOR_DB = -72.0

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# Default stage layout for the band, with WING colours per section (CH 1..22).
# Channels above this list are left unnamed, like an unused console channel.
DEFAULT_STAGE_CHANNELS: tuple[tuple[str, int, int], ...] = (
    # (name, WING color 1..18, WING icon)
    ("Kick", 2, 100),
    ("Snare", 2, 101),
    ("HiHat", 2, 102),
    ("Tom 1", 2, 103),
    ("Tom 2", 2, 103),
    ("Floor", 2, 104),
    ("OH L", 2, 105),
    ("OH R", 2, 105),
    ("Bass DI", 9, 200),
    ("E-Gtr", 5, 202),
    ("A-Gtr", 5, 201),
    ("Gtr 2", 5, 201),
    ("Piano", 5, 500),
    ("Harmonika", 5, 501),
    ("Ukulele", 5, 203),
    ("Vox 1", 6, 300),
    ("Vox 2", 6, 300),
    ("Vox 3", 6, 300),
    ("Trumpeta", 8, 402),
    ("Pozoun", 8, 401),
    ("Tuba", 8, 400),
    ("Housle", 12, 205),
)

CHANNEL_PATH = re.compile(r"^/ch/(\d+)/send/(\d+)/(lvl|on)$")
BUS_PATH = re.compile(r"^/bus/(\d+)/(fdr|mute)$")
NATIVE_SEND_PATH = re.compile(r"^ch\.(\d+)\.send\.(\d+)\.(lvl|on)$")
NATIVE_BUS_PATH = re.compile(r"^bus\.(\d+)\.(fdr|mute|\$mute)$")
NATIVE_META_PATH = re.compile(r"^(ch|aux|bus)\.(\d+)\.(name|col|icon)$")


def wing_hash(path: str) -> int:
    """Compute the 32-bit WING native node hash for a dot-separated path."""
    n = 0
    r = 3407988414
    idx = 0
    length = len(path)
    while idx < length:
        word = 0
        packed = 0
        while packed < 4 and idx < length:
            ch = path[idx]
            idx += 1
            if "a" <= ch <= "z":
                ch = ch.upper()
            if ch != " ":
                word = ((word << 8) | (ord(ch) & 0xFF)) & 0xFFFFFFFF
                packed += 1
                n += 1
        word = (word * 3432918353) & 0xFFFFFFFF
        word = (((word << 15) | (word >> 17)) & 0xFFFFFFFF)
        word = (word * 461845907) & 0xFFFFFFFF
        r ^= word
        r = (((r << 13) | (r >> 19)) & 0xFFFFFFFF)
        r = (r * 5 + 3864292196) & 0xFFFFFFFF
    return (r ^ n) & 0xFFFFFFFF


def armor_0xdf(payload: bytes | bytearray) -> bytes:
    """Escape 0xdf bytes in a WING channel stream, matching WING-Q strinter.js."""
    out = bytearray()
    prev_df = False
    for b in payload:
        if b == 0xDF:
            prev_df = True
        else:
            if prev_df and 0xD0 <= b <= 0xDE:
                out.append(0xDE)
            prev_df = False
        out.append(b)
    if prev_df:
        out.append(0xDE)
    return bytes(out)


def wrap_channel_frame(payload: bytes, channel: int = 1) -> bytes:
    """Wrap raw channel commands in a WING native channel prefix [0xdf, 0xd0 + ch]."""
    return bytes((0xDF, 0xD0 + channel)) + armor_0xdf(payload)


def encode_native_node_value(node_hash: int, kind: str, value: float | int | str,
                             use_i16_for_int: bool = False) -> bytes:
    """Encode a single WING native node record: 0xd7 <hash32be> <typed-value>."""
    out = bytearray((0xD7,))
    out.extend(struct.pack(">I", node_hash & 0xFFFFFFFF))
    if kind == "f":
        out.append(0xD5)
        out.extend(struct.pack(">f", float(value)))
    elif kind == "i":
        ival = int(value)
        if not use_i16_for_int and 0 <= ival <= 0x3F:
            out.append(ival)
        elif -32768 <= ival <= 32767:
            out.append(0xD3)
            out.extend(struct.pack(">h", ival))
        else:
            out.append(0xD4)
            out.extend(struct.pack(">i", ival))
    elif kind == "s":
        raw = str(value).encode("utf-8", errors="replace")[:256]
        if len(raw) == 0:
            out.append(0xD0)
        elif len(raw) <= 64:
            out.append(0x80 + len(raw) - 1)
            out.extend(raw)
        else:
            out.append(0xD1)
            out.append(len(raw) - 1)
            out.extend(raw)
    else:
        raise ValueError(f"Unsupported native node kind: {kind}")
    return bytes(out)


class NativeChannelSplitter:
    """Demultiplexes a WING 0xdf-escaped byte stream into logical channels 0..15."""

    def __init__(self, initial_channel: int = -1) -> None:
        self.current_channel = initial_channel
        self.escape = False
        self.channels: list[bytearray] = [bytearray() for _ in range(16)]

    def feed(self, data: bytes) -> None:
        for b in data:
            if b == 0xDF and not self.escape:
                self.escape = True
                continue
            if self.escape and b != 0xDF:
                self.escape = False
                if b == 0xDE:
                    b = 0xDF
                elif 0xD0 <= b <= 0xDD:
                    self.current_channel = b - 0xD0
                    continue
                else:
                    if 0 <= self.current_channel < 16:
                        self.channels[self.current_channel].append(0xDF)
            if 0 <= self.current_channel < 16:
                self.channels[self.current_channel].append(b)

    def take_channel(self, ch: int) -> bytearray:
        buf = self.channels[ch]
        self.channels[ch] = bytearray()
        return buf


@dataclass
class NativeCommand:
    kind: str  # "dump" | "get" | "set"
    node_hash: int
    value: float | int | str | None = None
    value_encoding: str = ""
    raw_bytes: bytes = b""


class NativeCommandParser:
    """Stateful parser for WING native channel-1 commands."""

    def __init__(self) -> None:
        self.buf = bytearray()
        self.current_hash = 0

    def feed(self, chunk: bytes | bytearray) -> list[NativeCommand]:
        self.buf.extend(chunk)
        commands: list[NativeCommand] = []
        idx = 0
        n = len(self.buf)
        last_cmd_start = 0

        while idx < n:
            cmd = self.buf[idx]
            if cmd == 0xD7:
                if idx + 5 > n:
                    break
                last_cmd_start = idx
                self.current_hash = struct.unpack_from(">I", self.buf, idx + 1)[0]
                idx += 5
            elif cmd == 0xDA:
                last_cmd_start = idx
                self.current_hash = 0
                idx += 1
            elif cmd == 0xDC:
                raw = bytes(self.buf[last_cmd_start:idx + 1])
                if self.current_hash == 0:
                    commands.append(NativeCommand("dump", 0, raw_bytes=raw))
                else:
                    commands.append(NativeCommand("get", self.current_hash, raw_bytes=raw))
                idx += 1
                last_cmd_start = idx
            elif cmd == 0xDD:
                # Node definition query — ignore safely
                idx += 1
                last_cmd_start = idx
            elif cmd == 0xDE:
                idx += 1
                last_cmd_start = idx
            elif cmd <= 0x3F:
                raw = bytes(self.buf[last_cmd_start:idx + 1])
                commands.append(NativeCommand("set", self.current_hash, int(cmd), "u6", raw))
                idx += 1
                last_cmd_start = idx
            elif 0x40 <= cmd <= 0x7F:
                idx += 1
                last_cmd_start = idx
            elif 0x80 <= cmd <= 0xBF:
                slen = cmd - 0x80 + 1
                if idx + 1 + slen > n:
                    break
                sval = self.buf[idx + 1:idx + 1 + slen].decode("utf-8", errors="replace")
                raw = bytes(self.buf[last_cmd_start:idx + 1 + slen])
                commands.append(NativeCommand("set", self.current_hash, sval, "str", raw))
                idx += 1 + slen
                last_cmd_start = idx
            elif 0xC0 <= cmd <= 0xCF:
                slen = cmd - 0xC0 + 1
                if idx + 1 + slen > n:
                    break
                sval = self.buf[idx + 1:idx + 1 + slen].decode("utf-8", errors="replace")
                raw = bytes(self.buf[last_cmd_start:idx + 1 + slen])
                commands.append(NativeCommand("set", self.current_hash, sval, "str", raw))
                idx += 1 + slen
                last_cmd_start = idx
            elif cmd == 0xD0:
                raw = bytes(self.buf[last_cmd_start:idx + 1])
                commands.append(NativeCommand("set", self.current_hash, "", "str0", raw))
                idx += 1
                last_cmd_start = idx
            elif cmd == 0xD1:
                if idx + 2 > n:
                    break
                slen = self.buf[idx + 1] + 1
                if idx + 2 + slen > n:
                    break
                sval = self.buf[idx + 2:idx + 2 + slen].decode("utf-8", errors="replace")
                raw = bytes(self.buf[last_cmd_start:idx + 2 + slen])
                commands.append(NativeCommand("set", self.current_hash, sval, "str256", raw))
                idx += 2 + slen
                last_cmd_start = idx
            elif cmd == 0xD2:
                if idx + 3 > n:
                    break
                idx += 3
                last_cmd_start = idx
            elif cmd == 0xD3:
                if idx + 3 > n:
                    break
                ival = struct.unpack_from(">h", self.buf, idx + 1)[0]
                raw = bytes(self.buf[last_cmd_start:idx + 3])
                commands.append(NativeCommand("set", self.current_hash, ival, "i16", raw))
                idx += 3
                last_cmd_start = idx
            elif cmd == 0xD4:
                if idx + 5 > n:
                    break
                ival = struct.unpack_from(">i", self.buf, idx + 1)[0]
                raw = bytes(self.buf[last_cmd_start:idx + 5])
                commands.append(NativeCommand("set", self.current_hash, ival, "i32", raw))
                idx += 5
                last_cmd_start = idx
            elif cmd in (0xD5, 0xD6):
                if idx + 5 > n:
                    break
                fval = struct.unpack_from(">f", self.buf, idx + 1)[0]
                raw = bytes(self.buf[last_cmd_start:idx + 5])
                commands.append(NativeCommand("set", self.current_hash, fval, "f32", raw))
                idx += 5
                last_cmd_start = idx
            elif cmd == 0xD8:
                idx += 1
                last_cmd_start = idx
            elif cmd == 0xD9:
                if idx + 2 > n:
                    break
                idx += 2
                last_cmd_start = idx
            elif cmd == 0xDB:
                idx += 1
                last_cmd_start = idx
            else:
                idx += 1
                last_cmd_start = idx

        del self.buf[:idx]
        return commands


def _read_osc_string(packet: bytes, offset: int) -> tuple[str, int]:
    end = packet.find(b"\0", offset)
    if end < 0:
        raise ValueError("unterminated OSC string")
    value = packet[offset:end].decode("utf-8")
    return value, (end + 4) & ~3


def decode_message(packet: bytes) -> tuple[str, str, list[object]]:
    """Decode the OSC message types needed by this mock."""
    address, offset = _read_osc_string(packet, 0)
    tags, offset = _read_osc_string(packet, offset)
    if not tags.startswith(","):
        raise ValueError("missing OSC type-tag string")

    values: list[object] = []
    for tag in tags[1:]:
        if tag == "f":
            values.append(struct.unpack_from(">f", packet, offset)[0])
            offset += 4
        elif tag == "i":
            values.append(struct.unpack_from(">i", packet, offset)[0])
            offset += 4
        elif tag == "s":
            value, offset = _read_osc_string(packet, offset)
            values.append(value)
        elif tag == "T":
            values.append(True)
        elif tag == "F":
            values.append(False)
        else:
            raise ValueError(f"unsupported OSC type tag: {tag}")
    return address, tags[1:], values


def encode_message(address: str, tags: str = "", values: tuple[object, ...] = ()) -> bytes:
    """Encode a small OSC message using 4-byte padded strings."""
    def osc_string(value: str) -> bytes:
        raw = value.encode("utf-8") + b"\0"
        return raw + b"\0" * ((-len(raw)) % 4)

    packet = bytearray(osc_string(address))
    packet.extend(osc_string("," + tags))
    for tag, value in zip(tags, values):
        if tag == "f":
            packet.extend(struct.pack(">f", float(value)))
        elif tag == "i":
            packet.extend(struct.pack(">i", int(value)))
        elif tag == "s":
            packet.extend(osc_string(str(value)))
        elif tag in "TF":
            continue
        else:
            raise ValueError(f"unsupported OSC type tag: {tag}")
    return bytes(packet)


@dataclass(frozen=True)
class Parameter:
    key: str
    label: str
    kind: str
    minimum: float | int


def parameter_for(address: str) -> Parameter | None:
    channel_match = CHANNEL_PATH.fullmatch(address)
    if channel_match:
        channel, bus, suffix = channel_match.groups()
        channel_number, bus_number = int(channel), int(bus)
        if not (1 <= channel_number <= CHANNEL_COUNT and 1 <= bus_number <= BUS_COUNT):
            return None
        if suffix == "lvl":
            return Parameter(address, f"CH {channel_number:02d} Send {bus_number:02d} Level", "f", -144.0)
        return Parameter(address, f"CH {channel_number:02d} Send {bus_number:02d} On", "i", 0)

    bus_match = BUS_PATH.fullmatch(address)
    if bus_match:
        bus_number, suffix = bus_match.groups()
        bus_number = int(bus_number)
        if not 1 <= bus_number <= BUS_COUNT:
            return None
        if suffix == "fdr":
            return Parameter(address, f"BUS {bus_number:02d} Fader", "f", -144.0)
        return Parameter(address, f"BUS {bus_number:02d} Mute", "i", 0)
    return None


@dataclass
class NativeNode:
    path: str
    node_hash: int
    kind: str  # "f" | "i" | "s"
    value: float | int | str


class MockWing:
    def __init__(self, console_name: str = "WING-MOCK", firmware: str = "3.1.0",
                 model: str = "wing-bk", log_file: Path | None = None) -> None:
        self.console_name = console_name
        self.firmware = firmware
        self.model = model
        self.log_file = log_file
        self.values: dict[str, float | int] = {}
        self.native_nodes: dict[str, NativeNode] = {}
        self.hash_to_node: dict[int, NativeNode] = {}
        self.subtree_hashes: dict[int, str] = {}
        self.lock = threading.Lock()

        for channel in range(1, CHANNEL_COUNT + 1):
            for bus in range(1, BUS_COUNT + 1):
                self.values[f"/ch/{channel}/send/{bus}/lvl"] = -12.0
                self.values[f"/ch/{channel}/send/{bus}/on"] = 1
        for bus in range(1, BUS_COUNT + 1):
            self.values[f"/bus/{bus}/fdr"] = -6.0
            self.values[f"/bus/{bus}/mute"] = 0

        self._init_native_tree()

    def _register_node(self, path: str, kind: str, value: float | int | str) -> None:
        h = wing_hash(path)
        node = NativeNode(path=path, node_hash=h, kind=kind, value=value)
        self.native_nodes[path] = node
        self.hash_to_node[h] = node

    def _register_subtree(self, prefix: str) -> None:
        self.subtree_hashes[wing_hash(prefix)] = prefix

    def _init_native_tree(self) -> None:
        for prefix in ("$syscfg", "$stat", "$ctl", "ch", "aux", "bus", "cfg"):
            self._register_subtree(prefix)

        self._register_node("$syscfg.consolename", "s", self.console_name)
        self._register_node("$syscfg.$firmware", "s", self.firmware)
        self._register_node("$syscfg.$cnsmdl", "s", self.model)
        self._register_node("$syscfg.$usbspd_act", "s", "HS")
        self._register_node("$stat.usbstate", "s", "-")
        self._register_node("play.$actstate", "s", "STOP")
        self._register_node("rec.$actstate", "s", "STOP")
        self._register_node("cards.$type", "s", "WLIVE")
        for slot in (1, 2):
            self._register_node(f"cards.wlive.{slot}.$stat.sdstate", "s", "NONE")
            self._register_node(f"cards.wlive.{slot}.$stat.state", "s", "STOP")
        self._register_node("$ctl.cfg.muteovr", "i", 0)
        self._register_node("cfg.mtr.mtrsfc.in", "s", "PRE")
        self._register_node("cfg.mtr.mtrsfc.bus", "s", "POST")
        self._register_node("cfg.mtr.mtrsfc.main", "s", "POST")
        self._register_node("cfg.mtr.mtrsfc.mtx", "s", "POST")
        self._register_node("cfg.mtr.mtrsfc.dca", "s", "POST")

        for ch in range(1, NATIVE_INPUT_CHANNELS + 1):
            if ch <= len(DEFAULT_STAGE_CHANNELS):
                name, col, icon = DEFAULT_STAGE_CHANNELS[ch - 1]
            else:
                name, col, icon = "", 1, 0
            self._register_node(f"ch.{ch}.name", "s", name)
            self._register_node(f"ch.{ch}.$name", "s", name)
            self._register_node(f"ch.{ch}.col", "i", col)
            self._register_node(f"ch.{ch}.$col", "i", col)
            self._register_node(f"ch.{ch}.icon", "i", icon)
            self._register_node(f"ch.{ch}.$icon", "i", icon)
            self._register_node(f"ch.{ch}.clink", "i", 0)
            self._register_node(f"ch.{ch}.fdr", "f", -12.0)
            self._register_node(f"ch.{ch}.pan", "f", 0.0)
            self._register_node(f"ch.{ch}.mute", "i", 0)
            self._register_node(f"ch.{ch}.$mute", "i", 0)
            self._register_node(f"ch.{ch}.$muteovr", "i", 0)
            self._register_node(f"ch.{ch}.$solo", "i", 0)
            self._register_node(f"ch.{ch}.$sololed", "i", 0)
            self._register_node(f"ch.{ch}.postins.mode", "s", "FX")
            self._register_node(f"ch.{ch}.in.conn.grp", "s", "OFF")
            self._register_node(f"ch.{ch}.in.conn.in", "i", ch if ch <= 24 else 1)
            self._register_node(f"ch.{ch}.in.conn.altgrp", "s", "OFF")
            self._register_node(f"ch.{ch}.in.conn.altin", "i", 1)
            for main_idx in range(1, 5):
                self._register_node(f"ch.{ch}.main.{main_idx}.lvl", "f", 0.0)
                self._register_node(f"ch.{ch}.main.{main_idx}.on", "i", 1 if main_idx == 1 else 0)
                self._register_node(f"ch.{ch}.main.{main_idx}.pre", "i", 0)
            for bus in range(1, BUS_COUNT + 1):
                self._register_node(f"ch.{ch}.send.{bus}.lvl", "f", -12.0)
                self._register_node(f"ch.{ch}.send.{bus}.on", "i", 1)
                self._register_node(f"ch.{ch}.send.{bus}.pan", "f", 0.0)
                self._register_node(f"ch.{ch}.send.{bus}.mode", "s", "PRE")
                self._register_node(f"ch.{ch}.send.{bus}.plink", "i", 0)

        for aux in range(1, AUX_INPUT_CHANNELS + 1):
            name = f"AUX IN {aux}"
            self._register_node(f"aux.{aux}.name", "s", name)
            self._register_node(f"aux.{aux}.$name", "s", name)
            self._register_node(f"aux.{aux}.col", "i", 4)
            self._register_node(f"aux.{aux}.$col", "i", 4)
            self._register_node(f"aux.{aux}.icon", "i", 0)
            self._register_node(f"aux.{aux}.$icon", "i", 0)
            self._register_node(f"aux.{aux}.clink", "i", 0)
            self._register_node(f"aux.{aux}.fdr", "f", -144.0)
            self._register_node(f"aux.{aux}.pan", "f", 0.0)
            self._register_node(f"aux.{aux}.mute", "i", 0)
            self._register_node(f"aux.{aux}.$mute", "i", 0)
            self._register_node(f"aux.{aux}.$muteovr", "i", 0)
            self._register_node(f"aux.{aux}.in.conn.grp", "s", "OFF")
            self._register_node(f"aux.{aux}.in.conn.in", "i", aux)
            self._register_node(f"aux.{aux}.in.conn.altgrp", "s", "OFF")
            self._register_node(f"aux.{aux}.in.conn.altin", "i", 1)
            for bus in range(1, BUS_COUNT + 1):
                self._register_node(f"aux.{aux}.send.{bus}.lvl", "f", -144.0)
                self._register_node(f"aux.{aux}.send.{bus}.on", "i", 0)
                self._register_node(f"aux.{aux}.send.{bus}.pan", "f", 0.0)
                self._register_node(f"aux.{aux}.send.{bus}.mode", "s", "PRE")
                self._register_node(f"aux.{aux}.send.{bus}.plink", "i", 0)

        for bus in range(1, BUS_COUNT + 1):
            bname = f"AUX {bus}"
            self._register_node(f"bus.{bus}.name", "s", bname)
            self._register_node(f"bus.{bus}.$name", "s", bname)
            self._register_node(f"bus.{bus}.col", "i", 7)
            self._register_node(f"bus.{bus}.$col", "i", 7)
            self._register_node(f"bus.{bus}.icon", "i", 0)
            self._register_node(f"bus.{bus}.$icon", "i", 0)
            self._register_node(f"bus.{bus}.fdr", "f", -6.0)
            self._register_node(f"bus.{bus}.pan", "f", 0.0)
            self._register_node(f"bus.{bus}.wid", "f", 100.0)
            self._register_node(f"bus.{bus}.busmono", "i", 0)
            self._register_node(f"bus.{bus}.mute", "i", 0)
            self._register_node(f"bus.{bus}.$mute", "i", 0)
            self._register_node(f"bus.{bus}.$muteovr", "i", 0)
            for target_bus in range(1, BUS_COUNT + 1):
                self._register_node(f"bus.{bus}.send.{target_bus}.lvl", "f", -144.0)
                self._register_node(f"bus.{bus}.send.{target_bus}.on", "i", 0)
                self._register_node(f"bus.{bus}.send.{target_bus}.pre", "i", 1)

        # WING-Q's EOTsense sentinel — must be registered LAST so full dumps finish with it!
        self._register_node("$ctl.lib.$actionidx", "i", 1)

    def _record_event(self, event: dict[str, object]) -> None:
        if self.log_file is None:
            return
        event_with_ts = {"ts": round(time.time(), 4), **event}
        with self.log_file.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(event_with_ts, ensure_ascii=False) + "\n")

    def _sync_osc_to_native_locked(self, osc_address: str, value: float | int) -> list[NativeNode]:
        changed: list[NativeNode] = []
        m_ch = CHANNEL_PATH.fullmatch(osc_address)
        if m_ch:
            ch, bus, suffix = int(m_ch.group(1)), int(m_ch.group(2)), m_ch.group(3)
            native_path = f"ch.{ch}.send.{bus}.{suffix}"
            node = self.native_nodes.get(native_path)
            if node is not None:
                node.value = float(value) if suffix == "lvl" else int(value)
                changed.append(node)
            return changed

        m_bus = BUS_PATH.fullmatch(osc_address)
        if m_bus:
            bus, suffix = int(m_bus.group(1)), m_bus.group(2)
            native_path = f"bus.{bus}.{suffix}"
            node = self.native_nodes.get(native_path)
            if node is not None:
                node.value = float(value) if suffix == "fdr" else int(value)
                changed.append(node)
            if suffix == "mute":
                dmute = self.native_nodes.get(f"bus.{bus}.$mute")
                if dmute is not None:
                    dmute.value = int(value)
                    changed.append(dmute)
        return changed

    def _sync_native_to_osc_locked(self, node: NativeNode) -> list[NativeNode]:
        extra: list[NativeNode] = []
        m_meta = NATIVE_META_PATH.fullmatch(node.path)
        if m_meta:
            # Without a source link, the console's active name/colour/icon follows the edit.
            twin = self.native_nodes.get(f"{m_meta.group(1)}.{m_meta.group(2)}.${m_meta.group(3)}")
            if twin is not None and twin.value != node.value:
                twin.value = node.value
                extra.append(twin)
            return extra

        m_ch = NATIVE_SEND_PATH.fullmatch(node.path)
        if m_ch:
            ch, bus, suffix = int(m_ch.group(1)), int(m_ch.group(2)), m_ch.group(3)
            osc_key = f"/ch/{ch}/send/{bus}/{suffix}"
            self.values[osc_key] = float(node.value) if suffix == "lvl" else int(node.value)
            return extra

        m_bus = NATIVE_BUS_PATH.fullmatch(node.path)
        if m_bus:
            bus, suffix = int(m_bus.group(1)), m_bus.group(2)
            if suffix == "fdr":
                self.values[f"/bus/{bus}/fdr"] = float(node.value)
            else:
                ival = int(bool(node.value))
                self.values[f"/bus/{bus}/mute"] = ival
                for twin_suffix in ("mute", "$mute"):
                    twin = self.native_nodes.get(f"bus.{bus}.{twin_suffix}")
                    if twin is not None and twin is not node:
                        twin.value = ival
                        extra.append(twin)
        return extra

    def handle(self, packet: bytes, source: str = "osc") -> bytes | None:
        address, tags, args = decode_message(packet)
        parameter = parameter_for(address)
        if parameter is None:
            return None

        with self.lock:
            value = self.values[parameter.key]
            if args:
                if parameter.kind == "f" and tags[:1] == "f":
                    value = max(-144.0, min(10.0, float(args[0])))
                elif parameter.kind == "i" and tags[:1] in ("i", "T", "F"):
                    raw = args[0]
                    value = int(bool(raw))
                else:
                    return None
                self.values[parameter.key] = value
                self._sync_osc_to_native_locked(address, value)
                print(f"OSC SET [{source}] {address} = {value}", flush=True)
                self._record_event({
                    "proto": "osc",
                    "source": source,
                    "op": "set",
                    "path": address,
                    "value": round(float(value), 4) if parameter.kind == "f" else int(value),
                    "hex": packet.hex(" "),
                })

        if parameter.kind == "f":
            return encode_message(address, "sff", (parameter.label, float(value), float(parameter.minimum)))
        return encode_message(address, "sii", (parameter.label, int(value), int(parameter.minimum)))

    def encode_dump_chunks(self, chunk_nodes: int = 200) -> list[bytes]:
        with self.lock:
            nodes = list(self.native_nodes.values())
        chunks: list[bytes] = []
        buf = bytearray()
        count = 0
        for node in nodes:
            buf.extend(encode_native_node_value(node.node_hash, node.kind, node.value))
            count += 1
            if count >= chunk_nodes:
                chunks.append(wrap_channel_frame(bytes(buf), 1))
                buf.clear()
                count = 0
        if buf:
            chunks.append(wrap_channel_frame(bytes(buf), 1))
        return chunks

    def handle_native_command(
        self,
        cmd: NativeCommand,
        source: str = "native",
        wire_frame_hex: str = "",
    ) -> tuple[list[bytes], list[bytes]]:
        """Process one NativeCommand; returns (direct_replies, broadcast_updates)."""
        if cmd.kind == "dump":
            print(f"NATIVE DUMP [{source}] full tree requested ({len(self.native_nodes)} nodes)", flush=True)
            self._record_event({
                "proto": "native",
                "source": source,
                "op": "dump",
                "cmd_hex": cmd.raw_bytes.hex(" "),
            })
            return self.encode_dump_chunks(), []

        if cmd.kind == "get":
            with self.lock:
                if cmd.node_hash in self.subtree_hashes:
                    prefix = self.subtree_hashes[cmd.node_hash] + "."
                    matching = [
                        encode_native_node_value(n.node_hash, n.kind, n.value)
                        for p, n in self.native_nodes.items()
                        if p.startswith(prefix)
                    ]
                    payload = b"".join(matching)
                    return ([wrap_channel_frame(payload, 1)] if payload else []), []

                node = self.hash_to_node.get(cmd.node_hash)
                if node is not None:
                    encoded = encode_native_node_value(node.node_hash, node.kind, node.value)
                    return [wrap_channel_frame(encoded, 1)], []
            return [], []

        if cmd.kind == "set":
            with self.lock:
                node = self.hash_to_node.get(cmd.node_hash)
                if node is None:
                    print(
                        f"NATIVE SET [{source}] <unknown:{cmd.node_hash:08x}> = {cmd.value!r} "
                        f"({cmd.value_encoding}) [cmd={cmd.raw_bytes.hex(' ')}]",
                        flush=True,
                    )
                    self._record_event({
                        "proto": "native",
                        "source": source,
                        "op": "set_unknown",
                        "hash": f"{cmd.node_hash:08x}",
                        "encoding": cmd.value_encoding,
                        "value": cmd.value,
                        "cmd_hex": cmd.raw_bytes.hex(" "),
                        "wire_hex": wire_frame_hex,
                    })
                    return [], []

                if node.kind == "f":
                    new_val: float | int | str = max(-144.0, min(100.0, float(cmd.value)))  # pan/wid up to 100, lvl <= 10
                    if node.path.endswith(".lvl") or node.path.endswith(".fdr"):
                        new_val = max(-144.0, min(10.0, float(new_val)))
                elif node.kind == "i":
                    new_val = int(cmd.value)
                    if node.path.endswith(".on") or node.path.endswith(".mute"):
                        new_val = 1 if new_val else 0
                else:
                    new_val = str(cmd.value)

                node.value = new_val
                extra_nodes = self._sync_native_to_osc_locked(node)
                changed_nodes = [node, *extra_nodes]
                bcast_payload = b"".join(
                    encode_native_node_value(n.node_hash, n.kind, n.value)
                    for n in changed_nodes
                )

            val_str = f"{new_val:.2f}" if isinstance(new_val, float) else repr(new_val)
            print(
                f"NATIVE SET [{source}] {node.path} ({node.node_hash:08x}) = {val_str} "
                f"({cmd.value_encoding}) [cmd={cmd.raw_bytes.hex(' ')}]",
                flush=True,
            )
            self._record_event({
                "proto": "native",
                "source": source,
                "op": "set",
                "path": node.path,
                "hash": f"{node.node_hash:08x}",
                "encoding": cmd.value_encoding,
                "value": round(float(new_val), 4) if isinstance(new_val, float) else new_val,
                "cmd_hex": cmd.raw_bytes.hex(" "),
                "wire_hex": wire_frame_hex,
            })
            return [], [wrap_channel_frame(bcast_payload, 1)]

        return [], []


def _recv_exact(sock: socket.socket, count: int) -> bytes | None:
    buf = bytearray()
    while len(buf) < count:
        chunk = sock.recv(count - len(buf))
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)


def ws_encode_frame(payload: bytes, opcode: int = 0x2) -> bytes:
    header = bytearray((0x80 | (opcode & 0x0F),))
    length = len(payload)
    if length < 126:
        header.append(length)
    elif length < 65536:
        header.append(126)
        header.extend(struct.pack(">H", length))
    else:
        header.append(127)
        header.extend(struct.pack(">Q", length))
    return bytes(header) + payload


def ws_read_message(sock: socket.socket) -> tuple[int, bytes] | None:
    """Read a full (possibly fragmented) RFC 6455 WebSocket message."""
    fragments = bytearray()
    message_opcode: int | None = None
    while True:
        hdr = _recv_exact(sock, 2)
        if hdr is None:
            return None
        fin = (hdr[0] & 0x80) != 0
        opcode = hdr[0] & 0x0F
        masked = (hdr[1] & 0x80) != 0
        length = hdr[1] & 0x7F
        if length == 126:
            ext = _recv_exact(sock, 2)
            if ext is None:
                return None
            length = struct.unpack(">H", ext)[0]
        elif length == 127:
            ext = _recv_exact(sock, 8)
            if ext is None:
                return None
            length = struct.unpack(">Q", ext)[0]
        mask_key = _recv_exact(sock, 4) if masked else b""
        if masked and mask_key is None:
            return None
        payload = _recv_exact(sock, length) if length > 0 else b""
        if payload is None:
            return None
        if masked:
            unmasked = bytearray(length)
            for i, b in enumerate(payload):
                unmasked[i] = b ^ mask_key[i & 3]
            payload = bytes(unmasked)

        if opcode in (0x8, 0x9, 0xA):
            return opcode, payload
        if message_opcode is None:
            message_opcode = opcode
        fragments.extend(payload)
        if fin:
            return message_opcode, bytes(fragments)


def _complete_ws_handshake(sock: socket.socket, initial: bytes) -> bool:
    buf = bytearray(initial)
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(2048)
        if not chunk or len(buf) > 16384:
            return False
        buf.extend(chunk)
    header_text = buf.split(b"\r\n\r\n", 1)[0].decode("latin-1", errors="replace")
    ws_key = None
    for line in header_text.splitlines()[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            if k.strip().lower() == "sec-websocket-key":
                ws_key = v.strip()
                break
    if not ws_key:
        return False
    accept = base64.b64encode(
        hashlib.sha1((ws_key + WS_GUID).encode("ascii")).digest()
    ).decode("ascii")
    response = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n"
        "\r\n"
    ).encode("ascii")
    sock.sendall(response)
    return True


class _NativeClientSession:
    def __init__(self, sock: socket.socket, addr: tuple[str, int], is_ws: bool) -> None:
        self.sock = sock
        self.addr = addr
        self.is_ws = is_ws
        self.send_lock = threading.Lock()
        self.label = f"{'ws' if is_ws else 'tcp'}:{addr[0]}:{addr[1]}"

    def send_native(self, raw_channel_frame: bytes) -> bool:
        out = ws_encode_frame(raw_channel_frame, 0x2) if self.is_ws else raw_channel_frame
        try:
            with self.send_lock:
                self.sock.sendall(out)
            return True
        except OSError:
            return False


def parse_meter_requests(payload: bytes) -> list[tuple[int | None, int | None, list[int] | None]]:
    """Parse channel-3 meter commands: ``d3 <port16> d4 <serial32> [dc <group [index]>... de]``.

    Returns (udp_port, serial, channels) per command; channels is None for a keep-alive.
    """
    results: list[tuple[int | None, int | None, list[int] | None]] = []
    port: int | None = None
    serial: int | None = None
    i, n = 0, len(payload)
    while i < n:
        b = payload[i]
        if b == 0xD3 and i + 3 <= n:
            port = int.from_bytes(payload[i + 1:i + 3], "big")
            i += 3
        elif b == 0xD4 and i + 5 <= n:
            serial = int.from_bytes(payload[i + 1:i + 5], "big")
            i += 5
            if not (i < n and payload[i] == 0xDC):
                results.append((port, serial, None))
        elif b == 0xDC:
            i += 1
            channels: list[int] = []
            while i < n and payload[i] != 0xDE:
                group = payload[i]
                i += 1
                if group in METER_GROUPS_WITHOUT_INDEX or i >= n:
                    continue
                index = payload[i]
                i += 1
                if group == METER_GROUP_CHANNEL:
                    channels.append(index + 1)
            i += 1
            results.append((port, serial, channels))
        else:
            i += 1
    return results


def encode_meter_packet(serial: int, channels: list[int], levels: dict[int, float]) -> bytes:
    """4-byte serial, then per channel 11 signed int16 values in 1/256 dB (il, ir, ol, or, ...)."""
    out = bytearray((serial & 0xFFFFFFFF).to_bytes(4, "big"))
    for ch in channels:
        level = max(-32768, min(32767, round(levels.get(ch, METER_FLOOR_DB) * 256)))
        values = (level, level, level, level, level) + (0,) * (METER_VALUES_PER_CHANNEL - 5)
        out.extend(struct.pack(">" + "h" * METER_VALUES_PER_CHANNEL, *values))
    return bytes(out)


@dataclass
class MeterSubscription:
    host: str
    port: int
    serial: int
    channels: list[int]
    expires_at: float


class WingMockServer:
    """Runs UDP discovery (2222), TCP/WebSocket Native (2222), UDP OSC (2223) and UDP meters."""

    def __init__(
        self,
        bind_host: str = "0.0.0.0",
        advertise_ip: str = "127.0.0.1",
        discovery_port: int = DISCOVERY_PORT,
        native_port: int = NATIVE_PORT,
        osc_port: int = OSC_PORT,
        meter_feed_port: int = METER_FEED_PORT,
        mixer: MockWing | None = None,
    ) -> None:
        self.bind_host = bind_host
        self.advertise_ip = advertise_ip
        self.mixer = mixer or MockWing()
        self.running = False
        self.clients_lock = threading.Lock()
        self.clients: set[_NativeClientSession] = set()
        self.meter_lock = threading.Lock()
        self.meter_subscriptions: dict[tuple[str, int], MeterSubscription] = {}
        self.meter_levels: dict[int, float] = {}
        self.meter_feed_at = 0.0

        self.meter_send_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.meter_feed_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.meter_feed_socket.bind(("127.0.0.1", meter_feed_port))
        self.meter_feed_port = self.meter_feed_socket.getsockname()[1]

        self.osc_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.osc_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.osc_socket.bind((bind_host, osc_port))
        self.osc_port = self.osc_socket.getsockname()[1]

        self.discovery_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.discovery_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.discovery_socket.bind((bind_host, discovery_port))
        self.discovery_port = self.discovery_socket.getsockname()[1]

        self.native_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.native_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.native_socket.bind((bind_host, native_port))
        self.native_socket.listen(16)
        self.native_port = self.native_socket.getsockname()[1]

    def start_background(self) -> None:
        self.running = True
        threading.Thread(target=self._run_discovery, daemon=True).start()
        threading.Thread(target=self._run_osc, daemon=True).start()
        threading.Thread(target=self._run_native_accept, daemon=True).start()
        threading.Thread(target=self._run_meter_feed, daemon=True).start()
        threading.Thread(target=self._run_meter_sender, daemon=True).start()

    def close(self) -> None:
        self.running = False
        for s in (self.osc_socket, self.discovery_socket, self.native_socket,
                  self.meter_feed_socket, self.meter_send_socket):
            try:
                s.close()
            except OSError:
                pass
        with self.clients_lock:
            clients = list(self.clients)
            self.clients.clear()
        for c in clients:
            try:
                c.sock.close()
            except OSError:
                pass

    def _broadcast_except(self, sender: _NativeClientSession | None, frames: list[bytes]) -> None:
        if not frames:
            return
        with self.clients_lock:
            targets = [c for c in self.clients if c is not sender]
        for client in targets:
            for frame in frames:
                if not client.send_native(frame):
                    with self.clients_lock:
                        self.clients.discard(client)
                    break

    def _run_discovery(self) -> None:
        while self.running:
            try:
                packet, client = self.discovery_socket.recvfrom(1024)
            except OSError:
                break
            if packet == b"WING?":
                response = (
                    f"WING,{self.advertise_ip},{self.mixer.console_name},"
                    f"{self.mixer.model},MOCK0001,{self.mixer.firmware}"
                ).encode("utf-8")
                try:
                    self.discovery_socket.sendto(response, client)
                except OSError:
                    pass

    def _run_osc(self) -> None:
        while self.running:
            try:
                packet, client = self.osc_socket.recvfrom(4096)
            except OSError:
                break
            try:
                response = self.mixer.handle(packet, source=f"udp:{client[0]}:{client[1]}")
                if response is not None:
                    self.osc_socket.sendto(response, client)
            except (ValueError, UnicodeDecodeError, struct.error) as error:
                print(f"Ignored malformed OSC packet from {client}: {error}", flush=True)

    def _run_native_accept(self) -> None:
        while self.running:
            try:
                conn, addr = self.native_socket.accept()
            except OSError:
                break
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            threading.Thread(target=self._handle_native_client, args=(conn, addr), daemon=True).start()

    def _handle_native_client(self, conn: socket.socket, addr: tuple[str, int]) -> None:
        session: _NativeClientSession | None = None
        try:
            conn.settimeout(10.0)
            initial = conn.recv(4)
            if not initial:
                conn.close()
                return
            is_ws = initial.startswith(b"GET ")
            if is_ws:
                if not _complete_ws_handshake(conn, initial):
                    conn.close()
                    return
            conn.settimeout(None)
            session = _NativeClientSession(conn, addr, is_ws=is_ws)
            with self.clients_lock:
                self.clients.add(session)
            print(f"NATIVE CONNECT [{session.label}]", flush=True)

            splitter = NativeChannelSplitter(initial_channel=-1)
            parser = NativeCommandParser()

            if is_ws:
                while self.running:
                    msg = ws_read_message(conn)
                    if msg is None:
                        break
                    opcode, payload = msg
                    if opcode == 0x8:
                        with session.send_lock:
                            conn.sendall(ws_encode_frame(b"", 0x8))
                        break
                    if opcode == 0x9:
                        with session.send_lock:
                            conn.sendall(ws_encode_frame(payload, 0xA))
                        continue
                    if opcode not in (0x1, 0x2):
                        continue
                    self._process_native_bytes(session, splitter, parser, payload)
            else:
                self._process_native_bytes(session, splitter, parser, initial)
                while self.running:
                    chunk = conn.recv(4096)
                    if not chunk:
                        break
                    self._process_native_bytes(session, splitter, parser, chunk)
        except OSError:
            pass
        finally:
            if session is not None:
                with self.clients_lock:
                    self.clients.discard(session)
                print(f"NATIVE DISCONNECT [{session.label}]", flush=True)
            try:
                conn.close()
            except OSError:
                pass

    def _process_native_bytes(
        self,
        session: _NativeClientSession,
        splitter: NativeChannelSplitter,
        parser: NativeCommandParser,
        data: bytes,
    ) -> None:
        splitter.feed(data)
        ch1 = splitter.take_channel(1)
        ch3 = splitter.take_channel(3)
        if ch3:
            self._handle_meter_requests(session, bytes(ch3))
        if not ch1:
            return
        wire_hex = data.hex(" ")
        for cmd in parser.feed(ch1):
            replies, broadcasts = self.mixer.handle_native_command(
                cmd, source=session.label, wire_frame_hex=wire_hex
            )
            for reply in replies:
                session.send_native(reply)
            self._broadcast_except(session, broadcasts)

    def _handle_meter_requests(self, session: _NativeClientSession, payload: bytes) -> None:
        host = session.addr[0]
        now = time.monotonic()
        for port, serial, channels in parse_meter_requests(payload):
            if port is None or serial is None:
                continue
            key = (host, port)
            with self.meter_lock:
                existing = self.meter_subscriptions.get(key)
                if channels is None:
                    if existing is not None and existing.serial == serial:
                        existing.expires_at = now + METER_SUBSCRIPTION_TTL_S
                    continue
                self.meter_subscriptions[key] = MeterSubscription(
                    host, port, serial, channels, now + METER_SUBSCRIPTION_TTL_S)
            print(f"METER SUBSCRIBE [{session.label}] -> udp {host}:{port} serial {serial} "
                  f"({len(channels)} channels)", flush=True)
            self.mixer._record_event({
                "proto": "native", "source": session.label, "op": "meter_subscribe",
                "udp": f"{host}:{port}", "serial": serial, "channels": channels,
                "cmd_hex": payload.hex(" "),
            })

    def _run_meter_feed(self) -> None:
        """Input levels from the PC input simulator: {"meters":[{"ch":1,"dbfs":-20,"peak":-18}]}."""
        while self.running:
            try:
                packet, _ = self.meter_feed_socket.recvfrom(65536)
            except OSError:
                break
            try:
                message = json.loads(packet.decode("utf-8"))
                levels: dict[int, float] = {}
                for meter in message.get("meters", []):
                    ch = int(meter.get("ch", 0))
                    if 1 <= ch <= NATIVE_INPUT_CHANNELS:
                        dbfs = float(meter.get("dbfs", METER_FLOOR_DB))
                        levels[ch] = max(dbfs, float(meter.get("peak", dbfs)))
            except (ValueError, TypeError, AttributeError):
                continue
            with self.meter_lock:
                self.meter_levels = levels
                self.meter_feed_at = time.monotonic()

    def current_meter_levels(self) -> dict[int, float]:
        with self.meter_lock:
            if time.monotonic() - self.meter_feed_at > METER_FEED_TIMEOUT_S:
                return {}
            return dict(self.meter_levels)

    def _run_meter_sender(self) -> None:
        while self.running:
            time.sleep(METER_INTERVAL_S)
            now = time.monotonic()
            levels = self.current_meter_levels()
            with self.meter_lock:
                for key in [k for k, sub in self.meter_subscriptions.items() if sub.expires_at < now]:
                    del self.meter_subscriptions[key]
                subscriptions = list(self.meter_subscriptions.values())
            for sub in subscriptions:
                try:
                    self.meter_send_socket.sendto(
                        encode_meter_packet(sub.serial, sub.channels, levels), (sub.host, sub.port))
                except OSError:
                    pass


def serve(bind_host: str, advertise_ip: str = "127.0.0.1", log_file: Path | None = None) -> None:
    mixer = MockWing(log_file=log_file)
    server = WingMockServer(bind_host=bind_host, advertise_ip=advertise_ip, mixer=mixer)
    server.start_background()
    print(f"Mock WING Native (WebSocket + TCP) listening on {bind_host}:{server.native_port}", flush=True)
    print(f"Mock WING OSC (UDP) listening on {bind_host}:{server.osc_port}", flush=True)
    print(f"Discovery probe listening on {bind_host}:{server.discovery_port} (WING?)", flush=True)
    print(f"Meter feed from the input simulator on 127.0.0.1:{server.meter_feed_port} (UDP JSON)", flush=True)
    if log_file is not None:
        print(f"Recording protocol events to {log_file}", flush=True)
    print("Ctrl+C to stop. No messages are forwarded to real hardware.", flush=True)

    try:
        while True:
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("\nStopping mock.", flush=True)
    finally:
        server.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="0.0.0.0", help="local address to bind (default: all interfaces)")
    parser.add_argument("--advertise-ip", default="10.0.2.2", help="IP returned in WING? discovery replies")
    parser.add_argument("--log-file", type=Path, default=None, help="optional JSONL file for packet comparison")
    args = parser.parse_args()
    serve(args.bind, advertise_ip=args.advertise_ip, log_file=args.log_file)


if __name__ == "__main__":
    main()
