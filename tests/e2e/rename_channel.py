"""Act as the sound engineer: rename a channel on the running WING mock over WING Native.

Usage: py -3.13 rename_channel.py <channel> <name>
Example: py -3.13 rename_channel.py 16 "Lead Vox"
"""

from __future__ import annotations

import sys
import time

import e2e_common  # noqa: F401  (puts tools/wing-mock on sys.path)
from mock_wing import encode_native_node_value, wing_hash, wrap_channel_frame
from test_mock_wing import _ws_client_connect, _ws_client_send_binary


def rename(channel: int, name: str, host: str = "127.0.0.1", port: int = 2222) -> None:
    sock = _ws_client_connect(host, port)
    try:
        _ws_client_send_binary(sock, wrap_channel_frame(
            encode_native_node_value(wing_hash(f"ch.{channel}.name"), "s", name), 1))
        time.sleep(0.3)
    finally:
        sock.close()


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    rename(int(sys.argv[1]), sys.argv[2])
    print(f"ch.{sys.argv[1]}.name -> {sys.argv[2]}")
