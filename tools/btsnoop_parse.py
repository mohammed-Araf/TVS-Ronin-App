#!/usr/bin/env python3
"""
Extract Ronin cluster traffic from an Android btsnoop_hci.log.

    .venv/bin/python tools/btsnoop_parse.py btsnoop_hci.log > frames.csv

Prints CSV: time, dir (RX = bike->phone notify, TX = phone->bike write), att_handle, raw, decoded, checksum_ok
"""
import struct
import sys
import datetime as dt

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from ronin_test import xor_frame, checksum_ok  # noqa: E402

EPOCH_DELTA_US = 0x00DCDDB30F2F8000  # btsnoop epoch (year 0) -> unix epoch


def records(path):
    with open(path, "rb") as f:
        hdr = f.read(16)
        assert hdr[:8] == b"btsnoop\0", "not a btsnoop file"
        while True:
            h = f.read(24)
            if len(h) < 24:
                return
            orig, incl, flags, drops, ts = struct.unpack(">IIIIq", h)
            data = f.read(incl)
            yield flags, ts, data


def main(path):
    print("time,dir,handle,raw,decoded,checksum_ok")
    for flags, ts, d in records(path):
        if not d or d[0] != 0x02 or len(d) < 9:  # H4 ACL only
            continue
        acl_len = struct.unpack("<H", d[3:5])[0]
        l2_len, cid = struct.unpack("<HH", d[5:9])
        if cid != 0x0004:  # ATT
            continue
        att = d[9:9 + l2_len]
        if len(att) < 3:
            continue
        op = att[0]
        handle = struct.unpack("<H", att[1:3])[0]
        val = att[3:]
        received = bool(flags & 1)
        if op == 0x1B and received:
            direction = "RX"
        elif op in (0x12, 0x52) and not received:
            direction = "TX"
        else:
            continue
        if len(val) != 20:
            continue
        t = dt.datetime.fromtimestamp((ts - EPOCH_DELTA_US) / 1e6).strftime("%H:%M:%S.%f")[:-3]
        dec = xor_frame(val)
        print(f"{t},{direction},0x{handle:04X},{val.hex(' ').upper()},{dec.hex(' ').upper()},{checksum_ok(dec)}")


if __name__ == "__main__":
    main(sys.argv[1])
