# TVS Ronin (U368) ↔ Phone BLE Protocol — findings from TVS Connect 8.14.3

Source: decompiled `com.tvsm.connect` v8.14.3 (versionCode 339). Internally the Ronin is model **U368**
and reuses the **U399C** GATT service/characteristic UUIDs.

Key files (in `decompiled/sources/com/tvsm/connect/`):
- `bluetooth/bluetoothlegatt/U368HelperService.java` — connection, notify setup
- `bluetooth/sendreceive/U368SendDataToCluster.java` — everything phone → cluster
- `bluetooth/sendreceive/U368DataParser.java` — cluster → phone frame decoding
- `bluetooth/sendreceive/U368ClusterDataManager.java` — frame intake, voice assist trigger
- `bluetooth/security/U368EncryptDecryptDataUtils.java` — "encryption"
- `bluetooth/security/U399ChecksumUtils.java` — checksum on incoming frames
- `utils/Utils.java` → `controlU368PhoneCall()` — call accept/reject

---

## 1. Connection

- Plain BLE GATT (the app uses the "Blessed" BLE library, `blemanager/`).
- **Confirmed on the bike:** service `5456534d-5647-5341-5342-454e544f5251` ("TVSMVGSASBENTORQ"),
  RX notify `0x5354`, TX write `0x5352` (16-bit UUIDs on the Bluetooth base UUID). Pairing uses a 6-digit passkey
  shown on the bike's display. Bit positions in the parser are **1-indexed** (`(v >> (pos-1)) & 1`).
- In the official app, service / RX (notify) / TX (write) UUIDs are **not hard-coded**: they're downloaded from TVS's
  server after login and stored in prefs under `SERVICE_UUID_U399C`,
  `RECEIVE_CHARACTERISTIC_UUID_U399C`, `SEND_CHARACTERISTIC_UUID_U399C`.
  → We can read them directly off the bike with a GATT scan (nRF Connect, or our own app).
- The bike's MAC address is stored per account (`DBHelper.getConnectedBikeAddress`), which is why
  login on any phone "just works". No per-phone cryptographic pairing was found in the U368 path.
- Writes use write type `2` (WRITE_TYPE_DEFAULT = with response). Frames are 20 bytes.
- Cyclic sending only happens while `bondState == BONDED (12)`, so the phone does bond at BLE level.

## 2. "Encryption"

Applies to **both directions** (incoming frames are passed through the same function):

```
frame = [b0, b1, payload..., 0xFF]
encoded = [b0, b1, (payload[i] XOR 0xEB)..., 0xFF]
```

The first two bytes and the trailing 0xFF are left as-is. That's the entire scheme. There's no key exchange.

## 3. Cluster → phone frames (20 bytes, after XOR decode)

Validated by checksum: `(sum(bytes[0..17], 0xFF counted as 255) mod 256) + bytes[18] == 255`.
Frame type = `bytes[1]`. (`bytes[0] == 0x5A` / 90.)

### Type 0x10 (16) — live ride data
| Byte | Meaning |
|---|---|
| 2 | Speed (km/h) |
| 3–5 | Odometer, 24-bit big-endian signed, ÷10 km |
| 6 | Fuel level % |
| 7 | Average speed |
| 8 | Mileage (km/l) |
| 9 | Top speed |
| 10 | Throttle ×2 (value/2 = %) |
| **11** | **Switch / button bitfield** — see below |
| 12 | 0-60 time ×10 |
| 13 | Avg mileage (direct) |
| 16–17 | Engine RPM, 16-bit big-endian |
| 18 | Checksum |

**Byte 11 bitfield (the buttons):**
- `bit 2 (0x04)` → **I button / voice assist** pressed
- `bit 7 (0x80)` → location tag active
- whole-byte value `1` → **accept call**, `2` → **reject call** (app compares the whole byte, see bugs)

