#!/usr/bin/env python3
"""
Twelve EQ versions of the opening's engine rev, all aimed at the exhaust thump (the highs cut away), to pick by ear.

    python3 tools/engine_variants.py "path/to/recording.m4a"
    → tools/sounds/engine_variants/v01.wav … v12.wav (+ .ogg) and tools/engine-variants.html (listen and pick)

Every version starts from the same clean base (clipping repaired, steady background subtracted, the rev at 9.75 s,
see clean_engine.py) and is loudness-matched, so louder never wins by accident. To ship one:
    python3 tools/engine_variants.py REC --ship 7
"""
import argparse
import os
import subprocess
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clean_engine import SR, ff, read, subtract_background, write  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "sounds", "engine_variants")
PAGE = os.path.join(ROOT, "tools", "engine-variants.html")
OGG = os.path.join(ROOT, "android", "app", "src", "main", "res", "raw", "snd_engine_start.ogg")

# name, what it should sound like, ffmpeg filter chain (after the clean base). "sat" = add warm overtones of the bass
# so the thump still comes through small phone speakers that can't play the real low notes.
V = [
    ("Deep boom", "Everything above 200 Hz gone. Pure low boom, like hearing it through a wall.",
     "lowpass=f=200:p=2,lowpass=f=200:p=2,equalizer=f=70:t=q:w=1:g=4"),
    ("Thump", "Low-passed at 300 Hz with a push at 80 Hz. The classic thumper beat.",
     "lowpass=f=300:p=2,lowpass=f=300:p=2,equalizer=f=80:t=q:w=1:g=6"),
    ("Thump + a little bark", "Cut at 450 Hz, so a hint of the exhaust's edge stays on top of the thump.",
     "lowpass=f=450:p=2,lowpass=f=450:p=2,equalizer=f=85:t=q:w=1:g=5,equalizer=f=250:t=q:w=1:g=-3"),
    ("Sub", "Weight down at 55 Hz. Huge in a helmet or on headphones, quiet on a phone speaker.",
     "lowpass=f=250:p=2,lowpass=f=250:p=2,equalizer=f=55:t=q:w=.9:g=8"),
    ("Punchy", "Focus at 110 Hz: tighter, each firing a separate knock.",
     "lowpass=f=400:p=2,lowpass=f=400:p=2,equalizer=f=110:t=q:w=1.3:g=6,highpass=f=45:p=2"),
    ("Warm", "Low shelf +6 dB under 150 Hz, gentle roll-off above 600 Hz. Round, nothing harsh.",
     "bass=g=6:f=150:w=.7,lowpass=f=600:p=2,lowpass=f=600:p=2,equalizer=f=1500:t=q:w=1:g=-6"),
    ("Phone-speaker thump", "Thump plus warm overtones, so the beat is felt even on the phone's small speaker.",
     "lowpass=f=300:p=2,lowpass=f=300:p=2,equalizer=f=80:t=q:w=1:g=5|sat"),
    ("Chest hit", "Two bumps (65 + 130 Hz) and a cut in the boxy middle. Big and solid.",
     "lowpass=f=350:p=2,lowpass=f=350:p=2,equalizer=f=65:t=q:w=1:g=5,equalizer=f=130:t=q:w=1:g=4,equalizer=f=220:t=q:w=1:g=-4"),
    ("Thump, tight", "Like Thump, but the rumble below 40 Hz is removed so it doesn't boom on and on.",
     "highpass=f=40:p=2,lowpass=f=300:p=2,lowpass=f=300:p=2,equalizer=f=85:t=q:w=1.2:g=6"),
    ("Deep + overtones", "Deep boom, with warm overtones added back for small speakers.",
     "lowpass=f=200:p=2,lowpass=f=200:p=2,equalizer=f=70:t=q:w=1:g=4|sat"),
    ("Open pipe", "Cut at 700 Hz, mid cut, bass up. Closest to the raw bike, just without the hiss and rasp.",
     "lowpass=f=700:p=2,lowpass=f=700:p=2,equalizer=f=90:t=q:w=1:g=5,equalizer=f=400:t=q:w=1.2:g=-5"),
    ("Big single", "Strongest bass (+9 dB at 75 Hz), cut at 250 Hz, overtones for phones. The most dramatic one.",
     "lowpass=f=250:p=2,lowpass=f=250:p=2,equalizer=f=75:t=q:w=1:g=9|sat"),
]


def saturate(x):
    """Warm overtones of the bass (2nd/3rd harmonics), mixed in under the original."""
    y = np.tanh(x * 3.0) / np.tanh(3.0)
    return .7 * x + .3 * y


def base(rec, start=9.75, length=3.0, noise=(0.1, 1.5)):
    with tempfile.TemporaryDirectory() as tmp:
        raw, dc = os.path.join(tmp, "raw.wav"), os.path.join(tmp, "dc.wav")
        ff(["-i", rec, "-ac", "1", "-ar", str(SR), raw])
        ff(["-i", raw, "-af", "adeclip=w=55:o=75:a=8:n=1000", dc])
        x = read(dc)
    nz = x[int(noise[0] * SR):int(noise[1] * SR)]
    s0 = int((start - .3) * SR)
    c = subtract_background(x[s0:int((start + length + .3) * SR)], nz)
    return c[int(start * SR) - s0:][: int(length * SR)]


