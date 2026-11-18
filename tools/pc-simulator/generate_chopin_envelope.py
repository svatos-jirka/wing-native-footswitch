"""Generate a 100 ms RMS dBFS envelope for the CC0 Chopin solo-piano sample."""

from __future__ import annotations

import json
import math
from pathlib import Path

import miniaudio
import numpy as np

ROOT = Path(__file__).resolve().parent
AUDIO = ROOT / "audio" / "chopin-nocturne.mp3"
OUTPUT = ROOT / "audio" / "chopin-meter.json"
SAMPLE_RATE = 8_000
INTERVAL_MS = 100


def main() -> None:
    decoded = miniaudio.decode_file(
        str(AUDIO), output_format=miniaudio.SampleFormat.FLOAT32,
        nchannels=1, sample_rate=SAMPLE_RATE,
    )
    samples = np.asarray(decoded.samples, dtype=np.float32)
    block_size = SAMPLE_RATE * INTERVAL_MS // 1000
    usable = samples[:(len(samples) // block_size) * block_size]
    blocks = usable.reshape(-1, block_size)
    rms = np.sqrt(np.mean(blocks * blocks, axis=1))
    levels = [round(max(-72.0, min(0.0, 20.0 * math.log10(max(float(value), 1e-6)))), 1)
              for value in rms]

    OUTPUT.write_text(json.dumps({
        "song": "Chopin — Nocturne Op. 9 No. 1",
        "license": "CC0-1.0",
        "intervalMs": INTERVAL_MS,
        "stems": {
            "piano": {
                "file": "chopin-nocturne.mp3",
                "wingChannel": 13,
                "label": "Klavír",
                "levelsDbfs": levels,
            }
        },
    }, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {len(levels)} meter frames to {OUTPUT}")


if __name__ == "__main__":
    main()
