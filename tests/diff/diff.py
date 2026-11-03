#!/usr/bin/env python3
"""Differential test: lift random AArch64 instructions to C with Bifrost and compare one-step
execution against Unicorn. Usage: diff.py [--count N] [--seed S] [class ...]
classes: dp_imm dp_reg ldst simd fp (default: all)."""
import argparse, ctypes, os, random, struct, subprocess, sys, tempfile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import *

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BIFROST = os.environ.get("BIFROST", os.path.join(ROOT, "target", "debug", "bifrost"))
MEM_BASE, MEM_SIZE, CODE = 0x0FF00000, 0x300000, 0x10080000
CLASSES = {
    "dp_imm": (0x1C000000, 0x10000000),
    "dp_reg": (0x0E000000, 0x0A000000),
    "ldst": (0x0A000000, 0x08000000),
    "simd": (0x1E000000, 0x0E000000),
    "fp": (0x5F000000, 0x1E000000),
}
SKIP = ("ldx", "stx", "ldax", "stlx", "ldlar", "stllr", "ldapr", "cas", "swp", "ldadd", "ldclr", "ldeor",
        "ldset", "ldsmax", "ldsmin", "ldumax", "ldumin", "stadd", "stclr", "steor", "stset", "stsmax",
        "stsmin", "stumax", "stumin", "ldg", "stg", "st2g", "stzg", "irg", "addg", "subg", "gmi", "ldraa", "ldrab")
STUB = r"""
#define _GNU_SOURCE
#include <sys/mman.h>
#include "bf_cpu.h"
void bf_call(BfCpu *c) { (void)c; }
void bf_dispatch_until(BfCpu *c, uint64_t r) { (void)c; (void)r; }
void bf_svc(BfCpu *c, uint32_t i, uint64_t pc) { (void)c; (void)i; (void)pc; }
void bf_brk(BfCpu *c, uint32_t i, uint64_t pc) { (void)c; (void)i; (void)pc; }
void bf_unimpl(BfCpu *c, uint64_t pc, uint32_t w) { (void)c; (void)pc; (void)w; }
uint64_t bf_ticks(void) { return 0; }
void bf_yield(void) {}
void *bf_test_mem(void) {  // guest addresses are host addresses, so the test window lives at MEM_BASE
    void *p = mmap((void *)0x%xULL, 0x%x, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
    return p == MAP_FAILED ? NULL : p;
}
size_t bf_test_cpu_size(void) { return sizeof(BfCpu); }
size_t bf_test_v_off(void) { return offsetof(BfCpu, v); }
""" % (MEM_BASE, MEM_SIZE)

XREGS = [UC_ARM64_REG_X0 + k for k in range(29)] + [UC_ARM64_REG_X29, UC_ARM64_REG_X30]

def rand64(r):
    k = r.random()
    if k < 0.15: return r.choice([0, 1, 2, 0xFFFFFFFFFFFFFFFF, 0x7FFFFFFFFFFFFFFF, 0x8000000000000000, 0xFFFFFFFF, 0x80000000, 0x7FFFFFFF])
    if k < 0.35: return r.getrandbits(r.randint(1, 16))
    if k < 0.45: return (-r.getrandbits(12)) & 0xFFFFFFFFFFFFFFFF
    return r.getrandbits(64)

def randfp(r):
    k = r.random()
    if k < 0.3: return struct.unpack("<I", struct.pack("<f", r.uniform(-1000, 1000)))[0]
    if k < 0.4: return r.choice([0, 0x80000000, 0x3F800000, 0xBF800000, 0x7F800000, 0xFF800000, 0x3F000000, 0x4F000000])
    if k < 0.5: return struct.unpack("<I", struct.pack("<f", float(r.randint(-100, 100)) + r.choice([0, 0.5, 0.25])))[0]
    if k < 0.6: return r.getrandbits(32) & 0x7F7FFFFF
    return r.getrandbits(32) & 0xBFFFFFFF  # avoid most NaNs
def randvec(r):
    k = r.random()
    if k < 0.3:
        return sum(randfp(r) << (32 * i) for i in range(4))
    if k < 0.6:
        out = 0
        for i in range(2):
            d = struct.unpack("<Q", struct.pack("<d", r.uniform(-1e6, 1e6) if r.random() < 0.7 else float(r.randint(-50, 50))))[0]
            out |= d << (64 * i)
        return out
    return r.getrandbits(128)

