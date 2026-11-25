#!/usr/bin/env python3
"""
Ronin test console: connect to a TVS Ronin (U368) cluster over BLE from a Mac,
watch live decoded data, and send test messages to the display.

Usage (from the project folder):
    .venv/bin/python tools/ronin_test.py              # scan, connect, watch changes
    .venv/bin/python tools/ronin_test.py --all        # print every frame, not just changes
    .venv/bin/python tools/ronin_test.py --selftest   # offline check of the decoder

While connected, type commands (then Enter):
    caller <text>        show an incoming caller name/number
    sms <text>           show an SMS sender
    text <text>          free text message (max 17 chars)
    name <text>          rider name greeting
    loc <text>           location name
    status [calls=N] [sms=N] [va=N]   send the mobile status frame (clock, missed counts)
    raw <hex bytes>      send a raw 20-byte PLAIN frame (it gets XOR-encoded for you)
    repeat N <command>   send a caller/sms/text/name/loc/status/raw command N times, 0.45 s apart
                         (how the official app spams the caller; e.g. "repeat 150 caller TEST")
    mark <note>          write a note into the log (e.g. "pressing I button now")
    all / changes        switch print mode
    quit

Only test with the bike parked. Protocol notes: PROTOCOL.md.
"""
import argparse
import asyncio
import csv
import datetime as dt
import os
import sys

SERVICE_UUID = "5456534d-5647-5341-5342-454e544f5251"
RX_UUID = "00005354-0000-1000-8000-00805f9b34fb"  # bike -> phone (notify)
TX_UUID = "00005352-0000-1000-8000-00805f9b34fb"  # phone -> bike (write)
XOR_KEY = 0xEB

# ---------------------------------------------------------------- codec

def xor_frame(frame: bytes) -> bytes:
    """Encode/decode (same operation): XOR every byte except the first two and the last."""
    b = bytearray(frame)
    for i in range(2, len(b) - 1):
        b[i] ^= XOR_KEY
    return bytes(b)


def checksum_ok(f: bytes) -> bool:
    if len(f) < 4:
        return False
    s = sum(f[:-2]) % 256
    return s + f[-2] == 255


def bit(v: int, pos: int) -> int:
    """1-indexed bit, same convention as the official app."""
    return (v >> (pos - 1)) & 1


def s16(hi, lo):
    return int.from_bytes(bytes([hi, lo]), "big", signed=True)


def parse(f: bytes) -> dict:
    t = f[1]
    d = {}
    if t == 0x10:
        b11 = f[11]
        d = {
            "type": "0x10 live",
            "speed_kmh": f[2],
            "odometer_km": int.from_bytes(f[3:6], "big", signed=True) / 10,
            "fuel_pct": f[6],
            "avg_speed": f[7],
            "mileage_kmpl": f[8],
            "top_speed": f[9],
            "throttle_pct": f[10] // 2,
            "byte11_buttons": f"0x{b11:02X} ({b11:08b})",
            "I_button_va_bit": bool(b11 & 0x04),
            "call_code(low2bits)": b11 & 0x03,
            "location_tag": bool(b11 & 0x80),
            "rpm": s16(f[16], f[17]),
        }
    elif t == 0x11:
        d = {
            "type": "0x11 status",
            "faults_b2": f"{f[2]:08b}",
            "clutch": bit(f[3], 1), "brake": bit(f[3], 2), "estart_sw": bit(f[3], 5),
            "side_stand": bit(f[3], 7),
            "service_reminder": f[4],
            "gear?_b5": f"0x{f[5]:02X}",
            "kill_switch": bit(f[6], 1),
            "speedo_sw_ver": f[7],
            "vehicle_model": f[9],
            "diag_b10": f"{f[10]:08b}",
            "b11": f[11],
            "b13_telltales": f"{f[13]:08b}",
            "neutral": bit(f[13], 1), "left_ind": bit(f[13], 2), "right_ind": bit(f[13], 3),
            "high_beam": bit(f[13], 4),
            "b14": f[14],
            "engine_started": bit(f[15], 7), "general_warn": bit(f[15], 6),
            "side_stand_telltale": bit(f[15], 5),
            "illumination": (f[17] & 0xF0) >> 4,
            "ride_mode": f[17] & 0x0F,
        }
    elif t == 0x12:
        d = {
            "type": "0x12 trip",
            "lean_angle": f[2],
            "cruising_range": s16(f[3], f[4]),
            "acceleration": int.from_bytes(f[6:7], "big", signed=True),
            "torque": f[7],
            "trip_distance_km": s16(f[8], f[9]) / 10,
            "trip_mileage": f[12],
        }
    elif t == 0x18:
        d = {
            "type": "0x18 engine",
            "map": f[5],
            "intake_air_C": f[7] - 40,
            "engine_temp_C": f[8] - 40,
            "battery_V": f[11] / 10,
        }
    elif t == 0x19:
        d = {
            "type": "0x19 trips/DTE",
            "tripA_km": s16(f[5], f[6]) / 10,
            "tripA_mileage": f[7],
            "tripB_km": s16(f[8], f[9]) / 10,
            "tripB_mileage": f[10],
            "range_DTE_km": s16(f[11], f[12]),
            "tripA_avg_speed": f[13],
            "tripB_avg_speed": f[14],
        }
    elif t == 0x16:
        d = {"type": "0x16 lap", "lap_trigger": f[10], "lap_no": f[5]}
    else:
        d = {"type": f"0x{t:02X} unknown"}
    return d

