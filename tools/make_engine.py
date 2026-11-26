#!/usr/bin/env python3
"""
Engine start for the opening animation: starter whine, the engine catching, one throttle blip, then a settling
single-cylinder idle that fades out (~3.2 s).

    python3 tools/make_engine.py      # writes tools/sounds/engine_start.wav (needs numpy + scipy)

Fallback only: the app ships the owner's own recording, cleaned by tools/clean_engine.sh (same output file).

Fully synthesised (no recordings), so it's ours to ship. Model: a 4-stroke single fires once every two turns, so
firing rate = rpm / 120. Each firing is an impulse into a few resonators (exhaust pipe body, muffler, the sharper
"bark"), plus a short noise burst. Firing times jitter a little, so the idle lopes like a real thumper.
"""
import os
import wave

import numpy as np
from scipy.signal import butter, lfilter, sosfilt

SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sounds", "engine_start.wav")
rng = np.random.default_rng(368)       # fixed seed: the same sound every build

DUR = 3.3
CATCH = .62                            # the engine fires here


def rpm_at(t):
    """Crank (starter) → catch → blip to ~4000 → settle to a ~1450 idle."""
    if t < CATCH:
        return 0
    k = t - CATCH
    if k < .22:                        # catches and climbs fast
        return 900 + (4000 - 900) * (k / .22) ** .8
    if k < .95:                        # throttle off: falls back, slight undershoot
        x = (k - .22) / .73
        return 1350 + (4000 - 1350) * np.exp(-4.2 * x)
    return 1450 + 60 * np.sin(2 * np.pi * .7 * k)   # idle, gently loping


def resonator(x, f, q):
    """Two-pole resonant filter (one body of the exhaust)."""
    w = 2 * np.pi * f / SR
    r = np.exp(-w / (2 * q))
    return lfilter([1 - r], [1, -2 * r * np.cos(w), r * r], x)


def bandnoise(n, lo, hi):
    sos = butter(2, [lo, hi], btype="band", fs=SR, output="sos")
    return sosfilt(sos, rng.standard_normal(n))


def main():
    n = int(SR * DUR)
    t = np.arange(n) / SR
    exc = np.zeros(n)                  # firing impulses
    bark = np.zeros(n)                 # noise bursts at each firing

    # --- firings ---------------------------------------------------------------------------------------------
    tt, i = CATCH, 0
    while tt < DUR - .05:
        rpm = rpm_at(tt)
        idx = int(tt * SR)
        load = np.clip((rpm - 1400) / 2600, 0, 1)            # harder firings while revving
        first = 1.6 if i < 2 else 1.0                          # the first pops are loud
        a = (.55 + .45 * load) * first * rng.uniform(.85, 1.1)
        exc[idx] += a
        m = int(.018 * SR)
        burst = bandnoise(m, 500, 2600) * np.exp(-np.arange(m) / (.0045 * SR))
        bark[idx:idx + m] += burst[: n - idx] * a * (.35 + .5 * load)
        period = 120.0 / rpm
        tt += period * rng.uniform(.93, 1.07)                 # lope
        i += 1

    def norm(v): return v / (np.max(np.abs(v)) + 1e-9)
    body = norm(resonator(exc, 85, 2.2)) + .55 * norm(resonator(exc, 170, 3.0)) + .22 * norm(resonator(exc, 330, 4.0))
    muffler = .8 * norm(resonator(exc, 55, 1.4))
    barkf = .22 * norm(resonator(bark, 700, 1.2))
    engine = body + muffler + barkf
    engine = sosfilt(butter(2, 2200, btype="low", fs=SR, output="sos"), engine)   # the muffler eats the highs

    # --- starter motor (before the catch) ---------------------------------------------------------------------
    st = np.zeros(n)
    sm = t < CATCH + .05
    ts = t[sm]
    f = 165 + 70 * np.minimum(ts / .25, 1)                     # spins up
    ph = 2 * np.cumsum(f) * np.pi / SR
    whine = np.sin(ph) + .5 * np.sin(2 * ph) + .3 * np.sin(3.02 * ph) + .2 * np.sin(5.01 * ph)
    lumps = .55 + .45 * np.sin(2 * np.pi * 7.5 * ts) ** 2      # compression strokes slow the starter
    st[sm] = whine * lumps * .10 + bandnoise(sm.sum(), 1500, 5000) * .02
    st[sm] *= np.minimum(ts / .03, 1) * np.clip((CATCH + .05 - ts) / .06, 0, 1)

    mix = engine / np.max(np.abs(engine)) + st
    # mechanical clatter that follows the engine (tappets/valve train), quiet
    clat = bandnoise(n, 2500, 7000) * .012 * (t > CATCH) * (.6 + .4 * np.sin(2 * np.pi * 11 * t) ** 2)
    mix += clat

    fade = np.clip((DUR - t) / .8, 0, 1) ** 1.5                 # last 0.8 s fades out
    mix *= fade
    mix = np.tanh(mix / np.max(np.abs(mix)) * 1.2)              # a little grit
    mix *= .7 / np.max(np.abs(mix))

    # stereo: the right channel a hair later and slightly different, so it feels wide but stays centred
    d = int(.0007 * SR)
    L = mix
    R = np.concatenate([np.zeros(d), mix[:-d]]) * .97 + bandnoise(n, 3000, 6000) * .003 * (t > CATCH)
    data = (np.stack([L, R], 1) * 32767).astype("<i2").tobytes()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with wave.open(OUT, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(data)
    print(OUT)


if __name__ == "__main__":
    main()
