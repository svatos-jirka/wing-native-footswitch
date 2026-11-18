"""Precompute 100 ms RMS dBFS meter envelopes from the Potassium CC0 stems."""

from __future__ import annotations

import json
import math
from pathlib import Path

import av
import miniaudio
import numpy as np

ROOT = Path(__file__).resolve().parent
TRACK = ROOT / "audio" / "potassium"
OUTPUT = TRACK / "meter_envelopes.json"
CHOPIN_AUDIO = ROOT / "audio" / "chopin-nocturne.mp3"
CHOPIN_OUTPUT = ROOT / "audio" / "chopin-meter.json"
SAMPLE_RATE = 8_000
INTERVAL_MS = 100
SAMPLES_PER_INTERVAL = SAMPLE_RATE * INTERVAL_MS // 1000

# Map the CC0 stems to the app's input groups/channels: drums=0, vocals=1,
# strings=3, keys=4. Channels with no demo stem remain silent.
STEMS = {
    "kick": {"file": "kick.m4a", "wingChannel": 1, "label": "Kick"},
    "snare": {"file": "snare.m4a", "wingChannel": 2, "label": "Snare"},
    "hats": {"file": "hats.m4a", "wingChannel": 3, "label": "Hi-hat"},
    "lead_vocal": {"file": "lead-vocal.m4a", "wingChannel": 16, "label": "Zpěv"},
    "bass_pad": {"file": "bass-pad.m4a", "wingChannel": 9, "label": "Basa"},
    "keys": {"file": "keys.m4a", "wingChannel": 13, "label": "Klavír"},
}


def stem_envelope(path: Path) -> list[float]:
    container = av.open(str(path))
    stream = container.streams.audio[0]
    resampler = av.AudioResampler(format="flt", layout="mono", rate=SAMPLE_RATE)
    pending = np.empty(0, dtype=np.float32)
    levels: list[float] = []

    def consume_frame(frame) -> None:
        nonlocal pending
        raw = bytes(frame.planes[0])
        samples = np.frombuffer(raw, dtype="<f4", count=frame.samples)
        if pending.size:
            samples = np.concatenate((pending, samples))
        full_size = (samples.size // SAMPLES_PER_INTERVAL) * SAMPLES_PER_INTERVAL
        if full_size:
            blocks = samples[:full_size].reshape(-1, SAMPLES_PER_INTERVAL)
            rms = np.sqrt(np.mean(blocks * blocks, axis=1))
            dbfs = 20.0 * np.log10(np.maximum(rms, 1e-6))
            levels.extend(np.round(np.clip(dbfs, -72.0, 0.0), 1).tolist())
        pending = samples[full_size:].copy()

    try:
        for frame in container.decode(stream):
            for output_frame in resampler.resample(frame):
                consume_frame(output_frame)
        for output_frame in resampler.resample(None):
            consume_frame(output_frame)
    finally:
        container.close()

    if pending.size:
        rms = float(np.sqrt(np.mean(pending * pending)))
        dbfs = 20.0 * math.log10(max(rms, 1e-6))
        levels.append(round(max(-72.0, min(0.0, dbfs)), 1))
    return levels


def main() -> None:
    tracks = {}
    for key, info in STEMS.items():
        path = TRACK / info["file"]
        if not path.exists():
            raise SystemExit(f"Stem missing: {path}")
        levels = stem_envelope(path)
        tracks[key] = {**info, "levelsDbfs": levels}
        print(f"{info['label']}: {len(levels)} meter frames")

    OUTPUT.write_text(json.dumps({
        "song": "Potassium",
        "source": "https://github.com/ichbinsoftware/everythingisfree/tree/main/src/4.Potassium",
        "license": "CC0-1.0",
        "sampleRate": SAMPLE_RATE,
        "intervalMs": INTERVAL_MS,
        "stems": tracks,
    }, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {OUTPUT}")

    # CC0 solo-piano recording for testing the keyboard input on its own.
    piano = miniaudio.decode_file(
        str(CHOPIN_AUDIO), output_format=miniaudio.SampleFormat.FLOAT32,
        nchannels=1, sample_rate=SAMPLE_RATE,
    )
    piano_samples = piano.samples
    piano_levels: list[float] = []
    for offset in range(0, len(piano_samples), SAMPLES_PER_INTERVAL):
        chunk = piano_samples[offset:offset + SAMPLES_PER_INTERVAL]
        if not len(chunk):
            break
        rms = float(np.sqrt(np.mean(np.asarray(chunk, dtype=np.float32) ** 2)))
        dbfs = 20.0 * math.log10(max(rms, 1e-6))
        piano_levels.append(round(max(-72.0, min(0.0, dbfs)), 1))
    CHOPIN_OUTPUT.write_text(json.dumps({
        "song": "Chopin — Nocturne Op. 9 No. 1",
        "source": "https://commons.wikimedia.org/wiki/File:NocturneOp.9No.1InBFlatMinor.mp3",
        "license": "CC0-1.0",
        "sampleRate": SAMPLE_RATE,
        "intervalMs": INTERVAL_MS,
        "stems": {
            "piano": {
                "file": "chopin-nocturne.mp3",
                "wingChannel": 13,
                "label": "Klavír",
                "levelsDbfs": piano_levels,
            }
        },
    }, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {CHOPIN_OUTPUT} ({len(piano_levels)} frames)")


if __name__ == "__main__":
    main()