def render(clip, chain, path):
    with tempfile.TemporaryDirectory() as tmp:
        i, o = os.path.join(tmp, "i.wav"), os.path.join(tmp, "o.wav")
        write(i, clip / np.max(np.abs(clip)) * .5)
        eq, _, extra = chain.partition("|")
        ff(["-i", i, "-af", eq, o])
        y = read(o)
    if extra == "sat":
        y = saturate(y / np.max(np.abs(y)))
    n = len(y)
    fade = np.ones(n); a = int(.03 * SR); b = int(.6 * SR)
    fade[:a] = np.linspace(0, 1, a); fade[-b:] = np.linspace(1, 0, b) ** 1.5
    y = y * fade
    # loudness match: same RMS for all, then keep peaks under −1 dB
    y = y / np.sqrt((y ** 2).mean()) * .12
    pk = np.max(np.abs(y)); lim = 10 ** (-1 / 20)
    if pk > lim: y = y * lim / pk
    write(path, y, channels=2)
    ff(["-i", path, "-c:a", "libvorbis", "-q:a", "6", path[:-4] + ".ogg"])


def page():
    rows = "\n".join(
        f'''    <li class="v" data-n="{i}"><div class="hd"><span class="num">{i:02d}</span><b>{name}</b></div>
      <p>{desc}</p><audio controls preload="auto" src="sounds/engine_variants/v{i:02d}.wav"></audio>
      <button class="pick" type="button">Pick {i:02d}</button></li>'''
        for i, (name, desc, _) in enumerate(V, 1))
    html = f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Engine Sound Picker</title>
<style>
:root{{--bg:#0f1012;--card:#1a1b1f;--fg:#ececef;--dim:#9a9ba1;--acc:#e8930c;--line:#2a2b30}}
@media (prefers-color-scheme: light){{:root:not([data-theme="dark"]){{--bg:#f6f6f7;--card:#fff;--fg:#141518;--dim:#5d5e66;--acc:#b86e00;--line:#e2e2e6}}}}
*{{box-sizing:border-box}} body{{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 system-ui,sans-serif}}
main{{max-width:760px;margin:0 auto;padding:24px 16px 80px}} h1{{font-size:24px;margin:0 0 6px}}
.lead{{color:var(--dim);margin:0 0 18px}} ol{{list-style:none;padding:0;margin:0;display:grid;gap:12px}}
.v{{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px 16px}}
.v.on{{border-color:var(--acc);box-shadow:0 0 0 1px var(--acc)}}
.hd{{display:flex;gap:10px;align-items:baseline}} .num{{color:var(--acc);font-weight:700;font-variant-numeric:tabular-nums}}
.v p{{color:var(--dim);margin:4px 0 10px}} audio{{width:100%}}
.pick{{margin-top:8px;background:none;border:1px solid var(--line);color:var(--fg);border-radius:9px;padding:6px 12px;cursor:pointer}}
.v.on .pick{{background:var(--acc);border-color:var(--acc);color:#111}}
.bar{{position:fixed;left:0;right:0;bottom:0;background:var(--card);border-top:1px solid var(--line);padding:12px 16px;text-align:center}}
</style></head><body><main>
<h1>Engine sound: pick the best thump</h1>
<p class="lead">The same rev from your recording, EQ'd 12 ways. All are volume-matched. Listen on what you'll actually use: helmet,
headphones or the phone speaker. Small speakers can't play the deepest notes, which is what the "overtones" versions are for.</p>
<ol>
{rows}
</ol></main>
<div class="bar" id="bar">Tap "Pick" on your favourite, then tell Claude the number.</div>
<script>
document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{{if(b!==a)b.pause()}})));
document.querySelectorAll('.pick').forEach(b=>b.addEventListener('click',()=>{{
  document.querySelectorAll('.v').forEach(v=>v.classList.remove('on'));
  const v=b.closest('.v'); v.classList.add('on');
  document.getElementById('bar').textContent='Picked '+v.dataset.n.padStart(2,'0')+': '+v.querySelector('b').textContent+'. Tell Claude this number.';
}}));
</script></body></html>
'''
    with open(PAGE, "w") as f:
        f.write(html)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("--ship", type=int, help="copy this version into the app")
    a = ap.parse_args()
    if a.ship:
        src = os.path.join(OUT, f"v{a.ship:02d}.ogg")
        subprocess.run(["cp", src, OGG], check=True)
        subprocess.run(["cp", src[:-4] + ".wav", os.path.join(ROOT, "tools", "sounds", "engine_start.wav")], check=True)
        print(f"shipped v{a.ship:02d} ({V[a.ship - 1][0]}) → {OGG}")
        return
    os.makedirs(OUT, exist_ok=True)
    clip = base(a.input)
    for i, (name, _, chain) in enumerate(V, 1):
        render(clip, chain, os.path.join(OUT, f"v{i:02d}.wav"))
        print(f"v{i:02d} {name}")
    page()
    print(PAGE)


if __name__ == "__main__":
    main()