# ---------------------------------------------------------------- frame builders (plain, before XOR)

def text_frame(header: int, text: str, maxlen: int = 17) -> bytes:
    body = text.encode("ascii", "replace")[:maxlen]
    return bytes([0x5B, header]) + body + bytes(19 - 2 - len(body)) + b"\xff"


def status_frame(calls=0, sms=0, va=0, signal=4, batt=4, overspeed=120) -> bytes:
    now = dt.datetime.now()
    sigbatt = int(f"{signal}{batt}", 16)  # app concatenates digits and parses as hex
    hour, ampm = now.hour, (16 if now.hour < 12 else 17)  # 24h format flags
    f = [0x5B, 0x4A, sigbatt, overspeed, 0, 0, hour, now.minute, now.second, ampm,
         min(calls, 99), 0, now.day, now.month, now.year % 100, min(sms, 99), va, 0, 0, 0xFF]
    return bytes(f)

# ---------------------------------------------------------------- BLE

def hexs(b: bytes) -> str:
    return " ".join(f"{x:02X}" for x in b)


class Console:
    def __init__(self, show_all: bool, log_path: str):
        self.show_all = show_all
        self.last = {}
        self.log_f = open(log_path, "a", newline="")
        self.log = csv.writer(self.log_f)
        self.log.writerow(["time", "dir", "raw", "decoded", "checksum_ok", "note"])
        print(f"Logging every frame to {log_path}")

    def on_rx(self, _sender, data: bytearray):
        raw = bytes(data)
        f = xor_frame(raw)
        ok = checksum_ok(f)
        ts = dt.datetime.now().strftime("%H:%M:%S.%f")[:-3]
        self.log.writerow([ts, "RX", hexs(raw), hexs(f), ok, ""])
        self.log_f.flush()
        t = f[1] if len(f) > 1 else None
        prev = self.last.get(t)
        if not self.show_all and prev == f:
            return
        changed = [i for i in range(len(f)) if prev is None or i >= len(prev) or prev[i] != f[i]]
        self.last[t] = f
        mark = "" if ok else "  !! BAD CHECKSUM"
        print(f"\n[{ts}] {hexs(f)}{mark}")
        if prev is not None:
            print("          changed bytes: " + ", ".join(str(i) for i in changed))
        if len(f) == 20:
            fields = parse(f)
            print("          " + "  ".join(f"{k}={v}" for k, v in fields.items()))

    def note(self, text):
        self.log.writerow([dt.datetime.now().strftime("%H:%M:%S.%f")[:-3], "NOTE", "", "", "", text])
        self.log_f.flush()


async def find_bike(name_prefix: str):
    from bleak import BleakScanner
    print(f"Scanning for a bike whose name starts with '{name_prefix}' (ignition must be ON)...")
    dev = await BleakScanner.find_device_by_filter(
        lambda d, ad: (d.name or ad.local_name or "").startswith(name_prefix), timeout=20.0)
    return dev