### Type 0x11 (17) — vehicle status / telltales
| Byte | Meaning |
|---|---|
| 2 | Fault bits (bit3 engine-speed sensor, bit4 gear sensor, bit5 side-stand sensor, bit6 CAN error, bit7 low battery) |
| 3 | State1 bits (bit1 clutch, bit2 brake, bit5 e-start, bit7 side stand) |
| 4 | Service reminder |
| 5 | Gear position (low nibble), gear-shift indication (high nibble) |
| 6 | State2 bits (bit1 kill switch, bit6 ES switch) |
| 7 | Speedo SW version |
| 8 | MIL blink code |
| 9 | Vehicle model id |
| 10 | Diagnostic bits (TPS, wheel-speed sensors, engine-temp sensor, IAT, MIL, ISG, ABS) |
| 11 | ISG MIL blink code / battery voltage (raw) |
| 13 | Engine temperature (value − 25 °C); also telltale bits (neutral, L/R indicator, high beam, EMS/ABS MIL, LFI) |
| 14 | Intake air temp / screen matrix |
| 15 | State3 (bit5 side-stand telltale, bit6 general warning, bit7 engine started) |
| 16 | ABS MIL blink code |
| 17 | **High nibble = display brightness 2–10** (confirmed 2026-11-25: sent 10 → 0xA1, 1 → 0x11 (invalid, see `5A F1`), 2 → 0x21; the owner rides at 2 = 20 %). Low nibble = ride mode (1 = Urban) |

### Type 0x12 (18) — trip
| Byte | Meaning |
|---|---|
| 2 | Lean angle |
| 3–4 | Cruising range |
| 6 | Acceleration |
| 7 | Torque |
| 8–9 | Trip distance ÷10 |
| 12 | Trip mileage |

### Type 0x18 (24) — engine diagnostics
5 = MAP, 7 = intake air temp (−40), 8 = engine temp (−40), 11 = battery voltage ÷10.

### Type 0x19 (25) — trips A/B and DTE
5–6 = Trip A distance ÷10, 7 = Trip A mileage, 8–9 = Trip B distance, 10 = Trip B mileage,
**11–12 = range / distance-to-empty**, 13/14 = Trip A/B average speed.

### Type 0x16 (22) — lap timer data.

## 4. Phone → cluster frames (20 bytes, XOR-encoded before write)

| Header | Purpose | Layout |
|---|---|---|
| `5B 4A` | **Mobile status** (main one) | signal+battery nibble byte, overspeed limit, 0, SMS-sent flag, hour, minute, second, AM/PM/24h flag (0/1 or 16/17), **missed calls**, 0, day, month, yy, **missed SMS**, **voice-assist state**, crash-alert flag, 0, FF |
| `5B 43` | Incoming caller name/number | ASCII (≤17 chars, "+91" stripped), zero-padded, FF |
| `5B 53` | SMS sender | ASCII (≤16), zero-padded, FF |
| `5B 52` | Rider name (greeting) | ASCII (≤17), zero-padded, FF |
| `5B 5C` | Current location name | ASCII (≤17) |
| `5B 5D` | Weather text | ASCII |
| `5B 4C` | Free text message (voice assist replies, "HIGH SPEED ALERT", "Low Fuel Alert") | ASCII (≤17) |
| `5A F1` | Settings: **display brightness**, speedo page, trip reset | **Brightness tested on the bike 2026-11-25.** Working form (official app's `onVehicleData`): `5A F1 LL 00 00 00 00 00 00 DD MM YY 00 00 01 00 00 00 00 FF`, DD MM YY = today. **LL = 2–10 = 20–100 %** (5 showed 50 % in the bike's Illumination menu; 0 and 11 are ignored). **Never send 1:** the bike takes it, but its menu has no 10 % and the cluster gets confused (owner, 2026-11-25). Takes effect at once; trips and page unchanged. Needs a live session (our `5B 4A` status sent first; plain nRF writes without it are ignored). The voice-assist form `5A F1 LL MM 00…00 RR 01 00…FF` (`U368SendDataToCluster.sendVehicleData`: MM = speedo page 10 ODO / 11 Trip A / 12 Trip B / 13 DTE, RR = reset 2 Trip A / 3 Trip B / 4 DTE) did nothing for brightness; its page/reset meaning is untested |
| `5A 4E` | Navigation control / distance (also low-fuel distance) | 2-byte distance, flags… (TODO: trace full nav packet via `sendAdviseInfoToListener`) |

