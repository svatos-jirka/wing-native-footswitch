import base64
import json
import os
import socket
import struct
import time
import unittest

from mock_wing import (
    MockWing,
    NativeChannelSplitter,
    NativeCommandParser,
    WingMockServer,
    armor_0xdf,
    decode_message,
    encode_message,
    encode_meter_packet,
    encode_native_node_value,
    parameter_for,
    parse_meter_requests,
    wing_hash,
    wrap_channel_frame,
)


def _meter_request(port: int, serial: int, channels: list[int] | None) -> bytes:
    """Same bytes as WingNativeClient.encodeMeterRequestFrame / WING-Q metermonger.js."""
    payload = bytearray(b"\xd3" + port.to_bytes(2, "big") + b"\xd4" + serial.to_bytes(4, "big"))
    if channels is not None:
        payload.append(0xDC)
        for ch in channels:
            payload += bytes((0xAB, ch - 1))
        payload.append(0xDE)
    return wrap_channel_frame(bytes(payload), 3)


class MeterProtocolTests(unittest.TestCase):
    def test_parse_subscription_and_keepalive(self):
        splitter = NativeChannelSplitter()
        splitter.feed(_meter_request(1241, 5123, [1, 2, 40]) + _meter_request(1241, 5123, None))
        requests = parse_meter_requests(bytes(splitter.take_channel(3)))
        self.assertEqual(requests, [(1241, 5123, [1, 2, 40]), (1241, 5123, None)])

    def test_packet_layout(self):
        packet = encode_meter_packet(5123, [1, 2], {1: -20.0})
        self.assertEqual(packet[:4], (5123).to_bytes(4, "big"))
        self.assertEqual(len(packet), 4 + 2 * 11 * 2)
        il, ir = struct.unpack_from(">hh", packet, 4)
        self.assertEqual((il / 256, ir / 256), (-20.0, -20.0))
        self.assertEqual(struct.unpack_from(">h", packet, 4 + 22)[0] / 256, -72.0)

    def test_subscribed_client_receives_fed_levels(self):
        server = WingMockServer(bind_host="127.0.0.1", discovery_port=0, native_port=0, osc_port=0,
                                meter_feed_port=0)
        server.start_background()
        receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        receiver.bind(("127.0.0.1", 0))
        receiver.settimeout(2.0)
        ws = _ws_client_connect("127.0.0.1", server.native_port)
        try:
            _ws_client_send_binary(ws, _meter_request(receiver.getsockname()[1], 7001, [16]))
            feed = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            deadline = time.monotonic() + 2.0
            level = None
            while time.monotonic() < deadline:
                feed.sendto(json.dumps({"meters": [{"ch": 16, "dbfs": -30.0, "peak": -0.5}]}).encode(),
                            ("127.0.0.1", server.meter_feed_port))
                packet, _ = receiver.recvfrom(4096)
                level = struct.unpack_from(">h", packet, 4)[0] / 256
                if level > -1.0:
                    break
            feed.close()
            self.assertEqual(packet[:4], (7001).to_bytes(4, "big"))
            self.assertAlmostEqual(level, -0.5, places=2)
        finally:
            ws.close()
            receiver.close()
            server.close()


def _ws_client_connect(host: str, port: int) -> socket.socket:
    sock = socket.create_connection((host, port), timeout=5.0)
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    req = (
        f"GET / HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        f"Upgrade: websocket\r\n"
        f"Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        f"Sec-WebSocket-Version: 13\r\n\r\n"
    ).encode("ascii")
    sock.sendall(req)
    resp = bytearray()
    while b"\r\n\r\n" not in resp:
        resp.extend(sock.recv(1024))
    assert resp.startswith(b"HTTP/1.1 101"), resp
    return sock


def _ws_client_send_binary(sock: socket.socket, payload: bytes) -> None:
    mask = b"\x12\x34\x56\x78"
    hdr = bytearray((0x82,))
    n = len(payload)
    if n < 126:
        hdr.append(0x80 | n)
    else:
        hdr.append(0x80 | 126)
        hdr.extend(struct.pack(">H", n))
    hdr.extend(mask)
    masked = bytes(b ^ mask[i & 3] for i, b in enumerate(payload))
    sock.sendall(bytes(hdr) + masked)


def _ws_client_recv_binary(sock: socket.socket) -> bytes:
    hdr = sock.recv(2)
    length = hdr[1] & 0x7F
    if length == 126:
        length = struct.unpack(">H", sock.recv(2))[0]
    elif length == 127:
        length = struct.unpack(">Q", sock.recv(8))[0]
    buf = bytearray()
    while len(buf) < length:
        buf.extend(sock.recv(length - len(buf)))
    return bytes(buf)


class OscCodecTests(unittest.TestCase):
    def test_round_trip_float_and_string(self):
        encoded = encode_message("/ch/1/send/2/lvl", "sf", ("Send level", -8.5))
        address, tags, values = decode_message(encoded)
        self.assertEqual(address, "/ch/1/send/2/lvl")
        self.assertEqual(tags, "sf")
        self.assertEqual(values[0], "Send level")
        self.assertAlmostEqual(values[1], -8.5)


