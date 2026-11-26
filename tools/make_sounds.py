#!/usr/bin/env python3
"""
Ronin Dash helmet sound pack: short, soft cues instead of spoken sentences.

    python3 tools/make_sounds.py            # writes tools/sounds/*.wav
    python3 tools/make_sounds.py NAME ...   # only those sounds

Everything is synthesised here (no downloaded samples), so the pack is ours to ship under the app's licence.
Stereo matters: through an intercom, LEFT cues play in the left ear and RIGHT cues in the right ear.
The same files go into the app (res/raw) and are what the browser mock plays.
"""
import math
import os
import random
import struct
import wave

SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sounds")

# note frequencies
N = {n: 440 * 2 ** ((i - 9) / 12 + o - 4) for o in range(3, 8) for i, n0 in enumerate("C C# D D# E F F# G G# A A# B".split())
     for n in [f"{n0}{o}"]}


class Track:
    def __init__(self, seconds):
        self.L = [0.0] * int(SR * seconds)
        self.R = [0.0] * int(SR * seconds)

    def add(self, mono, at=0.0, pan=0.0, gain=1.0):
        """Equal-power pan: -1 = left ear only, +1 = right ear only."""
        a = (pan + 1) * math.pi / 4
        gl, gr = math.cos(a) * gain, math.sin(a) * gain
        s = int(at * SR)
        for i, v in enumerate(mono):
            j = s + i
            if j >= len(self.L):
                break
            self.L[j] += v * gl
            self.R[j] += v * gr
        return self

    def write(self, name, peak=0.5):
        m = max(1e-9, max(max(abs(x) for x in self.L), max(abs(x) for x in self.R)))
        k = peak / m
        # trim trailing silence
        end = len(self.L)
        while end > 1 and abs(self.L[end - 1]) * k < 1e-4 and abs(self.R[end - 1]) * k < 1e-4:
            end -= 1
        end = min(len(self.L), end + int(.01 * SR))
        os.makedirs(OUT, exist_ok=True)
        with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(b"".join(struct.pack("<hh", int(self.L[i] * k * 32767), int(self.R[i] * k * 32767)) for i in range(end)))


def env(i, n, attack, tau):
    t = i / SR
    a = min(1.0, t / attack) if attack > 0 else 1.0
    return a * math.exp(-t / tau) * (1 - (i / n) ** 8)  # last few % fade to zero: no clicks


def tone(freq, dur, partials, attack=.004, tau=None):
    """partials: (ratio, gain, decay_scale). decay_scale < 1 makes that partial die faster."""
    n = int(dur * SR)
    tau = tau or dur / 3.2
    out = [0.0] * n
    for ratio, g, ds in partials:
        w = 2 * math.pi * freq * ratio / SR
        tt = tau * ds
        for i in range(n):
            out[i] += g * math.sin(w * i) * env(i, n, attack, tt)
    return out


marimba = lambda f, d=.45: tone(f, d, [(1, 1, 1), (3.93, .22, .25), (9.2, .05, .1)], attack=.003)
bell = lambda f, d=.9: tone(f, d, [(1, 1, 1), (2, .38, .7), (3, .14, .5), (5.4, .05, .3)], attack=.002)
soft = lambda f, d=.12: tone(f, d, [(1, 1, 1), (3, .18, 1), (5, .07, 1)], attack=.008, tau=d * 2)  # soft square-ish beep


def wood(f=1900, d=.035):
    """Indicator tick: a tiny woodblock knock."""
    n = int(d * SR)
    rnd = random.Random(f)
    out = tone(f, d, [(1, 1, 1), (2.7, .45, .5)], attack=.0008, tau=.006)
    for i in range(min(n, int(.004 * SR))):
        out[i] += (rnd.random() * 2 - 1) * .25 * (1 - i / (.004 * SR))
    return out


def sweep(f0, f1, d, tau=None):
    n = int(d * SR)
    ph, out = 0.0, []
    for i in range(n):
        f = f0 * (f1 / f0) ** (i / n)
        ph += 2 * math.pi * f / SR
        out.append(math.sin(ph) * env(i, n, .003, tau or d / 2.5))
    return out


