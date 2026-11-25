#!/usr/bin/env python3
"""
Reference design for Ronin Dash's bike-data guard (to be ported 1:1 to Kotlin, DataGuard.kt).

A frame can pass the checksum and still carry a wrong value (a bug in the cluster, a corrupted read on the bike's
CAN side, a frame type we've decoded wrongly). The checksum only proves the radio didn't damage it.
So every field goes through:

  1. Range:   physically possible for this bike? (never accepted if not, no matter how often it repeats)
  2. Rate:    could it have changed this much since the last good value? (physics, not "typical" riding)
  3. Confirm: a value that fails the rate check is held as "suspect". If the next 2 readings agree with it,
              it was real (e.g. after a reconnect or a refuel) and is accepted. Otherwise it was a glitch.
  4. Hold:    while a value is rejected, the last good one stays; after 3 s with no good value the field
              becomes unknown ("–"), never a made-up or stale number.

Limits were checked against every real frame captured so far (three sessions: two rides and a call), including a real
neutral rev to 8,726 rpm and engine temperature reaching 134 °C on a hot ride, so they don't reject real data.

    .venv/bin/python tools/data_guard.py              # replay real captures + glitch-injection test
"""
import csv
import glob
import datetime as dt
import random
import sys

# field: (min, max, max change per second, tolerance for "the next readings agree")
LIMITS = {
    "speed":   (0, 180, 40, 6),          # km/h. Real max seen 12 km/h/s; hard braking ~35 km/h/s
    "rpm":     (0, 11000, 8000, 1500),   # real max change 5,000 rpm/s (neutral rev to 8,726); limiter ~10k
    "fuel":    (0, 100, 1, 3),           # %. Any rise needs confirming (a real refuel stays risen)
    "temp":    (-30, 160, 3, 4),         # engine °C. Real: smooth 28 → 134 over an hour
    "intake":  (-30, 90, 3, 4),          # °C
    "batt":    (6.0, 15.8, 1, .8),       # V. Drops: only while cranking. Rises: up to +4.5 V (charging starts)
    "gear":    (0, 5, None, 0),          # Ronin: 5 speeds. At most 2 gears per reading (fast double shift)
    "odo":     (0, 999999, None, 2),     # km, 1 km resolution on this cluster; checked separately
    "range":   (0, 999, 1, 15),          # km (falls ~1 km per km ridden; a refuel jump gets confirmed)
}
# RPM per km/h in gear, clutch out (median from a real ride). Checked from 3rd gear and 20 km/h up, where it's tight.
# In the app this table is learned per bike from its own rides (other sprockets/tyres), these are the starting values.
RATIO = {1: (112, 400), 2: (100, 200), 3: (70, 150), 4: (62, 115), 5: (55, 76)}   # rpm per km/h (real min/max seen + margin)


# Top speed in each gear (42 / 76 / 92 / 114 km/h at the ~9,000 rpm limit; 5th is open). A gear reading at a speed
# well above that gear's top can't be right, whatever the RPM says (catches a wrong gear while the RPM reading is missing).
GEAR_TOP = {1: 42, 2: 76, 3: 92, 4: 114}


def ratio_fits(gear, r):
    """Gear and speed/RPM readings arrive at different moments, so mid-shift the ratio can match the next gear.
    Accept the claimed gear or a neighbour; a wrong gear two or more away is still caught."""
    return any(RATIO[g][0] <= r <= RATIO[g][1] for g in (gear - 1, gear, gear + 1) if g in RATIO)
CONFIRM = 2          # agreeing readings needed to accept a suspect value
STALE_S = 3.0        # no good value for this long -> unknown
GAP_RESET_S = 10.0   # after a gap (reconnect), the first in-range value is trusted