Connect sequence the official app sends: rider name ×3 → mobile status → location name, ~400 ms apart.

## 5. Bugs in the official app that explain your symptoms

1. **I-button lag / needing to spam it.** When voice assist starts, `voiceAssistStateChange()` runs a
   10-second timer that calls `sendMobileData()` **every 100 ms**, so about 100 BLE writes, each needing
   an acknowledgement. That floods the BLE write queue, so everything else (including the reaction to
   the next press) waits behind it. On top of that, `invokeVoiceAssists()` sets a flag that blocks any new
   trigger for **6 seconds** unless speech recognition reports back. If the assistant was auto-triggered on
   connect and you said nothing, presses during that window are silently ignored.
2. **Voice assist auto-starting on connect.** The trigger is simply "bit 0x04 of byte 11 is set in any
   type-16 frame". There's no edge detection, so any stale or initial frame with that bit set fires it.
   Incoming frames are also dropped entirely until a UI animation has finished (`isAnimationComplete()`),
   which adds more randomness.
3. **Call buttons sometimes doing nothing.** `controlU368PhoneCall` checks whether the **whole** byte 11
   equals exactly `1` (accept) or `2` (reject). Byte 11 is a bitfield shared with the voice-assist bit and
   the location-tag bit, so if any other bit is set, the call press never matches. The fix is to mask the
   low 2 bits.
4. **SMS count is always 0.** `BluetoothUtil.getMissedSMS()` literally `return 0;`.
5. **Likely "999" garbage.** The disconnect frame (`5B 4A 01 00…10 11 12 FF`) is written **without** the
   XOR step, unlike every other frame. The cluster XOR-decodes it anyway, so zero bytes become 0xEB (235),
   for example in the missed-call/SMS slots, which a 2-digit display can't show. This is the most likely
   cause of the 999s. Other contributing bugs: the `5B 4A` overspeed byte and the `onClearSMS` frame are
   filled in on a posted Handler and read before it runs.
6. **Map search hangs.** Maps use Mappls (MapmyIndia) + HERE SDKs with keys fetched at runtime from
   TVS's config (`MMI_MAP_SDK_KEY`, `HERE_MAP_*`). If those keys are expired or rate-limited server-side, search
   spins forever. In our app we can use our own key.

## 6. Still to confirm on the bike

- Actual GATT UUIDs (scan with nRF Connect, or let our app discover them).
- Exact byte-11 values when pressing accept/reject/I button (HCI snoop log).
- Full navigation (turn arrow + distance) frame layout.

## 7. Findings from the first real capture (2026-11-25, ride1, official app, HCI snoop log)

Files (local only, git-ignored): `captures/bugreport_ride1.zip`, `captures/btsnoop_hci.log`, `captures/frames_ride1.csv`
(made by `tools/btsnoop_parse.py`). btsnoop timestamps are 5:30 ahead of local IST. Local times are used below.

**Confirmed by the owner's sequence:**
- Bike → phone: notify on ATT handle `0x001E`. Phone → bike: write on handle `0x0022`. All frames are 20 bytes.
- Frame rate: each of types 0x10/0x11/0x12/0x18/0x19 arrives about every 1.3 s (~4 frames/s total).
- On connect the bike sends **`5B 42 … C4 D3 6A CD 62 D6 …` (not XORed)**, which carries its own MAC (identity/hello), repeated 7×.
- 0x11 b5 = **gear**: 0x02 = 2nd (bike was left in 2nd), 0x00 = neutral, 0x01 = 1st. ✓
- 0x11 b13 telltales: bit1 neutral ✓, bit2 left indicator ✓ (blinks), bit3 right indicator ✓, bit5 (0x10) = engine MIL
  (on with ignition, off once the engine runs), bit6 (0x20) = blinking ABS lamp until the bike moves (>~4 km/h), then off.