def make(only=None):
    S = {}
    # --- indicators: tick in the ear on the side you're turning (hazard: both ears)
    for side, p in (("left", -.85), ("right", .85), ("both", 0)):
        S[f"ind_tick_{side}"] = (Track(.08).add(wood(2000), pan=p), .22)
        S[f"ind_tock_{side}"] = (Track(.08).add(wood(1650), pan=p), .2)
    # indicator left on: quick tick-tock-tick-tock, then a soft "hey" (two rising notes)
    t = Track(1.0)
    for k in range(4):
        t.add(wood(2000 if k % 2 == 0 else 1650), at=k * .09)
    t.add(marimba(N["D5"]), at=.42).add(marimba(N["A5"]), at=.56)
    S["ind_forgot"] = (t, .4)

    # --- navigation: direction is WHERE you hear it
    for side, p in (("left", -.9), ("right", .9)):
        S[f"turn_soon_{side}"] = (Track(.9).add(marimba(N["E5"]), pan=p).add(marimba(N["A5"]), at=.16, pan=p), .42)
        S[f"turn_now_{side}"] = (Track(.9).add(marimba(N["A5"]), pan=p).add(marimba(N["C#6"]), at=.09, pan=p)
                                 .add(marimba(N["E6"], .6), at=.18, pan=p), .5)
    S["turn_soon_straight"] = (Track(.9).add(marimba(N["E5"])).add(marimba(N["E5"]), at=.16), .38)
    t = Track(1.1)  # roundabout: the notes go round your head, left to right
    for k, (n, p) in enumerate([("G5", -.9), ("B5", -.3), ("D6", .3), ("G6", .9)]):
        t.add(marimba(N[n]), at=k * .1, pan=p)
    S["roundabout"] = (t, .45)
    t = Track(1.1)  # U-turn: right ear, sweeps back to the left, falling
    for k, (n, p) in enumerate([("E6", .9), ("C6", .2), ("A5", -.5), ("E5", -.9)]):
        t.add(marimba(N[n]), at=k * .1, pan=p)
    S["uturn"] = (t, .45)
    t = Track(1.8)
    for k, n in enumerate(["C5", "E5", "G5", "C6"]):
        t.add(bell(N[n], 1.3), at=k * .12, gain=.8)
    S["arrived"] = (t, .42)
    S["reroute"] = (Track(.8).add(marimba(N["G5"])).add(marimba(N["G5"]), at=.2).add(marimba(N["D5"]), at=.4), .35)

    # --- riding
    S["speed"] = (Track(1.0).add(bell(N["B5"], .5)).add(bell(N["B5"], .7), at=.17), .45)
    S["fuel_low"] = (Track(1.2).add(marimba(N["G4"], .6)).add(marimba(N["C4"], .8), at=.26), .55)
    S["warning"] = (Track(1.0).add(soft(N["A5"], .14)).add(soft(N["F5"], .14), at=.18).add(soft(N["A5"], .2), at=.36), .45)
    t = Track(1.6)  # emergency (side stand while moving, crash): hard to miss, still not a screech
    for k in range(8):
        t.add(soft(1400 if k % 2 == 0 else 1050, .11), at=k * .17)
    S["urgent"] = (t, .75)
    S["service"] = (Track(1.4).add(bell(N["D5"], 1.3)), .35)
    # engine warmed up after a cold start: a calm rising chord, "you can use the revs now"
    S["engine_warm"] = (Track(1.4).add(marimba(N["A4"], .6)).add(marimba(N["C#5"], .6), at=.12).add(bell(N["E5"], 1.0), at=.24, gain=.7), .38)

    # --- phone (played when you slow down; one per missed item, max 3)
    t = Track(.6)
    for k in range(6):
        t.add(soft(1320 if k % 2 == 0 else 1660, .05), at=k * .045, gain=.8)
    S["missed_call"] = (t, .35)
    S["message"] = (Track(.5).add(sweep(520, 1350, .09)).add(bell(N["E6"], .4), at=.07, gain=.4), .35)

    # --- connection
    S["connected"] = (Track(.9).add(marimba(N["C5"])).add(marimba(N["G5"]), at=.14), .38)
    S["disconnected"] = (Track(1.0).add(marimba(N["G5"])).add(marimba(N["C5"]), at=.14).add(marimba(N["C4"], .6), at=.3), .42)

    for name, (track, peak) in S.items():
        if only and name not in only:
            continue
        track.write(name, peak)
    return sorted(n for n in S if not only or n in only)


if __name__ == "__main__":
    import sys
    names = make(set(sys.argv[1:]) or None)   # e.g. make_sounds.py engine_warm   (only that one)
    total = sum(os.path.getsize(os.path.join(OUT, n + ".wav")) for n in names)
    print(f"{len(names)} sounds, {total / 1024:.0f} KB in {OUT}")
    print(" ".join(names))