class Field:
    def __init__(self, name):
        self.name = name
        self.good = None        # last accepted value
        self.t_good = None
        self.hist = []          # earlier accepted values [(v, t)], to undo outliers that looked possible
        self.suspect = []       # recent readings that failed the rate check
        self.provisional = False   # first value after a gap: not trusted until the next one agrees
        self.rejected = 0
        self.accepted = 0

    def feed(self, v, t, ctx):
        lo, hi, rate, tol = LIMITS[self.name]
        if v is None or not (lo <= v <= hi):
            self.rejected += 1
            return self.value(t), "out of range"
        if self.good is None or t - self.t_good > GAP_RESET_S:
            self.hist = []
            r = self._accept(v, t)
            self.provisional = True
            return r
        dt_s = max(t - self.t_good, .05)
        if self.provisional:      # the reading after a gap decides; two that agree end the provisional state
            agrees = abs(v - self.good) <= (rate if rate is not None else 1) * dt_s + max(tol, 1)
            r = self._accept(v, t)
            self.provisional = not agrees
            return r
        ok = True
        if self.name == "odo":
            ok = self.good <= v <= self.good + 1 + 180 * dt_s / 3600 + 1   # never backwards, never faster than 180 km/h
        elif self.name == "fuel":
            ok = self.good - (rate * dt_s + tol) <= v <= self.good + tol
        elif self.name == "gear":
            ok = abs(v - self.good) <= 2 and (v == 0) == bool(ctx.get("neutral_lamp", v == 0))
            sp, rpm = ctx.get("speed") or 0, ctx.get("rpm")
            if ok and v in GEAR_TOP and sp > GEAR_TOP[v] + 8 and not ctx.get("clutch"):
                ok = False   # e.g. "1st gear" at 70 km/h
            if ok and sp >= 20 and rpm and not ctx.get("clutch") and v > 0:
                r = rpm / sp
                ok = ratio_fits(v, r)
        elif self.name == "batt" and ctx.get("cranking"):
            ok = v >= 7.0
        elif self.name == "batt":
            ok = (v - self.good <= 4.5) if v >= self.good else (ctx.get("cranking") or self.good - v <= rate * dt_s + tol)
        else:
            ok = abs(v - self.good) <= rate * dt_s + tol
        if ok and self.name == "rpm" and v - self.good > 2500 and (ctx.get("throttle") or 0) < 3:
            ok = False   # RPM only climbs fast when the throttle is open (real 8,726 rpm rev had 44 % throttle)
        if ok and self.name == "rpm" and ctx.get("gear") in RATIO and (ctx.get("speed") or 0) >= 20 and not ctx.get("clutch"):
            ok = ratio_fits(ctx["gear"], v / ctx["speed"])
        if ok:
            return self._accept(v, t)
        # failed the rate check. If it agrees with a value accepted in the last few seconds (before the recent
        # change), the recent change was an outlier that happened to look possible: go straight back.
        for pv, pt in self.hist:
            if t - pt <= 15 and (abs(v - pv) <= 2 if self.name == "gear" else
                                abs(v - pv) <= (rate if rate is not None else 180 / 3600) * (t - pt) + tol):
                return self._accept(v, t, confirmed=True)
        # otherwise: real only if it repeats
        self.suspect = [(s, ts) for s, ts in self.suspect if abs(s - v) <= tol and t - ts <= 5] + [(v, t)]
        if len(self.suspect) > CONFIRM:
            return self._accept(v, t, confirmed=True)
        self.rejected += 1
        return self.value(t), "jump"

    def _accept(self, v, t, confirmed=False):
        if self.good is not None and v != self.good:
            self.hist = (self.hist + [(self.good, self.t_good)])[-4:]
        self.good, self.t_good, self.suspect = v, t, []
        self.accepted += 1
        return v, "confirmed" if confirmed else "ok"

    def value(self, t):
        return self.good if self.good is not None and t - self.t_good <= STALE_S else None


def decode(d):
    """Fields from one decoded 20-byte frame (same layout as Protocol.kt BikeState.update)."""
    if d[1] == 0x10:
        return {"speed": d[2], "odo": ((d[3] << 16) | (d[4] << 8) | d[5]) / 10, "fuel": d[6], "rpm": (d[16] << 8) | d[17]}
    if d[1] == 0x11:
        return {"gear": d[5] & 0x0F}
    if d[1] == 0x18:
        return {"intake": d[7] - 40, "temp": d[8] - 40, "batt": d[11] / 10}
    if d[1] == 0x12:
        r = (d[3] << 8) | d[4]
        return {"range": r - 65536 if r > 32767 else r}
    return {}


def checksum_fix(d):
    d = bytearray(d)
    d[18] = (255 - sum(d[:18]) % 256) % 256
    return bytes(d)


def load(path):
    out = []
    for r in csv.DictReader(open(path)):
        if r.get("dir") != "RX":
            continue
        d = bytes.fromhex(r["decoded"].replace(" ", ""))
        if len(d) == 20 and d[0] == 0x5A and r.get("checksum_ok") == "True":
            t = dt.datetime.strptime(r["time"], "%H:%M:%S.%f")
            out.append((t.hour * 3600 + t.minute * 60 + t.second + t.microsecond / 1e6, d))
    return out


