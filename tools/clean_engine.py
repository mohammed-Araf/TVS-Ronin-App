#!/usr/bin/env python3
"""
Engine sound for the opening animation, from the owner's own recording of their Ronin (not a downloaded clip).

    python3 tools/clean_engine.py "path/to/recording.m4a" [--noise 0.1:1.5] [--start 9.75] [--length 3.0]

1. Learns the steady background (the hiss/hum you hear before the engine starts) from --noise (seconds, engine off).
2. Subtracts just that from the whole clip. Gentle: never more than −20 dB anywhere; the engine is ~20 dB louder than
   the background, so the engine itself is left alone. No gate, no compressor.
3. Uses a rev: --start 9.75 in the owner's recording = idle ~2400 rpm → ~6000 → back to idle, peaking ~1.1 s in,
   together with the opening's needle sweep.
4. Repairs clipped peaks, light EQ (+3 dB thump at 75 Hz, −3 dB boxiness at 320 Hz, −3 dB rasp at 2.8 kHz),
   peak −1.5 dB with plain gain, 30 ms fade in, 0.6 s fade out.
5. Writes tools/sounds/engine_start.wav and the app's res/raw/snd_engine_start.ogg.

Length 3.0 s = the opening animation. Needs numpy, scipy and ffmpeg. (tools/make_engine.py is a synthesised fallback.)
"""
import argparse
import os
import subprocess
import tempfile
import wave

import numpy as np
from scipy.signal import istft, stft

SR = 44100
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WAV = os.path.join(ROOT, "tools", "sounds", "engine_start.wav")
OGG = os.path.join(ROOT, "android", "app", "src", "main", "res", "raw", "snd_engine_start.ogg")


def ff(args):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *args], check=True)


def read(path):
    w = wave.open(path)
    x = np.frombuffer(w.readframes(w.getnframes()), "<i2").reshape(-1, w.getnchannels()).astype(float) / 32768
    return x.mean(1)


def write(path, x, channels=1):
    with wave.open(path, "wb") as w:
        w.setnchannels(channels); w.setsampwidth(2); w.setframerate(SR)
        y = np.clip(x, -1, 1)
        if channels == 2: y = np.repeat(y[:, None], 2, 1)
        w.writeframes((y * 32767).astype("<i2").tobytes())


def subtract_background(x, noise):
    """Power subtraction of the one background profile, floor −20 dB."""
    n = 4096
    _, _, X = stft(x, SR, nperseg=n, noverlap=n * 3 // 4)
    _, _, N = stft(noise, SR, nperseg=n, noverlap=n * 3 // 4)
    prof = (np.abs(N) ** 2).mean(1, keepdims=True)
    gain = np.sqrt(np.clip(1 - prof / (np.abs(X) ** 2 + 1e-12), 0.01, 1))
    _, y = istft(X * gain, SR, nperseg=n, noverlap=n * 3 // 4)
    return y[: len(x)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("--noise", default="0.1:1.5", help="engine-off stretch to learn the background from (s:s)")
    ap.add_argument("--start", type=float, default=9.75)
    ap.add_argument("--length", type=float, default=3.0)
    a = ap.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        raw, dc, mid, eq = (os.path.join(tmp, f) for f in ("raw.wav", "declip.wav", "mid.wav", "eq.wav"))
        ff(["-i", a.input, "-ac", "1", "-ar", str(SR), raw])
        ff(["-i", raw, "-af", "adeclip=w=55:o=75:a=8:n=1000", dc])      # the phone mic clipped on the loud parts
        x = read(dc)
        n0, n1 = (float(v) for v in a.noise.split(":"))
        noise = x[int(n0 * SR):int(n1 * SR)]
        # clean a wider window than we keep, so the edges are clean too
        s0 = max(0, int((a.start - .3) * SR))
        clip = subtract_background(x[s0:int((a.start + a.length + .3) * SR)], noise)
        clip = clip[int(a.start * SR) - s0:][: int(a.length * SR)]
        write(mid, clip / (np.max(np.abs(clip)) + 1e-9) * .8)

        fade = a.length - .6
        ff(["-i", mid, "-af",
            "highpass=f=30:p=2,equalizer=f=75:t=q:w=1.1:g=3,equalizer=f=320:t=q:w=1.2:g=-3,"
            f"equalizer=f=2800:t=q:w=1.5:g=-3,afade=t=in:d=0.03,afade=t=out:st={fade}:d=0.6", eq])
        y = read(eq)
        y = y / np.max(np.abs(y)) * 10 ** (-1.5 / 20)
        write(WAV, y, channels=2)
    ff(["-i", WAV, "-c:a", "libvorbis", "-q:a", "6", OGG])
    print(f"{a.start:.2f}–{a.start + a.length:.2f} s of the recording → {WAV} and {OGG}")


if __name__ == "__main__":
    main()
