# Ronin Open Dash — Project Status & Handoff

**Read this first if you're picking the project back up (human or Claude).**
Last updated: 2026-11-25 (night session).

## Goal

Replace the buggy official **TVS Connect** app with our own open-source Android app for the
**TVS Ronin Special Edition 2024**. It should talk to the bike's instrument cluster over Bluetooth LE and
**work for other Ronin owners too** (no TVS account, free, open source).

Features wanted:
- Caller name, missed calls and SMS on the cluster (actually working)
- **Google Maps turn-by-turn on the cluster** by reading Google Maps' own navigation notification, so no API key is needed
- **I button**: reliable call accept/reject, and launch **Google Assistant / Gemini** (configurable)
- Live bike data in the app (speed, odo, fuel, DTE, gear, engine temp, ride mode, trips, warnings)
- No auto-triggering voice assistant on connect

## Owner's setup / facts

- Phone: **Android**, Xiaomi/Redmi (MIUI 14, Android 13). USB debugging is enabled.
- Mac with Homebrew. Installed: `adb`, `jadx`.
- Helmet intercom: **"Q8"** (handles call audio itself, not involved in the bike's bugs).
- Bike buttons:
  - **I button**: has NO function on the bike itself. It only signals the app (voice assist, call accept/reject).
    The whole left switch set was replaced and nothing changed, so it's not hardware.
  - **Mode button** (Urban/Rain): handled by the bike's own computer. The app only displays the mode. Works instantly.
- Official app login works on any phone with the same account. The bike's MAC is stored per account, in the app's own DB.
- **Bluetooth pairing requires a PIN.** The bike rejects pairing without it. This is the real security layer.
- Owner cleared the phone's Bluetooth list, so the bike must be **re-paired with the PIN** on next connect.
- Symptoms with the official app: "999" / the **"99 glitch"** (missed calls/messages jump to 99, seen again after an intercom-answered call in ride2, see PROTOCOL §12), call buttons unreliable, voice assist
  auto-starts on connect and then needs many presses, map search loads forever.

## What has been done

1. Pulled the official APK from the phone (`com.tvsm.connect` **v8.14.3**, versionCode 339) into `apk/`.
2. Decompiled it with jadx into `decompiled/` (the decompiler reported 593 errors, which is normal; the important classes decompiled fine).
3. Identified the Ronin as internal model **U368**, using the **U399C** GATT service/characteristic slots.
4. Reverse-engineered the protocol. **Full details are in [PROTOCOL.md](PROTOCOL.md).** Summary:
   - 20-byte BLE frames. "Encryption" = payload bytes XOR `0xEB` (first 2 bytes and the final `0xFF` are untouched).
   - GATT UUIDs are fetched from TVS servers, not hard-coded, so we'll discover them from the bike.
   - Decoded cluster→phone frame types 0x10/0x11/0x12/0x16/0x18/0x19 (speed, odo, fuel, RPM, gear,
     temps, ride mode, telltales, trips, DTE, buttons in byte 11).
   - Decoded phone→cluster frames: `5B 4A` mobile status (clock, missed calls/SMS, VA state),
     `5B 43` caller, `5B 53` SMS, `5B 52` rider name, `5B 5C` location, `5B 4C` free text, `5A F1` settings.
   - Navigation frame (`5A 4E`) is only partly decoded. TODO: trace `sendAdviseInfoToListener`.
5. Found the official app's bugs that explain every symptom (see PROTOCOL.md §5): a flood of writes
   every 100 ms during voice assist, a 6 s lockout on the I button, no edge detection (so it auto-triggers),
   call accept/reject compares the whole bitfield byte, `getMissedSMS()` hard-coded to 0, and the disconnect
   frame sent without XOR (likely cause of the 999s).
6. Decided: the Mac is great for protocol testing (Python + `bleak`), but the final app must be Android
   (calls, notifications, Google Maps).

## Bike identity (found 2026-11-25 with nRF Connect)

- Bluetooth name: **`TVS22XXXXXXXX`**
- MAC: **`XX:XX:XX:XX:XX:XX`**
- The phone is now **BONDED** with the bike. Pairing went through the TVS app with a 6-digit passkey.
  A direct pairing attempt from nRF Connect first failed with "wrong passkey". The official app has **no code that supplies
  the passkey** (its pairing receiver only logs). **Confirmed: the 6-digit passkey is shown on the bike's display** during
  pairing, and the rider types it into Android's pairing popup. **New devices can only pair while the cluster is in
  Bluetooth pairing mode** (entered from the cluster's menu button). Outside pairing mode it accepts only already-bonded devices.
  It's unknown how many bonds the cluster keeps. Pairing a new device (e.g. the Mac) might drop the phone's bond, and then the phone just re-pairs. It's standard BLE passkey-entry pairing, so our app needs no
  secret, just tell the user "enter the code shown on your bike's display".
- GATT services: `0x1800` Generic Access, `0x1801` Generic Attribute, `0x180A` Device Information, and the custom
  service **`5456534d-5647-5341-5342-454e544f5251`**, which is ASCII "TVSMVGSASBENTORQ". The same value is hard-coded in the official
  app as `U399_SERVICE_PARCEL_UUID` in `digidoc/util/DigiDocUtils.java`.
- Characteristics inside the custom service:
  - **RX (bike → phone, NOTIFY): `0x5354`** → `00005354-0000-1000-8000-00805f9b34fb` (ASCII "ST")
  - **TX (phone → bike, WRITE): `0x5352`** → `00005352-0000-1000-8000-00805f9b34fb` (ASCII "SR")
- **Protocol verified on a real frame** captured in nRF Connect:
  `5A 11 EB AB EA E9 EB FA EB 2F EB EB EB EB E9 FB EB CA A2 FF`. XOR 0xEB gives
  `5A 11 00 40 01 02 00 11 00 C4 00 00 00 00 02 10 00 21 49 FF`. **Checksum valid** (182 + 0x49 = 255).
  Type 0x11 status: side stand down, side-stand telltale on, engine not started, speedo SW v17 (0x11),
  vehicle model byte 0xC4 = 196 (the app labels this "Apache", so the Ronin reports the Apache platform id),
  illumination 2, ride mode 1, no fault bits. Byte 5 = 0x02 (gear/shift nibble, meaning unclear, check with gear changes).

## Next steps (in order)

1. ~~**At the bike (owner):** find name/MAC/services/characteristics~~ **done 2026-11-25.**
2. *(Optional but very useful)* Turn on **Bluetooth HCI snoop log** (Developer options), toggle BT, ride/test
   with the official app (connect, a call accepted/rejected via I button, SMS, I button presses:
   short/long/double, mode changes, navigation), then `adb bugreport capture.zip` into this folder.
3. ~~**Claude:** write the Mac test script~~ **done 2026-11-25:** `tools/ronin_test.py` (Python venv in `.venv/`, bleak 3.1.0).
   Its offline self-test passes against the real captured frame. **It must be run from the owner's own Terminal app**:
   macOS kills Bluetooth access from Claude's background shell (exit 134), and Terminal needs Bluetooth permission
   (System Settings → Privacy & Security → Bluetooth). Each session writes a CSV log to `logs/`.
   **Result (2026-11-25): Mac is a dead end.** The Mac connects and sees both characteristics, but the bike streams
   **all-zero frames** (~4/s) to an unpaired device, even after sending name and status frames. Every readable GATT item
   (Device Info etc.) is unprotected, so macOS never gets asked to pair, and CoreBluetooth can't force pairing.
   Conclusion: **the bike sends real data only over a bonded (encrypted) link**, so testing moves to Android
   (phone already bonded; `BluetoothDevice.createBond()` while the cluster is in pairing mode for new users).
   Device Info values seen: model/serial/firmware strings are placeholders ("Model Number", "experimental").
   Original plan for the Mac run, kept for the Android test build: Test plan: watch frames, shift gears, press the I button
   short/long/double (use `mark` before each), try `caller`, `text`, `status calls=3 sms=2`. Then send Claude the log file.
3b. **Android test app built 2026-11-25:** `android/` (Kotlin, AGP 9.5.2 with built-in Kotlin, Gradle 9.6.0 wrapper,
   minSdk 31, compileSdk 36, package `dev.ronindash.test`, label "Ronin Dash Test").
   Build: `cd android && ./gradlew assembleDebug`. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
   Xiaomi phones need **Developer options → "Install via USB"** turned on (otherwise INSTALL_FAILED_USER_RESTRICTED),
   and that needs a Mi account, which the owner doesn't use. **Real blocker found:** Android's 2026 **developer verification**
   refuses apps from unregistered developers (debug-signed = unverified). Play Protect approved the app, but the install was refused.
   Enabling "allow apps from unverified developers" has a **one-time 24-hour wait** (found 2026-11-25).
   `com.miui.packageinstaller` is absent on the phone (MIUI 14 / Android 13), and the "MIUI optimization" toggle couldn't be found.
   **For publishing to other riders:** we must register as a verified developer with Google (Play Console / Android
   developer verification) and sign releases with that registered key, or every rider hits this same wall.
   Sideload path once allowed: `adb push app/build/outputs/apk/debug/app-debug.apk
   /sdcard/Download/RoninDashTest.apk`, then the owner taps it in a file manager (allow "install unknown apps" for that app).
   The app connects by MAC (auto-picks a paired "TVS…" device), enables notify on 0x5354, shows decoded live data and
   changed frames, and has buttons Caller / Text / SMS / Status / Hello / Mark / Pair (createBond).
   Logs: `adb pull /sdcard/Android/data/dev.ronindash.test/files/logs/ logs/`.
3c. **Phone-only solo test (no install needed):** checklist `tools/bike_test_checklist.txt` (also on the phone as
   `Download/RoninTest_Checklist.txt`). The owner enables the HCI snoop log, records the official app (I-button presses, modes,
   indicators, engine), then sends our own frames from nRF Connect. Afterwards: `adb bugreport` and extract
   `btsnoop_hci.log` to decode. (Calls can't be tested solo, and WhatsApp isn't supported by the official app for U368.)
3d. **Guided test page (2026-11-25):** (private guided page, link kept locally) (source `tools/ronin-test-guide.html`).
   Opens in the phone's browser, no install. It walks the owner through each step (official-app VA/I-button checks, call
   accept/reject, then nRF Connect writes with Copy buttons for caller/SMS/text/name/status/navigation frames), with timers.
   Every answer is saved with a timestamp to the artifact's database, collection `events`, which Claude reads with the ArtifactData tool.
   There's also a localStorage backup and a "Copy all results" button. Workflow: snoop log ON, run the page at the bike, then plug in the phone,
   `adb bugreport`, and line the page timestamps up with the btsnoop frames (btsnoop parse times are local).
4. ~~**Claude:** finish decoding the navigation frame from the decompiled code.~~ Done, see PROTOCOL.md §11.
5. **Real app v0.2 built 2026-11-25** (`android/`, package `dev.ronindash`, label "Ronin Dash", Jetpack Compose + Material 3,
   Kotlin compose plugin 2.2.10 to match AGP 9.5.2's bundled Kotlin). It replaces the old test app: **one app**, with a built-in
   "Test & log" tab. Builds clean; **not installed yet** (same install block as 3b). APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
   Files in `app/src/main/java/dev/ronindash/`:
   - `Protocol.kt`: frames (status, text types, nav `5A 4E` / `5B 4F`) plus `BikeState.update()` decoding all frame types.
   - `BikeService.kt`: foreground service (connectedDevice). Single GATT write queue, auto-reconnect (autoConnect after a link loss).
     Status every 2 s with real missed calls (CallLog NEW=1) and unread messages, overspeed fixed at 120, VA=32 only for 4 s after
     *our* assistant launch. I button acts on the **rising edge** of `b11 & 0x07`: 1 = answer (ringing), 2 = reject / hang up / clear,
     3 = mode (ignored), 4 = launch assistant (idle only). Caller name from contacts, sent 3× (not 11). CSV log of every RX/TX/event
     in `/sdcard/Android/data/dev.ronindash/files/logs/ride_*.csv`.
   - `NotifListener.kt`: SMS / WhatsApp (sender to `5B 53`, unread = message notifications in the shade) and Google Maps nav
     notification → `NavParser.kt` → nav frames. Raw Maps strings are logged as `[maps]` so the parser can be tuned.
   - UI: `RideScreen.kt` (speed, gear, RPM, telltales, fuel/DTE, trips, temps, nav card, counts), `TestScreen.kt` (21 guided steps;
     it sends frames itself, so no nRF needed, and every answer goes into the same CSV as the bike frames), `SetupScreen.kt`
     (paired-bike picker, scan + createBond pairing with instructions, permission checklist incl. notification access / overlay /
     battery, greeting name, feature toggles, I-button action: default assistant / Gemini / none).
   - **v0.3 (same day): trips, mileage, crash/SOS.**
     - `Trip.kt`: `TripRecorder` starts when the bike moves, ends after 10 min still. It saves GPS + speed/RPM/gear/estimated fuel to
       `files/trips/trip_*.csv` plus a summary `.json`. `Mileage` model: owner's figures 60 km/h = 44 km/l, 120 km/h = 32 km/l
       (linear between, worse below 30 km/h), × sqrt(actual RPM-per-km/h ÷ learned top-gear ratio) clamped 0.85–1.8, × acceleration
       factor, idle 0.15 L/h. The top-gear ratio is learned (10th percentile of rpm/kmh above 35 km/h) and saved. A fuel-gauge
       cross-check comes from fuel % × tank (14 L).
     - `assets/trip.html` + `TripMapActivity` (WebView): MapLibre 5.24.1 with Esri World Imagery satellite / OpenFreeMap street,
       AWS terrarium terrain ×1.5, route + 3D extruded "wall" coloured green→blue→red by speed / mileage / RPM, follow-cam replay
       with HUD, stats card. No API key (idea from github.com/ashmitmittal/trip-map-skill, MIT). Opens in a desktop browser with demo data.
       **Before publishing:** Esri's terms need a key for heavy or commercial use. Consider street-only by default.
     - `Crash.kt` (`CrashGuard`) + `CrashActivity`: >4 g impact while >20 km/h within 3 s, then the bike stops or the link drops;
       or the engine cuts out at >25 km/h. Then a 30 s alarm + full-screen countdown over the lock screen. Cancel via I'M OK or any
       bike button; the cluster shows "CRASH? PRESS I". At 0 it sends SMS with a Google Maps link to up to 5 contacts, and an
       optional auto-call. There's also a manual SOS (long-press on the Ride tab) and a "Test the alert screen" button in Setup.
   - **Trip page redesign (same day):** a dark navy dashboard look (glass cards, cyan→violet→pink gradients).
     - Tapping the route or dragging on the timeline chart selects any moment and shows: clock time, km from start, a coordinates link to
       Google Maps, ring gauges for speed / RPM / gear, and mileage + fuel + ₹ **up to that point**, plus altitude.
       Per-point data is `[lon, lat, alt, kmh, rpm, gear, kmpl, t, kmFromStart, litresSoFar]`.
     - Timeline chart: speed area (gradient) + RPM line, with a cursor.
     - Full-ride animation: the route draws itself (layer filter on segment index, throttled to 4 Hz) with a follow-cam,
       and the chart reveals in sync. Durations 30 s / 1 min / 2 min, follow on/off.
     - Tested in a browser with demo data at phone width.
   - **v0.4 (same day): clean UI + Settings.** The owner disliked the neon trip page, so the whole app now uses one calm style:
     neutral surfaces, thin borders, a single accent colour.
     - The "Setup" tab is now **Settings** (`SettingsScreen.kt`). Sections: Appearance (theme System/Light/Dark, 6 accent colours,
       keep screen on), Bike (picker, pairing, start/stop), Speed alert, Service reminder, Crash & SOS, bike display, I button,
       Trips & fuel, Permissions, App.
     - Theme and accent live in `Bike.theme` / `Bike.accent` and apply instantly. `TripMapActivity` passes them to the page
       via `Android.theme()`.
     - **Speed alert:** above the limit for 3 s → beep + vibrate (at most every 30 s). It can also send the limit to the cluster's own
       overspeed warning (status byte 3, previously fixed at 120).
     - **Service reminder:** every N km (default 6000) on the odometer, or from "last service at" / "I just got it serviced".
       It warns once per due point, N km before (default 300), with a phone notification + cluster text "SERVICE IN xKM" / "SERVICE DUE".
       The Ride tab shows a service card.
     - The trip page restyle keeps every feature: point readout (speed, rpm, gear, km/l so far, time, km, fuel ₹, altitude, map link),
       timeline chart, animation, metric/base toggles. The 3D wall and the footer text were removed (credits are a collapsed ⓘ).
   - **Motion + I-button calm-down (same day):**
     - Animations everywhere:
       - Speed and RPM count smoothly; RPM and fuel bars animate, and the RPM bar turns red near redline.
       - Gear slides up or down; telltales fade on and off.
       - Trip, nav and service cards expand and fade in; the connection dot pulses while connecting.
       - Tabs slide and fade; test steps slide; the Settings speed section expands.
       - Trips list items animate.
       - Trip page: cards rise in, readout numbers count, the map eases to a tapped point. Reduced-motion is respected.
     - Assistant: opened **once per press**. Further 0x04 codes are ignored for 10 s, so it can't keep popping up.
     - The cluster "Listening" shows for 3 s, then an idle status closes it.
     - Our cluster pop-ups close by themselves: SMS after 8 s, service text after 10 s, caller 1.5 s after the call ends (blank `5B 4C`).
   - **Ride end vs break + sharing (same day):**
     - `TripRecorder` decides automatically. A stop with the engine running = traffic/waiting, so the ride ends only after 20 min.
       Ignition off / bike gone = a break; if the rider doesn't ride again within 45 min the ride ends, timestamped at the last
       movement. Stops over 3 min that the rider continues from count as breaks (`breaks`, `breakS` in the summary).
     - The Trips tab shows "Riding now" / "On a break · N min" with an **End ride** button. GPS stays on only while moving recently.
     - Share: the trip page's Share button (and Trips list → Share, via `TripMapActivity` extra `share=true`) opens a preview sheet.
       The card is drawn in trip.html (`drawCard()`): styles Dark / Light / Colour (accent gradient), formats Post 1080×1350 / Story 1080×1920.
       It shows date + time, "Morning/Afternoon/Evening/Night ride", the route coloured by speed with a glow, the distance big, then
       riding time / avg / top / km/l (Story adds fuel ₹, climb, max RPM, breaks + a speed profile). No map tiles and no coordinates;
       the first/last 300 m are trimmed. JS → `Android.shareImage(b64)` / `saveImage(b64)`; `ShareCard.kt` shares through FileProvider
       or saves to Pictures/Ronin Dash via MediaStore. Back closes the sheet (`Android.sheet()` + OnBackInvokedCallback / onBackPressed).
     - Trip page layout: the bottom panel overlays the map (margin-top −22px, rounded top corners, grab handle, shadow); cards have no borders.
       Wide screens (≥900px): the map is full-bleed and the panel floats as an inset rounded card (top/right/bottom 12px); fit/follow padding avoids it.
     - Share card map: share sheet has Map / Satellite / No map. Tiles are Esri (no key): `Canvas/World_Dark_Gray_Base` or
       `World_Light_Gray_Base` (label-free) by card style, or `World_Imagery`; drawn under the route in web-mercator, faded into the card,
       with the attribution printed at the bottom of the card. CARTO basemaps were tried and now return "API KEY REQUIRED" tiles.
       The map shows the area ridden (only the first/last 300 m of the line are trimmed); "No map" is the most private option.
     - Trip page: the 30 s/1 min/2 min chips and the Follow toggle were removed. Play always follows. Playback follows the ride's own timestamps (`PC` clock), stops/breaks fast-forward (≤2 s each),
       and length ≈ 6 s × √(riding minutes), clamped 12 s–2 min: 10 min → ~19 s, 1 h → ~46 s, 5 h → ~1:45 (`PLAY` in trip.html).
       Outside the app, `trip.html?demo=<minutes>` makes a demo ride of that length.
       Coordinates were removed (only km from start).
   - **Rolling numbers (same day):**
     - Ride tab speed uses `RollingNumber`: odometer-style digits keyed from the right, rolling up when the value rises and down when it falls.
     - Gear rolls up on upshift and down on downshift (app and trip page; trip page `rollTo()`).
     - The trip page colour bar was removed.
     - Map ⓘ credits: custom `#credits` pill (MapLibre attribution off), real width animation 0.6 s both ways, folds back after 5 s.
     - Trip readout: speed 52px | rpm + km/l | fuel cost so far (₹, litres below) | gear tile 76×76 with 44px accent number.
       Time shows h:mm AM/PM. Fuel moved out of the meta row.
     - Button/slider motion: segmented toggles have a sliding highlight (`placeThumb()`), every button has a press bounce,
       Play↔Pause icons morph, the chart cursor glides when a point is picked on the map.
       App Settings theme toggle uses `SlidingSegments` (Motion.kt) instead of Material SegmentedButton.
   - Open questions the Test tab answers: pictogram ids for left/right (now uses id 7 + state 3/4 + text), whether `0x04` really is the
     long press, whether `0x01` = long press during a call, Maps notification wording on this phone.

- **Helmet sound pack** (`tools/make_sounds.py` → `tools/sounds/*.wav`, 25 stereo cues, all synthesised by us, so no licence
  issues): indicator tick/tock per side, indicator-left-on nudge, turn soon/now per ear, roundabout, U-turn, re-route, arrived,
  speed, fuel low, warning lamp, service, emergency alarm, missed call, message, connected, disconnected. The browser mock plays
  these exact files. For the app: convert to OGG (`ffmpeg -i x.wav -c:a libvorbis -q:a 4 x.ogg`) into `res/raw`, play with
  SoundPool on the navigation audio stream with transient-may-duck focus; voice (TextToSpeech) only per the Voice setting
  (Off / Emergencies (default) / More).

- **Bad-data guard** (`tools/data_guard.py`, reference for `DataGuard.kt`): range → rate → cross-check → confirm → hold → unknown.
  Limits come from physics and were checked on every real frame (ride1, ride2, call1: 0 real values rejected; real extremes
  include a neutral rev to 8,726 rpm, engine temperature to 134 °C, +3 V battery rise when charging starts). Glitch test: 7,740
  wrong-but-checksum-valid values injected over 60 runs → no impossible value shown, none kept once real data resumes; ~6 %
  physically possible ones pass for one reading, so consumers act only on 2+ agreeing readings. Run:
  `.venv/bin/python tools/data_guard.py` (exit code 0 = pass). Findings: odometer has 1 km resolution; range bytes are 0 on this bike.

**Mileage (planned, in the mock, FIXES 13–15):** fuel flow = K × RPM × load(throttle), load = 0.19 + 0.81·(1 − e^(−thr/25)), K ≈ 5.27e-4 (idle 0.15 L/h, 60 km/h in 5th ≈ 44 km/l, 120 ≈ 32), fuel cut when the throttle is shut while rolling, ×1.15 rich at full throttle. Throttle u[10]/2 is real (ride2). K is corrected per bike at each fill-up. Range = our litres left × average; the bike's DTE is never used. Gear ratios ≈ 150/118/101/79/65 rpm per km/h. Every value animates (count / fade / glide); mileage is coloured green (≥46) → red (≤28). FIXES 16–17: distance from GPS (bike speed ÷ learned speedometer error when GPS drops, odometer as the long-run check); range = our litres left × the rider's mileage over the last ~50 km; at a fuel station (OSM list + learned stations, or the gauge went up) ask 'Filled up? Full tank / Litres / ₹ / No'; full-to-full calibration of K. Answering is optional: no answer → the bike's fuel gauge rise decides (≥95 % = full); an answer replaces the guess. FIXES 18–20: no always-on service. Pair as a CompanionDeviceManager device and observe presence (minSdk 31 OK); bike on → CompanionDeviceService starts BikeService, bike off → it stops (0 MB). Ride notification on a MIN channel; the only posted notification is service due. GPS ~every 5 s while moving. Opening = instrument-cluster style needle sweep (no bike drawing). One-screen first-time setup. FIXES 20–22: six automatic screens (parked = speed 0 + stand down; riding; fast ≥ 70 km/h) × day/night, turn compass with a draining meter on the fast screen (turn appears ~36 s ahead), owner's gear data (tools/data_guard.py now checks gear vs gear top speed; replay still PASS). FIXES 24–28: sun/heat (screen rests in direct sun by default; 'Fight direct sunlight' setting, off; thermal status backs off), battery rules (60 Hz, sensors only while riding on the mount, no idle work), dashboard opens itself on ignition when the phone is on the mount (not in a pocket), sensors (compass heading at low speed, lean angle, bike-down → crash alert; no camera), natural voice (pre-rendered neural clips for fixed phrases + best offline Android voice for names). Voice clips: Kokoro-82M (Apache-2.0) in `.tts/venv` (uv, Python 3.11; model cached in ~/.cache/huggingface); `.tts/venv/bin/python -I tools/make_voice.py` renders 21 phrases × 4 voices (Heart, Emma, Michael, George) to `tools/voice/<voice>/*.wav` + `.ogg` (~108 KB per voice); Settings → Whose voice. FIX 29: bike display brightness follows the phone's light sensor via `5A F1` byte 2 (1–5; Ronin = `U368SendDataToCluster.sendVehicleData`), only when connected + mounted + sensor facing the sky; manual change on the bike pauses it for the ride. Needs bike test B3b in tools/bike_test_checklist.txt before shipping (the same frame also selects the speedo page and resets Trip A/B/DTE, so byte 3 must stay 0). FIX 31: assistants (Gemini via AppFunctions on Android 16+; app shortcuts, an exported intent and a deep link for Google Assistant / Tasker / other agents on older Android) can only set the bike display brightness (low/high/up/down/1–5); nothing else is exported. The owner's phone is Android 13, so Gemini's direct AppFunctions path won't reach it there. **Bike-tested 2026-11-25:** brightness `5A F1` dated form works, levels 2–10 (20–100 %; 1 confuses the cluster), read back in 0x11 byte 17 high nibble (see PROTOCOL). **Test phone found:** a second phone (POCO F1, custom ROM, Android 16) takes `adb install -r -g` with no Xiaomi or verification block, so the debug app now runs on the bike. The debug build has `DebugReceiver` (adb-only via the DUMP permission, inert in release): `adb shell am broadcast -n dev.ronindash/.DebugReceiver --ei bright N` / `--es hex "<plain frame>"` / `--ez status true`, and `Bike.log` goes to logcat tag `RoninDash`. Fixed a crash on the first bike frame (`Protocol.describe` used String.format with `% t`).

## Publishing rules (when we open-source it)

- Own name. Label it "unofficial, not affiliated with TVS Motor". No TVS logos or assets.
- **Never publish `decompiled/` or `apk/`.** Those folders are git-ignored. Only our own code and the protocol notes go public.
- Add a ride-safety disclaimer.

## Folder layout

| Path | What | Public? |
|---|---|---|
| `PROJECT_STATUS.md` | This file | yes |
| `PROTOCOL.md` | Protocol and bug findings | yes (our own notes) |
| `apk/` | Official APK splits pulled from phone | **no** |
| `decompiled/` | jadx output of the official app | **no** |
| `native/` | Extracted native libs (just config loaders, not BLE) | **no** |
| `jadx.log` | Decompile log | no |

## Useful commands

```bash
adb devices
adb shell pm path com.tvsm.connect
jadx -d decompiled apk/base.apk
grep -rn "U368" decompiled/sources/com/tvsm/connect/bluetooth
```
