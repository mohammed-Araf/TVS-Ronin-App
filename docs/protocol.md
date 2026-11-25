# TVS Ronin (U368) cluster: BLE protocol

This describes the Bluetooth Low Energy link between the TVS Ronin's instrument cluster (internal model **U368**) and a
phone. It was worked out by observing the link (Android HCI snoop logs) and then checking each field on a real
motorcycle. Each field is marked:

- **Verified**: tested on the bike.
- **Observed**: seen in captures and consistent, but not tested on purpose.
- **Unconfirmed**: expected from how the official app behaves, not checked yet.

Byte offsets are 0-based. Bit names like "bit 0x04" are masks.

---

## 1. Transport

| Item | Value |
|---|---|
| Service | `5456534d-5647-5341-5342-454e544f5251` |
| Bike → phone (notify) | `0x5354` (16-bit UUID on the Bluetooth base UUID) |
| Phone → bike (write, with response) | `0x5352` |
| Frame size | 20 bytes, both directions |
| Pairing | LE bonding with a 6-digit passkey shown on the cluster. One phone per cluster |
| Frame rate (bike → phone) | Each frame type about every 1.3 s, about 4 frames/s in total |

Enter pairing mode on the cluster: long-press **Mode** → *Clock setting* → press **Mode** → *BT pairing*.

## 2. Encoding

Every frame except the bike's hello (§3.6) is encoded the same way in both directions:

```
plain   = [h0, h1, p2, p3, … p18, 0xFF]
encoded = [h0, h1, p2^0xEB, p3^0xEB, … p18^0xEB, 0xFF]
```

The two header bytes and the final `0xFF` are not changed. There is no key or session secret.

**Checksum (bike → phone):** `(sum(bytes[0..17]) mod 256) + bytes[18] == 255`, computed on the decoded frame.

## 3. Bike → phone frames

The header is `5A <type>`.

### 3.1 Type `0x10`: ride data

| Byte | Field | Status |
|---|---|---|
| 2 | Speed, km/h | Verified |
| 3–5 | Odometer, 24-bit big-endian, ÷10 km | Observed |
| 6 | **Fuel gauge, bars 0–9** (9 = full). Not a percentage, see §6 | Verified |
| 7 | Average speed | Observed |
| 8 | Mileage, km/l | Observed |
| 9 | Top speed | Observed |
| 10 | Throttle, value ÷ 2 = % | Observed |
| 11 | Button / event code (§5) | Verified |
| 12 | 0–60 time ×10 | Unconfirmed |
| 13 | Average mileage | Unconfirmed |
| 16–17 | Engine RPM, 16-bit big-endian | Verified |
| 18 | Checksum | Verified |

### 3.2 Type `0x11`: status and telltales

| Byte | Field | Status |
|---|---|---|
| 2 | Fault bits: engine-speed sensor, gear sensor, side-stand sensor, CAN error, low battery | Unconfirmed |
| 3 | 0x01 clutch lever pulled · 0x10 starter button · 0x40 side stand down | Verified |
| 4 | Service reminder | Unconfirmed |
| 5 | Gear: low nibble (0 = neutral, 1–5) | Verified |
| 6 | 0x01 run/kill switch at "run" · 0x20 starter pressed | Observed |
| 10 | Diagnostic bits (sensors, MIL, ABS). 0x80 toggles with the ABS lamp blink | Observed |
| 13 | Telltales: 0x01 neutral · 0x02 left indicator · 0x04 right indicator · 0x10 engine MIL · 0x20 ABS lamp | Verified |
| 15 | 0x10 side-stand lamp · 0x40 engine running | Verified |
| 17 | High nibble: **display brightness 2–10**. Low nibble: **ride mode** (1 = Urban, 3 = Rain) | Verified |

### 3.3 Type `0x12`: trip

2 = lean angle, 3–4 = cruising range, 6 = acceleration, 7 = torque, 8–9 = trip distance ÷10, 12 = trip mileage.
*Unconfirmed.*

### 3.4 Type `0x18`: engine

| Byte | Field | Status |
|---|---|---|
| 5 | Manifold pressure | Unconfirmed |
| 7 | Intake air temperature, value − 40 °C | Observed |
| 8 | Engine temperature, value − 40 °C | Observed |
| 9–10 | Injection time | Unconfirmed |
| 11 | Battery voltage ÷10 | Observed |
| 16–17 | 16-bit counter that runs only with the engine and wraps at 65,536. About 65–70/s at idle, about 260/s at 3,400 rpm. Tracks RPM closely. It may count injections; its link to fuel used is not yet measured | Observed |

### 3.5 Type `0x19`: trips A/B

5–6 = Trip A distance ÷10, 7 = Trip A mileage, 8–9 = Trip B distance, 10 = Trip B mileage,
11–12 = distance to empty, 13 / 14 = Trip A / B average speed. *Unconfirmed.*

### 3.6 Hello