def candidates(cls, n, r, md):
    mask, val = CLASSES[cls]
    out = []
    tries = 0
    while len(out) < n and tries < n * 400:
        tries += 1
        w = (r.getrandbits(32) & ~mask) | val
        ins = list(md.disasm(struct.pack("<I", w), CODE))
        if not ins:
            continue
        m = ins[0].mnemonic
        if any(m.startswith(s) for s in SKIP) or m in ("prfm", "prfum", "mrs", "msr", "hint", "sys", "sysl") or m.startswith("b") and cls != "simd" and cls != "fp" and m not in ("bic", "bics", "bfi", "bfm", "bfxil", "bsl", "bif", "bit"):
            continue
        out.append((w, m + " " + ins[0].op_str))
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=400)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--show", type=int, default=25)
    ap.add_argument("classes", nargs="*")
    a = ap.parse_args()
    r = random.Random(a.seed)
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    cands = []
    for cls in a.classes or list(CLASSES):
        cands += [(cls, w, t) for w, t in candidates(cls, a.count, r, md)]
    out = subprocess.run([BIFROST, "lift-words", "--pc", hex(CODE)] + ["%08x" % w for _, w, _ in cands],
                         check=True, capture_output=True, text=True).stdout.splitlines()
    tests, unsupported = [], {}
    for (cls, w, text), line in zip(cands, out):
        _, kind, code = line.split("\t", 2)
        if kind == "C":
            tests.append((cls, w, text, code))
        else:
            unsupported.setdefault(text.split()[0], []).append(text)
    tmp = tempfile.mkdtemp(prefix="bfdiff")
    src = STUB + "".join("void t_%d(BfCpu *c) { %s }\n" % (i, t[3]) for i, t in enumerate(tests))
    src += "BfFn bf_tests[] = {%s};\n" % ",".join("t_%d" % i for i in range(len(tests)))
    open(os.path.join(tmp, "t.c"), "w").write(src)
    so = os.path.join(tmp, "t.so")
    cc = subprocess.run(["clang", "-O1", "-shared", "-fPIC", "-w", "-I", os.path.join(ROOT, "runtime"), "-DBF_AS_BASE=0ull", "-DBF_AS_SIZE=(1ull<<32)", "-o", so,
                         os.path.join(tmp, "t.c"), "-lm"], capture_output=True, text=True)
    if cc.returncode:
        print(cc.stderr[:6000]); sys.exit(2)
    lib = ctypes.CDLL(so)
    lib.bf_test_cpu_size.restype = ctypes.c_size_t
    lib.bf_test_v_off.restype = ctypes.c_size_t
    csize, voff = lib.bf_test_cpu_size(), lib.bf_test_v_off()
    lib.bf_test_mem.restype = ctypes.c_void_p
    if lib.bf_test_mem() != MEM_BASE:
        print("cannot map the test window at 0x%x" % MEM_BASE); sys.exit(2)
    hostmem = (ctypes.c_uint8 * MEM_SIZE).from_address(MEM_BASE)
    fns = (ctypes.c_void_p * len(tests)).in_dll(lib, "bf_tests")
    FN = ctypes.CFUNCTYPE(None, ctypes.c_void_p)
    fails = []
    base_mem = bytes(r.getrandbits(8) for _ in range(4096)) * (MEM_SIZE // 4096)
    for i, (cls, w, text, code) in enumerate(tests):
        x = [rand64(r) for _ in range(31)]
        sp = MEM_BASE + 0x100000 + r.randrange(0, 0x80000) * 16
        if cls == "ldst":
            x = [MEM_BASE + 0x100000 + r.randrange(0, 0x100000) if r.random() < 0.8 else r.getrandbits(r.choice([4, 8, 12])) for _ in range(31)]
        nzcv = r.getrandbits(4) << 28
        v = [randvec(r) for _ in range(32)]
        uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        uc.reg_write(UC_ARM64_REG_CPACR_EL1, 0x300000)
        uc.mem_map(MEM_BASE, MEM_SIZE)
        mem = bytearray(base_mem)
        mem[CODE - MEM_BASE:CODE - MEM_BASE + 4] = struct.pack("<I", w)
        uc.mem_write(MEM_BASE, bytes(mem))
        for k in range(31): uc.reg_write(XREGS[k], x[k])
        uc.reg_write(UC_ARM64_REG_SP, sp)
        uc.reg_write(UC_ARM64_REG_NZCV, nzcv)
        for k in range(32): uc.reg_write(UC_ARM64_REG_Q0 + k, v[k])
        try:
            uc.emu_start(CODE, CODE + 4, count=1)
        except Exception as e:
            continue  # faults (unmapped access, undefined) are not comparable
        if uc.reg_read(UC_ARM64_REG_PC) != CODE + 4:
            continue
        ctypes.memmove(hostmem, bytes(mem), MEM_SIZE)
        cpu = bytearray(csize)
        struct.pack_into("<31Q", cpu, 0, *x)
        struct.pack_into("<QQQ", cpu, 32 * 8, sp, CODE, nzcv)
        for k in range(32): cpu[voff + 16 * k: voff + 16 * k + 16] = v[k].to_bytes(16, "little")
        cbuf = (ctypes.c_uint8 * csize).from_buffer(cpu)
        FN(fns[i])(ctypes.addressof(cbuf))
        diffs = []
        for k in range(31):
            got, want = struct.unpack_from("<Q", cpu, 8 * k)[0], uc.reg_read(XREGS[k])
            if got != want: diffs.append("x%d got %x want %x" % (k, got, want))
        got = struct.unpack_from("<Q", cpu, 32 * 8)[0]
        if got != uc.reg_read(UC_ARM64_REG_SP): diffs.append("sp got %x want %x" % (got, uc.reg_read(UC_ARM64_REG_SP)))
        got = struct.unpack_from("<Q", cpu, 34 * 8)[0]
        want = uc.reg_read(UC_ARM64_REG_NZCV) & 0xF0000000
        if got != want: diffs.append("nzcv got %x want %x" % (got, want))
        for k in range(32):
            got = int.from_bytes(cpu[voff + 16 * k: voff + 16 * k + 16], "little")
            want = uc.reg_read(UC_ARM64_REG_Q0 + k)
            if got != want: diffs.append("v%d got %032x want %032x" % (k, got, want))
        if bytes(hostmem) != bytes(uc.mem_read(MEM_BASE, MEM_SIZE)):
            diffs.append("memory differs")
        if diffs:
            fails.append((w, text, code, diffs))
    print("tested %d, failed %d, unsupported %d" % (len(tests), len(fails), sum(len(v) for v in unsupported.values())))
    for w, text, code, diffs in fails[: a.show]:
        print("FAIL %08x  %-40s %s\n      %s" % (w, text, "; ".join(diffs[:3]), code[:300]))
    if unsupported:
        top = sorted(unsupported.items(), key=lambda kv: -len(kv[1]))[:30]
        print("unsupported:", ", ".join("%s(%d)" % (k, len(v)) for k, v in top))
    sys.exit(1 if fails else 0)

if __name__ == "__main__":
    main()
