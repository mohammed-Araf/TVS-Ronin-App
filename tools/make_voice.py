#!/usr/bin/env python3
"""
Ronin Dash voice clips: the few fixed phrases the app may speak, rendered once with Kokoro-82M (Apache-2.0, open weights,
runs offline on the Mac) so the app ships a natural human-sounding voice as small audio files instead of a robotic TTS.

    .tts/venv/bin/python -I tools/make_voice.py            # writes tools/voice/<voice>/<phrase>.wav and .ogg

Kokoro lives in its own folder (.tts/venv, model cached by Hugging Face under ~/.cache/huggingface). Names (Voice: More) can't
be pre-rendered; the app speaks those with the phone's best offline voice.
"""
import os
import subprocess

import numpy as np
import soundfile as sf
from kokoro import KPipeline

SR = 24000
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "voice")

# Short on purpose: the sound plays first, the words only confirm it. Emergencies get the only full sentences.
PHRASES = {
    # emergencies (Voice: Emergencies, the default)
    "side_stand": "Side stand!",
    "crash": "Crash detected. Hold the I button if you're okay.",
    "crash_10": "Sending S O S in ten seconds.",
    "sos_sent": "S O S sent to your contacts.",
    "crash_ok": "Okay. Cancelled.",
    "bike_down": "Bike down. Hold the I button if you're okay.",
    # Voice: More (still only a word or two)
    "fuel_low": "Fuel low.",
    "bike_lost": "Bike disconnected.",
    "bike_connected": "Bike connected.",
    "indicator": "Indicator.",
    "check_engine": "Check engine.",
    "service_due": "Service due.",
    "slow_down": "Slow down.",
    "turn_left": "Left turn.",
    "turn_right": "Right turn.",
    "keep_straight": "Keep straight.",
    "roundabout": "Roundabout.",
    "uturn": "U-turn.",
    "arrived": "You've arrived.",
    "missed_call": "Missed call.",
    "message": "Message.",
}

# Kokoro's best-rated English voices: (voice, language code: a = American, b = British, speed)
VOICES = [("af_heart", "a", 1.0), ("bf_emma", "b", 1.0), ("am_michael", "a", 1.0), ("bm_george", "b", 1.0)]


def trim(x, thr=0.006, pad=0.03):
    idx = np.where(np.abs(x) > thr)[0]
    if not len(idx):
        return x
    a, b = max(0, idx[0] - int(pad * SR)), min(len(x), idx[-1] + int(pad * SR))
    y = x[a:b].copy()
    f = int(0.012 * SR)  # tiny fades: no clicks
    y[:f] *= np.linspace(0, 1, f)
    y[-f:] *= np.linspace(1, 0, f)
    return y


def main():
    for voice, lang, speed in VOICES:
        pipe = KPipeline(lang_code=lang, repo_id="hexgrad/Kokoro-82M")
        d = os.path.join(OUT, voice)
        os.makedirs(d, exist_ok=True)
        for name, text in PHRASES.items():
            parts = [np.asarray(audio, dtype=np.float32) for _, _, audio in pipe(text, voice=voice, speed=speed)]
            x = trim(np.concatenate(parts))
            x = x / max(1e-6, np.abs(x).max()) * 0.8
            wav = os.path.join(d, name + ".wav")
            sf.write(wav, x, SR, subtype="PCM_16")
            # app copy: Opus in OGG, mono 32 kbit/s (res/raw)
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libopus", "-b:a", "32k", wav[:-4] + ".ogg"], check=True)
        print(f"{voice}: {len(PHRASES)} phrases")
    total = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(OUT) for f in fs if f.endswith(".ogg"))
    print(f"app clips (ogg, all voices): {total / 1024:.0f} KB  ·  one voice ≈ {total / len(VOICES) / 1024:.0f} KB")


if __name__ == "__main__":
    main()
