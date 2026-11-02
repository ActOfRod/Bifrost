#!/usr/bin/env python3
"""Wrap an executable into synthetic Switch containers for intake tests:
  make_fixture.py <exe> <out_dir>
produces exefs.pfs0, game.nsp (PFS0 holding a plaintext NCA3 Program NCA), and game_encrypted.nsp."""
import os, struct, sys

def pfs0(files):
    names = b"".join(n.encode() + b"\0" for n, _ in files)
    names += b"\0" * ((-len(names) - 16 - 24 * len(files)) % 32)
    hdr = b"PFS0" + struct.pack("<III", len(files), len(names), 0)
    off, ents, noff = 0, b"", 0
    for n, d in files:
        ents += struct.pack("<QQII", off, len(d), noff, 0)
        off += len(d); noff += len(n) + 1
    return hdr + ents + names + b"".join(d for _, d in files)

def npdm(name, title_id):
    m = bytearray(0x200)
    m[0:4] = b"META"
    m[0x20:0x20 + len(name)] = name.encode()
    m[0x70:0x74] = struct.pack("<I", 0x100)
    m[0x100:0x104] = b"ACI0"
    m[0x110:0x118] = struct.pack("<Q", title_id)
    return bytes(m)

def nca(exefs, title_id, encrypted=False):
    sec_off = 0xC00
    body = exefs + b"\0" * ((-len(exefs)) % 0x200)
    h = bytearray(0xC00)
    h[0x200:0x204] = b"NCA3" if not encrypted else os.urandom(4)
    h[0x205] = 0
    h[0x210:0x218] = struct.pack("<Q", title_id)
    h[0x240:0x248] = struct.pack("<II", sec_off // 0x200, (sec_off + len(body)) // 0x200)
    fs = 0x400
    h[fs + 2] = 1; h[fs + 3] = 2; h[fs + 4] = 1
    h[fs + 8 + 0x38: fs + 8 + 0x48] = struct.pack("<QQ", 0, len(exefs))
    return bytes(h) + body

exe, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
tid = 0x0100BF0000000000
ex = pfs0([("main", open(exe, "rb").read()), ("main.npdm", npdm("BifrostTest", tid))])
open(os.path.join(out, "exefs.pfs0"), "wb").write(ex)
open(os.path.join(out, "game.nsp"), "wb").write(pfs0([("0123456789abcdef0123456789abcdef.nca", nca(ex, tid))]))
open(os.path.join(out, "game_encrypted.nsp"), "wb").write(pfs0([("0123456789abcdef0123456789abcdef.nca", nca(ex, tid, True))]))