class MockMixerTests(unittest.TestCase):
    def test_query_returns_wing_style_value_reply(self):
        mixer = MockWing()
        response = mixer.handle(encode_message("/ch/1/send/2/lvl"))
        address, tags, values = decode_message(response)
        self.assertEqual(address, "/ch/1/send/2/lvl")
        self.assertEqual(tags, "sff")
        self.assertAlmostEqual(values[1], -12.0)

    def test_set_level_clamps_and_reads_back(self):
        mixer = MockWing()
        mixer.handle(encode_message("/ch/1/send/2/lvl", "f", (14.0,)))
        response = mixer.handle(encode_message("/ch/1/send/2/lvl"))
        _, _, values = decode_message(response)
        self.assertEqual(values[1], 10.0)

    def test_set_send_on(self):
        mixer = MockWing()
        mixer.handle(encode_message("/ch/2/send/3/on", "i", (0,)))
        response = mixer.handle(encode_message("/ch/2/send/3/on"))
        _, tags, values = decode_message(response)
        self.assertEqual(tags, "sii")
        self.assertEqual(values[1], 0)

    def test_rejects_out_of_range_bus(self):
        self.assertIsNone(parameter_for("/ch/1/send/17/lvl"))


class NativeProtocolTests(unittest.TestCase):
    def test_wing_hash_matches_wing_q_consolestruct(self):
        expected = {
            "$syscfg.consolename": 0xC9CB20E0,
            "$syscfg.$firmware": 0x9E69815D,
            "$syscfg.$cnsmdl": 0x985729A2,
            "$ctl.lib.$actionidx": 0xA72FB15F,
            "ch.1.send.1.lvl": 0xB46C4564,
            "ch.1.send.1.on": 0xE8EE1755,
            "ch.2.main.1.lvl": 0xBC3F3E23,
            "bus.1.fdr": 0x815B19BB,
            "bus.1.mute": 0xC16EA2D7,
        }
        for path, h in expected.items():
            self.assertEqual(wing_hash(path), h, path)

    def test_df_armor_roundtrip(self):
        raw = bytes((0xD7, 0xDF, 0xD5, 0xDF, 0x10, 0xDF))
        framed = wrap_channel_frame(raw, 1)
        splitter = NativeChannelSplitter()
        splitter.feed(framed)
        self.assertEqual(bytes(splitter.take_channel(1)), raw)

    def test_dump_contains_channel_names_and_colors(self):
        dump = b"".join(MockWing().encode_dump_chunks())
        self.assertIn(b"Vox 1", dump)
        kick_color = encode_native_node_value(wing_hash("ch.1.col"), "i", 2)
        self.assertIn(armor_0xdf(kick_color), dump)

    def test_rename_channel_updates_active_name(self):
        mixer = MockWing()
        rename = encode_native_node_value(wing_hash("ch.16.name"), "s", "Lead Vox")
        parser = NativeCommandParser()
        (cmd,) = parser.feed(rename)
        _, broadcasts = mixer.handle_native_command(cmd)
        self.assertEqual(mixer.native_nodes["ch.16.$name"].value, "Lead Vox")
        self.assertIn(b"Lead Vox", b"".join(broadcasts))

    def test_websocket_probe_set_and_broadcast(self):
        server = WingMockServer(bind_host="127.0.0.1", discovery_port=0, native_port=0, osc_port=0,
                                meter_feed_port=0)
        server.start_background()
        try:
            ws1 = _ws_client_connect("127.0.0.1", server.native_port)
            ws2 = _ws_client_connect("127.0.0.1", server.native_port)
            try:
                # 1. WING-Q $syscfg probe sequence
                _ws_client_send_binary(ws1, bytes((0xDF, 0xD1)))
                syscfg_req = bytes((0xD7,)) + struct.pack(">I", wing_hash("$syscfg")) + bytes((0xDC,))
                _ws_client_send_binary(ws1, syscfg_req)
                reply = _ws_client_recv_binary(ws1)
                self.assertIn(b"WING-MOCK", reply)
                self.assertIn(b"3.1.0", reply)

                # 2. WING-Q channel send level adjustment: ch.1.send.1.lvl = -7.0 dB
                lvl_cmd = encode_native_node_value(wing_hash("ch.1.send.1.lvl"), "f", -7.0)
                _ws_client_send_binary(ws1, wrap_channel_frame(lvl_cmd, 1))

                # Second connected client receives broadcast update
                bcast = _ws_client_recv_binary(ws2)
                splitter = NativeChannelSplitter()
                splitter.feed(bcast)
                cmds = NativeCommandParser().feed(splitter.take_channel(1))
                self.assertEqual(len(cmds), 1)
                self.assertEqual(cmds[0].node_hash, wing_hash("ch.1.send.1.lvl"))
                self.assertAlmostEqual(float(cmds[0].value), -7.0)
                self.assertEqual(server.mixer.values["/ch/1/send/1/lvl"], -7.0)

                # 3. WING-Q send on/mute toggle: ch.1.send.1.on = 0 (int16 0xd3 encoding used by WING-Q)
                on_cmd = encode_native_node_value(wing_hash("ch.1.send.1.on"), "i", 0, use_i16_for_int=True)
                _ws_client_send_binary(ws1, wrap_channel_frame(on_cmd, 1))
                _ws_client_recv_binary(ws2)
                self.assertEqual(server.mixer.values["/ch/1/send/1/on"], 0)
            finally:
                ws1.close()
                ws2.close()
        finally:
            server.close()


if __name__ == "__main__":
    unittest.main()