def run(frames, glitch_rate=0.0, seed=1):
    rnd = random.Random(seed)
    F = {k: Field(k) for k in LIMITS}
    stats = {"injected": 0, "bad_shown": 0, "real_rejected": 0, "max_err": {}, "detail": [],
             "sustained_real_rejected": 0, "sustained_wrong": 0, "impossible_shown": 0}
    run_rej, run_wrong = {}, {}
    truth = {}
    clutch = False
    thr = [0, 0, 0]
    neutral = starter = False
    starter_t = -99
    for t, d in frames:
        if d[1] == 0x10:
            thr = thr[1:] + [d[10] // 2]   # throttle %, last 3 readings (it lags the RPM a little)
        if d[1] == 0x11:
            clutch = bool(d[3] & 1) or bool(d[13] & 1)   # clutch pulled or neutral: no ratio check
            neutral = bool(d[13] & 1)
            if (d[3] & 0x10) or (d[6] & 0x20):           # starter button: battery may dip hard
                starter_t = t
        real = decode(d)
        if not real:
            continue
        fed = dict(real)
        hit = None
        if glitch_rate and rnd.random() < glitch_rate:
            # a wrong value that still passes the checksum (the cluster itself sent it)
            hit = rnd.choice(list(real))
            lo, hi = LIMITS[hit][:2]
            kind = rnd.random()
            if kind < .4:
                fed[hit] = rnd.choice([65535, 0xFFFFFF / 10, 255, 900000, -1])     # nonsense
            elif kind < .8:
                fed[hit] = real[hit] + rnd.choice([-1, 1]) * (hi - lo) * rnd.uniform(.3, .9)  # big plausible-looking jump
            else:
                fed[hit] = lo if real[hit] != lo else hi                                 # snaps to a limit
            stats["injected"] += 1
        if hit in ("speed", "rpm", "fuel", "gear", "temp", "intake", "range"):
            fed[hit] = int(round(fed[hit]))
        for k in sorted(fed, key=lambda k: k != "speed"):   # speed first: the RPM check uses it
            v = fed[k]
            ctx = {"speed": F["speed"].value(t), "gear": F["gear"].value(t), "clutch": clutch, "throttle": max(thr),
                   "cranking": t - starter_t < 6, "neutral_lamp": neutral, "rpm": F["rpm"].value(t)}
            shown, why = F[k].feed(v, t, ctx)
            truth[k] = real[k]
            rej = k != hit and why not in ("ok", "confirmed")
            run_rej[k] = run_rej.get(k, 0) + 1 if rej else 0
            if run_rej[k] == 2:
                stats["sustained_real_rejected"] += 1
            if shown is not None and not (LIMITS[k][0] <= shown <= LIMITS[k][1]):
                stats["impossible_shown"] += 1
            if rej:
                stats["real_rejected"] += 1
                stats["detail"].append(("real rejected", k, real[k], why, round(t, 1)))
            lo, hi, rate, tol = LIMITS[k]
            wrong = shown is not None and abs(shown - real[k]) > max(tol * 3, (hi - lo) * .1)
            # failure = the bike is sending the REAL value again, but we keep showing a wrong one
            run_wrong[k] = run_wrong.get(k, 0) + 1 if (wrong and k != hit) else 0
            if run_wrong[k] == 2:
                stats["sustained_wrong"] += 1
                stats["detail"].append(("sustained", k, real[k], shown, why, round(t, 1)))
            if shown is not None and k == hit:
                err = abs(shown - real[k])
                stats["max_err"][k] = max(stats["max_err"].get(k, 0), err)
                if err > max(tol * 3, (hi - lo) * .1) and why in ("ok", "confirmed"):
                    stats["bad_shown"] += 1
                    stats["detail"].append(("leak", k, round(real[k], 1), round(fed[k], 1), why, dict(speed=truth.get("speed"))))
    return stats, F


if __name__ == "__main__":
    paths = sys.argv[1:] or sorted(glob.glob("captures/frames_*.csv"))
    ok = True
    tot = {"inj": 0, "one": 0}
    for p in paths:
        frames = load(p)
        s, F = run(frames)
        print(f"{p}: {len(frames)} real frames")
        print(f"   real data: one-reading spikes smoothed {s['real_rejected']}, real values held back 2+ readings: {s['sustained_real_rejected']}")
        ok &= s["sustained_real_rejected"] == 0
        for seed in range(1, 21):   # 20 different random glitch patterns per capture
            g, _ = run(frames, glitch_rate=.05, seed=seed)
            tot["inj"] += g["injected"]; tot["one"] += g["bad_shown"]
            ok &= g["impossible_shown"] == 0 and g["sustained_wrong"] == 0
            if g["impossible_shown"] or g["sustained_wrong"]:
                print(f"   seed {seed}: impossible shown {g['impossible_shown']}, wrong for 2+ readings {g['sustained_wrong']}")
    print(f"glitch test: {tot['inj']} wrong values injected (each passes the checksum). Impossible values shown: 0 required.")
    print(f"   wrong but physically possible, let through for one reading (~1 s): {tot['one']} ({tot['one'] / max(1, tot['inj']) * 100:.1f}%)")
    print(f"   wrong value kept after the bike sent the real one again (2+ readings): must be 0")
    print("PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)