async def run(args):
    from bleak import BleakClient

    dev = await find_bike(args.name)
    if dev is None:
        print("Bike not found. Is the ignition on, and is your phone's Bluetooth OFF (the bike allows one connection)?")
        return
    print(f"Found {dev.name} ({dev.address}). Connecting...")

    os.makedirs("logs", exist_ok=True)
    log_path = os.path.join("logs", dt.datetime.now().strftime("ronin_%Y%m%d_%H%M%S.csv"))
    con = Console(args.all, log_path)

    async with BleakClient(dev, timeout=30.0) as client:
        print("Connected. If macOS asks to pair, enter the 6-digit code shown on the bike's display.")
        svc = client.services.get_service(SERVICE_UUID)
        if svc is None:
            print("Custom TVS service not found! Services seen:")
            for s in client.services:
                print("  ", s.uuid)
            return
        for c in svc.characteristics:
            print(f"  char {c.uuid}  {c.properties}")

        # macOS only pairs when the bike demands encryption for something we touch.
        # Read everything readable so a protected item triggers the pairing popup.
        print("\nReading all readable items (this is what triggers the macOS pairing popup)...")
        for s in client.services:
            for c in s.characteristics:
                if "read" in c.properties:
                    try:
                        v = await client.read_gatt_char(c)
                        print(f"  read {c.uuid}: {bytes(v)!r}")
                    except Exception as e:
                        print(f"  read {c.uuid}: ERROR {e}")
                for dsc in c.descriptors:
                    try:
                        v = await client.read_gatt_descriptor(dsc.handle)
                        print(f"  desc {dsc.uuid} of {c.uuid}: {bytes(v)!r}")
                    except Exception as e:
                        print(f"  desc {dsc.uuid} of {c.uuid}: ERROR {e}")
        print()
        await client.start_notify(RX_UUID, con.on_rx)
        print("Listening. Type 'help' for commands.\n")

        loop = asyncio.get_running_loop()
        while client.is_connected:
            line = (await loop.run_in_executor(None, sys.stdin.readline))
            if not line:
                break
            line = line.strip()
            if not line:
                continue
            cmd, _, rest = line.partition(" ")
            cmd = cmd.lower()
            plain = None
            times = 1
            if cmd == "repeat":
                n, _, inner = rest.partition(" ")
                if not n.isdigit() or not inner:
                    print("usage: repeat N <command>"); continue
                times = max(1, min(int(n), 500))
                cmd, _, rest = inner.strip().partition(" ")
                cmd = cmd.lower()
                if cmd not in ("caller", "sms", "text", "name", "loc", "status", "raw"):
                    print("repeat works with caller/sms/text/name/loc/status/raw"); continue
            if cmd in ("quit", "exit", "q"):
                break
            elif cmd == "help":
                print(__doc__)
            elif cmd == "all":
                con.show_all = True; print("Printing every frame.")
            elif cmd == "changes":
                con.show_all = False; print("Printing only changed frames.")
            elif cmd == "mark":
                con.note(rest); print(f"noted: {rest}")
            elif cmd == "caller":
                plain = text_frame(0x43, rest)
            elif cmd == "sms":
                plain = text_frame(0x53, rest, 16)
            elif cmd == "text":
                plain = text_frame(0x4C, rest)
            elif cmd == "name":
                plain = text_frame(0x52, rest)
            elif cmd == "loc":
                plain = text_frame(0x5C, rest)
            elif cmd == "status":
                kw = dict(p.split("=", 1) for p in rest.split() if "=" in p)
                plain = status_frame(calls=int(kw.get("calls", 0)), sms=int(kw.get("sms", 0)),
                                     va=int(kw.get("va", 0)))
            elif cmd == "raw":
                try:
                    plain = bytes.fromhex(rest)
                except ValueError:
                    print("bad hex"); continue
                if len(plain) != 20 or plain[-1] != 0xFF:
                    print("raw frame must be 20 bytes ending in FF"); continue
            else:
                print("unknown command, type 'help'")
                continue
            if plain is not None:
                enc = xor_frame(plain)
                for k in range(times):
                    if not client.is_connected:
                        break
                    await client.write_gatt_char(TX_UUID, enc, response=True)
                    con.log.writerow([dt.datetime.now().strftime("%H:%M:%S.%f")[:-3], "TX", hexs(enc), hexs(plain), "", line])
                    con.log_f.flush()
                    if times > 1:
                        print(f"\rsent {k + 1}/{times}", end="", flush=True)
                        await asyncio.sleep(0.45)
                print(f"\nsent  plain: {hexs(plain)}" if times > 1 else f"sent  plain: {hexs(plain)}")
        try:
            await client.stop_notify(RX_UUID)
        except Exception:
            pass
    print("Disconnected. Log saved to", log_path)


def selftest():
    captured = bytes.fromhex("5A11EBABEAE9EBFAEB2FEBEBEBEBE9FBEBCAA2FF")
    f = xor_frame(captured)
    print("captured:", hexs(captured))
    print("decoded: ", hexs(f))
    print("checksum ok:", checksum_ok(f))
    print("fields:  ", parse(f))
    assert checksum_ok(f)
    assert xor_frame(f) == captured
    for fr in (text_frame(0x43, "9876543210"), text_frame(0x4C, "HELLO RONIN"), status_frame(calls=3, sms=1)):
        assert len(fr) == 20 and fr[-1] == 0xFF, fr
        print("built:   ", hexs(fr), "-> on air:", hexs(xor_frame(fr)))
    print("SELFTEST PASSED")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="TVS Ronin BLE test console")
    ap.add_argument("--name", default="TVS", help="bike BLE name prefix (default: TVS)")
    ap.add_argument("--all", action="store_true", help="print every frame, not only changes")
    ap.add_argument("--selftest", action="store_true", help="offline decoder check, no Bluetooth")
    a = ap.parse_args()
    if a.selftest:
        selftest()
    else:
        try:
            asyncio.run(run(a))
        except KeyboardInterrupt:
            print("\nStopped.")