On connect the cluster sends `5B 42 …` several times, **not** encoded, carrying its own Bluetooth address.

## 4. Phone → bike frames

All frames are encoded (§2) and written with response.

| Header | Purpose | Layout | Status |
|---|---|---|---|
| `5B 4A` | **Phone status**, sent every 2 s | See below | Verified |
| `5B 52` | Rider name / greeting | ASCII ≤ 17, zero-padded. Shown as "Hi \<name\>", with spaces removed | Verified |
| `5B 43` | Incoming caller | ASCII ≤ 17 (name, or number without the country code) | Verified |
| `5B 53` | SMS sender | ASCII ≤ 16 | Unconfirmed |
| `5B 4C` | Free text (alerts, assistant replies) | ASCII ≤ 17. All zeros clears it | Observed |
| `5B 5C` | Location name | ASCII ≤ 17 | Observed |
| `5B 5D` | Weather text | ASCII | Unconfirmed |
| `5A F1` | Settings: display brightness | See below | Verified |
| `5A 4E` + `5B 4F` | Navigation | See §7 | Unconfirmed |

**`5B 4A` phone status** (bytes 2–18):
signal/battery nibbles, overspeed limit, 0, SMS-sent flag, hour, minute, second, clock format (0/1 or 16/17),
**missed calls**, 0, day, month, year (yy), **unread SMS**, **assistant state** (0 idle, 0x20 listening, 0x30 processing,
0x40 received), crash-alert flag, 0.

Other phone frames only take effect while a session is live, so send a status frame first after connecting.

**`5A F1` display brightness:**

```
5A F1 LL 00 00 00 00 00 00 DD MM YY 00 00 01 00 00 00 00 FF      (plain, before encoding)
```

- `LL` = level **2–10**, meaning 20–100 %. It takes effect at once and reads back in `0x11` byte 17.
- `DD MM YY` = today's date.
- Don't send level 1: the cluster accepts it but has no 10 % setting, and its menu misbehaves. Levels 0 and 11 or more are ignored.
- A second layout seen in the official app (`5A F1 LL MM 00 … RR 01 …`, with a speedo page and a trip reset) has no effect on brightness.

Connect sequence: rider name (×3), status, location, about 400 ms apart, then status every 2 s.

## 5. Buttons and events (`0x10` byte 11)

The cluster reports a press **when the button is released**, and only if the hold time fits what is on screen. A quick
tap with nothing on screen is not reported. Each event stays in the frames for a few seconds, so act on the change
(once per event).

| On screen | Press | Byte 11 |
|---|---|---|
| Normal (no pop-up) | **I button**: hold 2–3 s, release | `0x04`, held about 5 s |
| Normal | Shorter or much longer hold | nothing |
| Incoming call | I button: tap | `0x02`, reject |
| Incoming call | I button: hold about 2.5 s, release | `0x01`, accept |
| Incoming call | Much longer hold | nothing |
| Any | **Mode** button | `0x03` |
| Ignition off | none | `0x80`, then the link drops about 10 s later |

The clutch lever (`0x11` byte 3, 0x01) only shows when it is held; a quick pull can fall between frames.

## 6. Fuel gauge

`0x10` byte 6 is the number of bars on the cluster's gauge (0–9). Reading it as a percentage gives a false "9 % fuel"
low-fuel warning with a full tank. From the owner's manual: 1 bar ≈ 3.2 L left; the low-fuel lamp blinks at about 2.6 L;
0 bars with a steady lamp ≈ 2 L.

## 7. Navigation (unconfirmed)

There are two frames per update, about 400 ms apart.

**`5A 4E`: turn data**

| Byte | Field |
|---|---|
| 2–3 | Distance to next turn, m (rounded to 50 m) |
| 4–5 | Time to destination, minutes |
| 6–8 | Distance to destination, m, 24-bit |
| 9 | Pictogram id (7 straight, 0x29 U-turn, 0x48 rerouting; others are map-provider maneuver ids) |
| 10 | Text lines (1 or 2) |
| 11 | 1 |
| 13 | Alert flag (no GPS / no data) |
| 14 | State: 1 turn ahead · 3 turn left now · 4 turn right now · 5 straight · 6 no GPS · 7 no data · 8 arrived · 0 U-turn / rerouting |

**`5B 4F`: instruction text**, ASCII ≤ 17.

Still to map: which pictogram ids the cluster draws as which arrows.

## 8. Known issues on the cluster side

- **"99" missed calls:** after a long call answered away from the bike, the cluster has shown 99 missed calls while the phone
  reported 0. The leading theory is that it counts each unanswered caller frame. A client should send the caller frame only a
  few times and stop once the call is answered.
- **Link drop when call audio starts:** with a Bluetooth intercom, the LE link dropped once about 1 s after call audio began
  (reason 0x3D). This came from the phone's Bluetooth controller, not the app. Reconnect fast and restore the display.