- 0x11 b10 bit8 (0x80) toggles in step with the ABS lamp blink.
- 0x11 b3: bit1 (0x01) = clutch lever ✓, bit5 (0x10) = starter button ✓, bit7 (0x40) = side stand down ✓.
- 0x11 b6: bit1 = run/kill switch "run", bit6 (0x20) = starter pressed. b15 bit7 (0x40) = engine running ✓, bit5 (0x10) = side-stand lamp.
- 0x10: b2 speed ✓ (1–6 km/h during the short ride), b16–17 RPM ✓ (~1,500–3,300).
- 0x10 b11 events: **0x03** at 09:04:09 (0.24 s) and 09:04:14 (2 s), around the mode-change step. No b11 change at all
  during the I-button test window (09:06:02–09:06:30). **0x80** at 09:07:07 (after engine off; likely park/location flag).
  **Owner confirmed: the 0x03 events were the MODE button** (Urban→Rain→Urban). So b11 = 0x03 = mode button pressed.
  The ride mode value itself (0x11 b17 = 0x21) never changed, and no other byte changed, so the bike may not broadcast the selected mode.
  **The I button produced no change in any frame** during its test window (short, long, double press). Next test: focused I-button run.

**Official-app behaviour confirmed (bugs):**
- It sends the `5B 4A` status every 2 s. Byte 3 (overspeed limit) **randomly flips between 0x64 and 0x00**, the predicted race bug.
- Voice-assist state byte 16 = **0x20 in the very first status frame after connect** (09:03:36), before any bike button frame
  arrived, and again at 09:03:49–51. **So the auto-wake of the assistant on connect comes from the app itself, not the bike.**
