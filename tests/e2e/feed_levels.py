"""Feed scripted input levels into the WING mock, like the PC input simulator does.

Usage: py -3.13 feed_levels.py <channel> <seconds>:<dbfs>[:<peak>] ...
Example (CH 16: playing, silence, playing, a 0 dBFS peak, playing):
    py -3.13 feed_levels.py 16 2:-20 3:-50 2:-20 0.4:-20:0 2:-20
"""

from __future__ import annotations

import json
import socket
import sys
import time

from e2e_common import METER_FEED


def feed(channel: int, steps: list[tuple[float, float, float]], verbose: bool = True) -> None:
    """Send {"meters":[...]} to the mock every 50 ms for each (seconds, dbfs, peak) step."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        for seconds, dbfs, peak in steps:
            if verbose:
                print(f"{time.strftime('%H:%M:%S')} CH{channel}: {dbfs} dBFS (peak {peak}) for {seconds} s",
                      flush=True)
            end = time.monotonic() + seconds
            while time.monotonic() < end:
                message = {"meters": [{"ch": channel, "dbfs": dbfs, "peak": peak}]}
                sock.sendto(json.dumps(message).encode(), METER_FEED)
                time.sleep(0.05)
    finally:
        sock.close()


def parse_step(arg: str) -> tuple[float, float, float]:
    parts = [float(p) for p in arg.split(":")]
    return parts[0], parts[1], parts[2] if len(parts) > 2 else parts[1]


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    feed(int(sys.argv[1]), [parse_step(arg) for arg in sys.argv[2:]])
