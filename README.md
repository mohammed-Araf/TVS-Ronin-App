# Ronin Dash

An open-source Android companion app for the **TVS Ronin** (2024 SE, cluster type U368), written as a replacement for the
official TVS Connect app.

> **Unofficial.** This project is not affiliated with, endorsed by, or connected to TVS Motor Company. "TVS" and "Ronin" are
> their trademarks, used here only to say which motorcycle this works with. No TVS code, logos or assets are included.

> **Ride safely.** Don't look at or touch your phone while riding. The app is built to work without attention (sounds in the
> helmet, the bike's own display), but you are responsible for how you use it. It's experimental software: test it parked
> first. Crash detection and SOS are best-effort and must not be relied on as your only safety measure.

## What it does

- Connects to the bike by itself when the ignition turns on, uses no memory or battery while the bike is off.
- Caller name on the bike's display; answer / reject with the I button (see the exact press rules in `PROTOCOL.md` §14).
- I button opens your phone's assistant (hold 2–3 s and let go).
- Live data: speed, RPM, gear, throttle, fuel gauge (in bars, the way the bike really sends it), trips, temperatures, warnings.
- Sets the bike display brightness (20–100 %), automatically from the light around you or when your assistant asks.
- Trips, mileage from fuel flow, and crash detection with SOS (experimental).

Not yet done: much of the design in `tools/dashboard-mock.html` (the reviewed mock-up) is still being ported to the app.

## Layout

| Path | What |
|---|---|
| `android/` | The app (Kotlin, Jetpack Compose). `cd android && ./gradlew assembleDebug` |
| `PROTOCOL.md` | The bike's Bluetooth protocol, worked out from captures and bike tests |
| `PROJECT_STATUS.md` | Design notes and progress |
| `tools/dashboard-mock.html` | Browser mock-up of the app, with the list of planned fixes |
| `tools/ronin_test.py` | Frame decoder / test console |
| `tools/data_guard.py` | Checks decoded data for impossible values |
| `tools/make_sounds.py`, `tools/make_voice.py` | Generate the helmet sounds and voice clips |

## Voice

The spoken clips in `tools/voice/` are generated with [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) (Apache-2.0).