- On connect it sends rider name ×3 (the account's full name). **Owner confirmed the cluster displays it as a greeting "Hi <name>"**,
  so `5B 52` + ASCII (XOR 0xEB) is proven to put text on the display; our builder produces the identical format.
  Exact rendering: app sent "Firstname Lastname", cluster showed **"Hi Firstnamelastname"**. The cluster adds "Hi ", **drops spaces**,
  and seems to normalise letter case. Keep display text short and don't rely on spaces or capitals, status, then location name ("Bengaluru"), then clears text (`5B 4C` zeros).
- Missed calls/SMS slots are always 0 in this session. Signal/battery byte went 0x24 → 0x34 → 0x44 (battery level nibble).

## 8. What the official owner's manual says (reference/TVS-Ronin-Owners-Manual.pdf, local only)

Source: https://www.tvsmotor.com/-/media/Feature/Owners/User-Manual/festive/TVS-Ronin-with-TCS.pdf
- The I button is officially the **"Info switch"** (left handlebar). It accepts/rejects incoming calls, clears notifications on the cluster,
  invokes the voice assistant, and accepts/rejects the nearest-fuel suggestion. It also dismisses service-reminder / low-battery pop-ups.
- **Voice assist = LONG press of the Info switch, and only "when the notification area is displaying the customer window"**
  (the default connected screen with phone signal, battery, unread SMS and missed-call counts). It can also be started from the app icon.
  Voice feedback needs a wired or wireless headset connected.
- **Calls: long press = accept. Press = cancel/reject the incoming-call alert.**
- Fuel assist: hold ~3 s = accept, ~1 s = reject.
- Pairing: long-press Mode → CLOCK SETTING → press Mode once → BT pairing mode. The cluster shows a passkey, which is entered in the app.
  **Only one phone can be paired to a cluster at a time.** On a wrong passkey the cluster shows an error. If there's trouble, cycle the ignition and restart the app.
- Long-pressing Mode reaches: Clock setting → BT Pairing → Illumination setting.
- Caller display: the contact name if saved, otherwise the number. The customer window shows unread SMS and missed calls **"for that particular ride"**.
- Navigation licence "has to be renewed after 5 years" via a dealer (the cluster's nav feature is licensed).

**From the owner (2026-11-25):**
- 5 gears. Recommended upshifts: 1→2 at ~16 km/h, 2→3 at ~24, 3→4 at ~35, 4→5 at ~48.
- Gear top speeds: 1st 42, 2nd 76, 3rd 92, 4th 114 km/h, 5th open. At a ~9,000 rpm limit that's ≈ 214 / 118 / 98 / 79 rpm per km/h;
  2nd, 4th and 5th (≈ 65) agree with the ride captures. 1st is shorter than first assumed (150).
- Three things kill the engine: the key, the kill switch, and the side stand (stand down = engine cut, also while moving).
  So "parked" = speed 0 with the stand down; stand down while moving = emergency (engine just died). Engine off with the stand up
  and speed 0 (kill switch at a signal) is not parked.

## 9. Voice-assist auto-wake: code trail (official app 8.14.3)

- Status byte 16 values (`VoiceAssistConstants`): **32 (0x20) = LISTENING**, 48 = PROCESSING, 64 = RECEIVED, 0 = idle.
- The state is stored in **SharedPreferences** (`pref_voice_assists_state`, set in `BaseVoiceAssistDelegate.onStatus`), and
  `sendMobileData()` reads it back for every 2-second status frame. It's only reset to 0 by `removeCountDownTimer()` / delegate cleanup.
  **Likely bug:** a LISTENING value left over from a previous session gets sent in the first status frame after connect
  (matches capture: 0x20 at 09:03:36, before any bike button frame), so the cluster shows "Listening" without a press.
- For U368, the only code path that *starts* listening is `U368ClusterDataManager.invokeVoiceAssists()` (bike b11 bit 0x04).
  No 0x04 frame appeared in the capture, so the bike did not request it.
- `VoiceRecognitionManager.onError`: if you say nothing (ERROR_NO_MATCH = 7) it **silently restarts listening up to 3 more times**,
  which stretches an unwanted session. Meanwhile `invokeVoiceAssists()` **ignores I-button presses for 6 s**.

## 10. Capture "call1" (2026-11-25 ~10:00, official app): incoming call + single I-button click

Files (local, git-ignored): `captures/bugreport_call1.zip`, `captures/call1/btsnoop_hci.log`, `captures/frames_call1.csv`.
- Connect sequence again: bike `5B 42 …MAC…` hello (plain, also an XORed variant `5B 42 EB… 2F 38 81 26 89 3D …`), app rider name ×3,
  status, location "Bengaluru". This time the first status frame had VA = 0 (no stale-state wake).
- **~10 s after connect the app's own speech recognizer starts LISTENING** (status 32 at 09:59:47.5, preceded by two blank `5B 4C`
  frames from `U368VoiceAssistDelegateNew.sendStatus(32)` → `removeCustomData()`). The bike sent no 0x04 request. The same thing happened
  in ride1 at 09:03:49.9 (~13 s after connect). **So the assistant auto-starts from inside the app shortly after every connect.**
- **Incoming call:** the app sent `5B 43` + the caller's number (ASCII, "+91" stripped) **11 times, every ~0.4 s** (10:00:52–10:00:56).
- **Single short press of the I button → bike sent b11 = 0x02 (REJECT)** at 10:00:55.0, and the call was rejected/disconnected.
  This matches the manual (short press = reject, long press = accept). The bike **held 0x02 for ~5 s** (until 10:01:00.3), so the
  official app calls rejectCall() on every frame in that window. Our app should act once per rising edge.
- So the bike **does** report I-button presses when a call alert is on screen. That supports the theory that it only reports presses
  that mean something on the current screen.
- 10:01:06 b11 = 0x80 again (seen at the end of ride1 too), meaning still unknown.
- Missed calls stayed 0 after the rejected call (the app doesn't count rejected calls as missed).

## 11. Navigation frames (from `TVSApplication.U368sendNavigationData`)

Two frames per update (both XOR-encoded like everything else), ~400 ms apart:

**Frame A, `5A 4E`: turn data**
| Byte | Meaning |
|---|---|
| 0–1 | `5A 4E` |
| 2–3 | Distance to next turn, metres, big-endian, rounded to 50 m (set to 0 for U-turn / rerouting / arrived) |
| 4–5 | Time left to destination, **minutes** |
| 6–8 | Distance left to destination, metres, 24-bit big-endian |
| 9 | **Pictogram id** (turn-arrow icon). 7 = straight, 41 (0x29) = U-turn, 72 (0x48) = rerouting. Otherwise the Mappls (MapmyIndia) maneuver id is passed straight through |
| 10 | Text lines: 1 (≤17 chars) or 2 |
| 11 | 1 |
| 12 | 0 |
| 13 | Alert flag (1 for "no GPS" / "no mobile data") |
| 14 | **State**: 1 = turn coming (<1000 m), 3 = turn LEFT now (<65 m), 4 = turn RIGHT now, 5 = go straight (≥1000 m), 6 = no GPS, 7 = no mobile data, 8 = arrived, 0 = U-turn / rerouting |
| 15–18 | 0 padding |
| 19 | FF |

**Frame B, `5B 4F`: instruction text** (≤17 ASCII chars, zero-padded, FF), e.g. "Turn left", "Go straight", "You Arrived".
Low fuel reuses `5A 4E` with distance and byte 9 = 81 (0x51), byte 10 = 1 (see `sendLowFuelAlert`).

Still unknown: which pictogram ids the cluster draws as which arrows (it has its own icon set). Plan: send `5A 4E` with ids 0–90
from a test tool and photograph the display.

## 12. Capture "ride2" (2026-11-25 13:27–13:37, official app): intercom joined mid-ride, then a call

Files (local, git-ignored): `captures/bugreport_ride2.zip`, `captures/ride2/…/btsnoop_hci.log`, `captures/frames_ride2b.csv`.
Times below are local IST (the frames CSV is 5:30 ahead). Speeds up to 66 km/h; the official app was running Google-Maps-style
navigation the whole ride (`5A 4E` + `5B 4F` every 0.5 s).

| Time | What happened |
|---|---|
| 13:25:41 | Bike LE link up (30 ms → 15 ms → 30 ms interval, slave latency 29, timeout 6 s, same as every connect) |
| 13:30:05 | **Intercom connected** (Classic BT: HFP, A2DP, AVRCP channels opened) at ~30 km/h. **Bike link unaffected.** No music was streaming. |
| 13:32:06.6 | Incoming call (unknown number) starts ringing. Rider slowing, 16 → 2 km/h |
| 13:32:11.1 | Official app sends `5B 43` + number, **4.5 s after the ring started**, then repeats it every ~0.45 s for the whole call (~200 frames) |
| 13:32:16.4 | Call answered (~1–2 km/h) |
| 13:32:16.7 | **Call audio channel (eSCO) to the intercom opens** |
| 13:32:17.75 | **Bike link drops, reason 0x3D (MIC failure)**, i.e. the phone's controller rejected a corrupted encrypted packet ~1 s after eSCO started |
| 13:32:23.8 | Official app reconnects (6 s gap), re-sends rider name `5B 52`, location `5B 5C`, and the caller again |
| 13:33:36.8 | Call ends (~80 s). App sends a burst of duplicate `5B 4A` status frames within ~50 ms (queue flush) |
| 13:36:56 | Engine off (rpm 0); 13:37:03 link timeout (0x08), normal key-off |
| 13:38:52 | Intercom switched off |

Findings:
- **Connecting an intercom does not disturb the bike link. Starting call audio did** (one sample so far; needs a repeat to confirm
  it happens every call). The drop is in the phone's Bluetooth controller (eSCO and the encrypted LE link competing for radio time),
  not something the app causes, so the app's job is to **reconnect fast and restore what was on the display**.
- The official app has **no READ_PHONE_STATE** (`Permission Denial: receiving PHONE_STATE` on MIUI), which is likely why its caller
  frame is 4–5 s late. Ronin Dash uses the phone-state permission, so it should be on time.
- `5B 4A` byte 3 (overspeed limit) still randomly flips 0x64 ↔ 0x00 (§9 race bug confirmed again).
- Earlier the same day (12:23–12:52, bike parked) two connects with a 5 min failed-reconnect gap; not investigated.

**Info (I) button during ride2** (owner says: long-pressed many times while riding for the assistant, and tried to answer the call with it):
- The bike reported a press **only twice** in the whole session: `b11 = 0x04` (assistant) at 13:27:33, standing still, **before
  navigation started**; and `b11 = 0x02` at 13:33:36.8 during the active call, which hung it up (Telecom REQUEST_DISCONNECT at the
  same millisecond). Every other press was **never sent by the bike**, so no app could have reacted to it.
- Navigation frames ran non-stop from 13:28:34 to 13:36:03. That matches the manual: the assistant long-press works only while the
  cluster shows the **customer window**, and a nav turn on screen replaces it. Working theory: **while nav is on the cluster, the
  bike swallows the assistant press.** To confirm: same ride with nav off, then with nav on.
- The call: ringing started 13:32:06.6, but the caller pop-up only reached the bike at 13:32:11.1 (the official app is 4.5 s late),
  and the intercom auto-answered at 13:32:16.4 (its "auto-answer after two rings"). So the bike's call screen was up for only ~5 s,
  and presses before 13:32:11 hit a screen with no call on it. No `0x01` (accept) arrived in that window.
- The official app also starts its own assistant with no press at all, ~14 s after connect (13:25:56, and again 13:27:14): the §10
  auto-wake bug again.

**The "99 glitch"** (owner's name for it): right after the call ended (13:33:37), the cluster showed **99 missed calls and 99 notifications**.
- The phone did **not** send 99. All 3,065 frames the official app wrote in this session are correctly XOR-encoded (no plain/unencoded
  frame, so §5's "unencoded disconnect frame" theory does not explain this one), every write was a confirmed Write Request, and
  every `5B 4A` status said **missed calls 0, SMS 0** before, during and after the call. Nothing the bike sends back holds a count.
- So the **cluster makes the 99 itself**, and it does not simply show the phone's count (the phone kept sending 0 every 2 s).
- Prime suspect: the official app sent the caller frame `5B 43` **~200 times** (every ~0.45 s for the whole 80 s call, plus again
  after the reconnect), and the call was answered by the intercom, so the bike never saw an "accept" press. If the cluster counts
  every caller frame that isn't answered on the bike as a missed call, ~200 frames → shows 99 (its 2-digit maximum).
- **Test to confirm (bike parked, phone Bluetooth off, Mac connected with `tools/ronin_test.py`):** `status calls=0 sms=0`, then
  `repeat 150 caller TEST` (no button press), then `status calls=0 sms=0`, and look at the missed-call count. Then the control:
  `repeat 3 caller TEST`. If 150 gives 99 and 3 gives ~1–3, the theory holds.
- Ronin Dash already sends the caller only 3 times while ringing. Still to add: nothing more once the call is answered anywhere,
  and close the pop-up as soon as the call is answered (OFFHOOK), not only when it ends.

## 13. Bench test with our own app (2026-11-25 17:40, second phone, ignition on, engine off)

Our debug app connected (status frame first), logging every changed frame. Owner pressed, ~5 s apart, with no pop-up on the cluster:
I short, I hold 3 s, I double, Mode ×2, clutch in/out, side stand down/up. Log: `captures/buttons1.log` (git-ignored).

- **I button: one event only**, `0x10` byte 11 = **0x04**, latched for ~5 s (17:41:13 → 17:41:18). Three I-button actions gave
  one event, so with no call or pop-up on screen the bike reports only one kind of press (most likely the 3 s hold, which the
  manual makes the voice-assist press); short and double presses send nothing. In a call alert a short press sends 0x02 (§10).
  The 5 s latch is why spamming it seems to do nothing.
- **Mode button / ride mode: read it from `0x11` byte 17 low nibble** (1 → 3 → 1 = Urban → Rain → Urban). Byte 11 = 0x03
  showed for only one of the two presses, so byte 11 isn't a reliable mode-press signal.
- **Side stand:** down → `0x11` byte 3 = 0x40 and byte 15 = 0x10; up → both 0. Matches §7.
- **Clutch lever: reported** (`0x11` byte 3 = 0x01, engine off too): seen at 17:44:17 for 2.5 s while the owner held it and the app's Clutch lamp lit. The quick pull during the test sequence didn't show up, probably because it fell between status frames (~2–3 per second), so short pulls can be missed.
- **Owner, watching the app's new button lamps (17:50): the bike only reports a HELD press.** I button held ~2–3 s → `0x10`
  byte 11 = 0x04, shown a moment late; quick taps or a double tap → **nothing at all**. Same for the clutch: held → byte 3
  bit 0x01 a moment later; a quick pull → nothing. So with no call or pop-up on screen, the I button is **hold-only**. (During a
  call alert a short press does send 0x02 reject, §10.) Anything we ask the rider to do with I must say **hold**.
- **Brightness read-back** again correct: 2 → 10 → 2 gave byte 17 = 0x21 → 0xA1 → 0x21.
- Unknown: `0x11` byte 10 bit 7 (0x80) and byte 13 bit 5 (0x20) flip together every 1–9 s with nothing touched.

## 14. The exact I-button rules (owner, 2026-11-25 17:48–17:57, our app on the bike; log `captures/app_logs/logs/ride_20261125_17*.csv`, git-ignored)

The bike reports a press **when the button is released**, and only if the hold length is right:

| Situation | Press | `0x10` byte 11 | Result in our app |
|---|---|---|---|
| No call (customer window) | **hold 2–3 s, then let go** | 0x04 (held ~5 s by the bike) | assistant opens (7/7) |
| No call | shorter tap, or held much longer | nothing | — ("garbage") |
| Incoming call | **tap** (any short press) | 0x02 | call rejected (2/2) |
| Incoming call | **hold ~2.5 s, then let go** | 0x01 | call answered (1/1) |
| Incoming call | held too short | 0x02 | rejected |
| Incoming call | held much longer | nothing | keeps ringing |
| Mode button | press | 0x03 (0.2–6 s) | reported for all 12 presses in this session |

- After hanging up and switching off: byte 11 = **0x80** at 17:57:38, then the link dropped 11 s later (status 8, timeout).
  Same as §7 (0x80 after engine off). So **0x80 = the bike is shutting down**: save the trip and clean up now, don't wait for the timeout.
- Our app acts on the change of byte 11 (once per event), which matches "detected on release".
- Caller name went out 3× ~0.7 s apart on each ring and showed on the cluster.

## 15. Fuel gauge and the injection counter (2026-11-25)

- **`0x10` byte 6 is the fuel gauge in bars, not a percent.** It read **9 in every frame of every capture today** (ride1, ride2,
  bench tests), all with a tank filled the day before and the bike's own gauge showing full. The official app reads it as a
  percent (`U368DataParser.getFuelLevelPercentage` = byte 6) → shows "9 %" and fires its low-fuel prompt: **a TVS Connect bug**.
  Our app now shows bars. **Owner confirmed: the gauge has 9 bars, so 9 = full.** Manual p43: 1 bar ≈ 3.2 L left
  (safe level), low-fuel lamp blinks ≈ 2.6 L, 0 bars + steady lamp ≈ 2 L. Those are useful fixed points for our fuel model.
- **`0x18` bytes 16–17 = a 16-bit counter that only runs with the engine** (official name "fuel injection volume"). It freezes
  with the engine off, wraps at 65,536, and rises ~65–70/s at a 1,300 rpm idle and ~260/s at 3,400 rpm / 35 km/h. The rate follows
  RPM much more than throttle (counter ÷ rpm ≈ 0.055–0.077), so it may count injections, not litres. **Lead, not used yet:**
  log it on a real ride between two full-tank fills and compare with pumped litres; if it tracks fuel, it beats our throttle model.
- `0x18` bytes 9–10 = injection time (official: ÷100 × 0.001), not checked yet.
