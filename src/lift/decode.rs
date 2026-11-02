//! AArch64 (ARMv8.0 + CRC32, the Cortex-A57 profile of the Switch) decoder.
//!
//! Straight-line instructions become a C statement (`Op::C`); control flow stays structured so the
//! emitter can build functions, gotos and dispatch. Anything not decoded becomes `Op::Unsupported`
//! and the generated code stops there with a report instead of guessing.

use super::simd;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Op {
    /// C statement(s) executed with `BfCpu *c` in scope.
    C(String),
    B { target: u64 },
    Bl { target: u64 },
    /// `cond` is a C expression; branch to `target` when it is true.
    CondBranch { cond: String, target: u64 },
    Br { reg: u8 },
    Blr { reg: u8 },
    Ret { reg: u8 },
    Svc { imm: u16 },
    Brk { imm: u16 },
    Udf,
    Unsupported,
}

impl Op {
    pub fn is_terminator(&self) -> bool {
        matches!(
            self,
            Op::B { .. } | Op::Br { .. } | Op::Ret { .. } | Op::Brk { .. } | Op::Udf | Op::Unsupported
        )
    }
}

fn bits(w: u32, hi: u32, lo: u32) -> u32 {
    (w >> lo) & ((1u32 << (hi - lo + 1)) - 1)
}
fn bit(w: u32, b: u32) -> bool {
    (w >> b) & 1 == 1
}
fn sext(v: u64, nbits: u32) -> i64 {
    let s = 64 - nbits;
    ((v << s) as i64) >> s
}

// ---- register access in C
pub(crate) fn rx(n: u32) -> String {
    if n == 31 {
        "0ull".into()
    } else {
        format!("c->x[{n}]")
    }
}
pub(crate) fn rw(n: u32) -> String {
    if n == 31 {
        "0u".into()
    } else {
        format!("(uint32_t)c->x[{n}]")
    }
}
pub(crate) fn rxsp(n: u32) -> String {
    if n == 31 {
        "c->sp".into()
    } else {
        format!("c->x[{n}]")
    }
}
fn rwsp(n: u32) -> String {
    format!("(uint32_t){}", rxsp(n))
}
fn rsf(sf: bool, n: u32) -> String {
    if sf {
        rx(n)
    } else {
        rw(n)
    }
}
fn rsfsp(sf: bool, n: u32) -> String {
    if sf {
        rxsp(n)
    } else {
        rwsp(n)
    }
}
pub(crate) fn wx(n: u32, e: &str) -> String {
    format!("c->x[{n}] = (uint64_t)({e});")
}
fn wsf(sf: bool, n: u32, e: &str) -> String {
    if sf {
        format!("c->x[{n}] = (uint64_t)({e});")
    } else {
        format!("c->x[{n}] = (uint32_t)({e});")
    }
}
fn wsfsp(sf: bool, n: u32, e: &str) -> String {
    let dst = if n == 31 { "c->sp".to_string() } else { format!("c->x[{n}]") };
    if sf {
        format!("{dst} = (uint64_t)({e});")
    } else {
        format!("{dst} = (uint32_t)({e});")
    }
}
fn ty(sf: bool) -> &'static str {
    if sf {
        "uint64_t"
    } else {
        "uint32_t"
    }
}
fn hex(v: u64) -> String {
    format!("0x{v:x}ull")
}

/// DecodeBitMasks from the ARM ARM: returns (wmask, tmask), or None for reserved encodings.
pub fn decode_bit_masks(n: u32, imms: u32, immr: u32, immediate: bool, width: u32) -> Option<(u64, u64)> {
    let combined = (n << 6) | (!imms & 0x3F);
    if combined == 0 {
        return None;
    }
    let len = 31 - combined.leading_zeros();
    if len < 1 {
        return None;
    }
    let esize = 1u32 << len;
    if esize > width {
        return None;
    }
    let levels = esize - 1;
    if immediate && (imms & levels) == levels {
        return None;
    }
    let s = imms & levels;
    let r = immr & levels;
    let d = s.wrapping_sub(r) & levels;
    let ones = |k: u32| -> u64 {
        if k >= 64 {
            u64::MAX
        } else {
            (1u64 << k) - 1
        }
    };
    let emask = ones(esize);
    let welem = ones(s + 1);
    let telem = ones(d + 1);
    let ror = |v: u64, sh: u32| -> u64 {
        if sh == 0 {
            v
        } else {
            ((v >> sh) | (v << (esize - sh))) & emask
        }
    };
    let welem = ror(welem, r);
    let replicate = |e: u64| -> u64 {
        let mut out = 0u64;
        let mut i = 0;
        while i < width {
            out |= e << i;
            i += esize;
        }
        out
    };
    let wm = replicate(welem);
    let tm = replicate(telem);
    let wmask = if width == 32 { wm & 0xFFFF_FFFF } else { wm };
    let tmask = if width == 32 { tm & 0xFFFF_FFFF } else { tm };
    Some((wmask, tmask))
}

fn shifted(sf: bool, v: &str, kind: u32, amt: u32) -> String {
    if amt == 0 {
        return v.to_string();
    }
    let t = ty(sf);
    match kind {
        0 => format!("(({t})({v}) << {amt})"),
        1 => format!("(({t})({v}) >> {amt})"),
        2 => {
            if sf {
                format!("bf_asr64({v}, {amt})")
            } else {
                format!("bf_asr32({v}, {amt})")
            }
        }
        _ => {
            if sf {
                format!("bf_ror64({v}, {amt})")
            } else {
                format!("bf_ror32({v}, {amt})")
            }
        }
    }
}

pub(crate) fn extended(reg: u32, option: u32, amt: u32) -> String {
    let base = match option {
        0 => format!("(uint64_t)(uint8_t){}", rx(reg)),
        1 => format!("(uint64_t)(uint16_t){}", rx(reg)),
        2 => format!("(uint64_t)(uint32_t){}", rx(reg)),
        3 => rx(reg),
        4 => format!("(uint64_t)(int64_t)(int8_t){}", rx(reg)),
        5 => format!("(uint64_t)(int64_t)(int16_t){}", rx(reg)),
        6 => format!("(uint64_t)(int64_t)(int32_t){}", rx(reg)),
        _ => rx(reg),
    };
    if amt == 0 {
        base
    } else {
        format!("(({base}) << {amt})")
    }
}

pub fn decode(w: u32, pc: u64) -> Op {
    if w == 0 {
        return Op::Udf;
    }
    let op0 = bits(w, 28, 25);
    let r = match op0 {
        0b1000 | 0b1001 => dp_imm(w, pc),
        0b1010 | 0b1011 => return branch_sys(w, pc),
        0b0101 | 0b1101 => dp_reg(w),
        0b0111 | 0b1111 => simd::decode_dp(w),
        _ if op0 & 0b0101 == 0b0100 => ldst(w, pc),
        _ => None,
    };
    match r {
        Some(s) => Op::C(s),
        None => Op::Unsupported,
    }
}

fn dp_imm(w: u32, pc: u64) -> Option<String> {
    let sf = bit(w, 31);
    let rd = bits(w, 4, 0);
    let rn = bits(w, 9, 5);
    match bits(w, 25, 23) {
        0b000 | 0b001 => {
            let imm = ((bits(w, 23, 5) as u64) << 2) | bits(w, 30, 29) as u64;
            let v = if bit(w, 31) {
                let off = sext(imm, 21) << 12;
                (pc & !0xFFF).wrapping_add(off as u64)
            } else {
                pc.wrapping_add(sext(imm, 21) as u64)
            };
            if rd == 31 {
                return Some(String::new());
            }
            Some(wx(rd, &hex(v)))
        }
        0b010 => {
            let sub = bit(w, 30);
            let s = bit(w, 29);
            let mut imm = bits(w, 21, 10) as u64;
            if bit(w, 22) {
                imm <<= 12;
            }
            let a = rsfsp(sf, rn);
            if s {
                let f = match (sf, sub) {
                    (true, false) => "bf_adds64",
                    (true, true) => "bf_subs64",
                    (false, false) => "bf_adds32",
                    (false, true) => "bf_subs32",
                };
                Some(wsf(sf, rd, &format!("{f}(c, {a}, {})", hex(imm))))
            } else {
                let o = if sub { "-" } else { "+" };
                Some(wsfsp(sf, rd, &format!("{a} {o} {}", hex(imm))))
            }
        }
        0b100 => {
            let n = bits(w, 22, 22);
            if !sf && n == 1 {
                return None;
            }
            let width = if sf { 64 } else { 32 };
            let (imm, _) = decode_bit_masks(n, bits(w, 15, 10), bits(w, 21, 16), true, width)?;
            let a = rsf(sf, rn);
            let i = hex(imm);
            match bits(w, 30, 29) {
                0 => Some(wsfsp(sf, rd, &format!("{a} & {i}"))),
                1 => Some(wsfsp(sf, rd, &format!("{a} | {i}"))),
                2 => Some(wsfsp(sf, rd, &format!("{a} ^ {i}"))),
                _ => {
                    let lf = if sf { "bf_logic_flags64" } else { "bf_logic_flags32" };
                    Some(format!(
                        "{{ {t} r = ({t})({a} & {i}); {lf}(c, r); {} }}",
                        wsf(sf, rd, "r"),
                        t = ty(sf)
                    ))
                }
            }
        }
        0b101 => {
            let hw = bits(w, 22, 21);
            if !sf && hw > 1 {
                return None;
            }
            let imm = (bits(w, 20, 5) as u64) << (hw * 16);
            match bits(w, 30, 29) {
                0 => {
                    let v = !imm;
                    let v = if sf { v } else { v & 0xFFFF_FFFF };
                    Some(wx(rd, &hex(v)))
                }
                2 => Some(wx(rd, &hex(imm))),
                3 => {
                    let mask = 0xFFFFu64 << (hw * 16);
                    Some(wsf(sf, rd, &format!("({} & {}) | {}", rx(rd), hex(!mask), hex(imm))))
                }
                _ => None,
            }
        }
        0b110 => {
            let n = bits(w, 22, 22);
            if sf as u32 != n {
                return None;
            }
            let opc = bits(w, 30, 29);
            if opc == 3 {
                return None;
            }
            let width = if sf { 64 } else { 32 };
            let immr = bits(w, 21, 16);
            let imms = bits(w, 15, 10);
            if !sf && (immr > 31 || imms > 31) {
                return None;
            }
            let (wm, tm) = decode_bit_masks(n, imms, immr, false, width)?;
            let kind = match opc {
                0 => 0,
                1 => 1,
                _ => 2,
            };
            Some(wsf(
                sf,
                rd,
                &format!(
                    "bf_bfm({}, {}, {immr}, {imms}, {}, {}, {kind}, {width})",
                    rx(rd),
                    rx(rn),
                    hex(wm),
                    hex(tm)
                ),
            ))
        }
        0b111 => {
            if bits(w, 30, 29) != 0 || bit(w, 21) || bit(w, 22) != sf {
                return None;
            }
            let rm = bits(w, 20, 16);
            let lsb = bits(w, 15, 10);
            if !sf && lsb > 31 {
                return None;
            }
            if lsb == 0 {
                return Some(wsf(sf, rd, &rsf(sf, rm)));
            }
            let width = if sf { 64 } else { 32 };
            Some(wsf(
                sf,
                rd,
                &format!(
                    "(({t}){} >> {lsb}) | (({t}){} << {})",
                    rsf(sf, rm),
                    rsf(sf, rn),
                    width - lsb,
                    t = ty(sf)
                ),
            ))
        }
        _ => None,
    }
}

fn sysreg_read(id: u32) -> Option<&'static str> {
    Some(match id {
        0x5A10 => "c->nzcv",
        0x5A20 => "c->fpcr",
        0x5A21 => "c->fpsr",
        0x5E82 => "c->tpidr",
        0x5E83 => "c->tpidrro",
        0x5F00 => "BF_CNTFRQ",
        0x5F01 | 0x5F02 => "bf_ticks()",
        0x5807 => "4ull",
        0x5801 => "0x8444c004ull",
        0x4000 => "0x411fd071ull",
        0x4005 => "0x80000000ull",
        _ => return None,
    })
}

fn branch_sys(w: u32, pc: u64) -> Op {
    let rt = bits(w, 4, 0);
    if w & 0x7C00_0000 == 0x1400_0000 {
        let target = pc.wrapping_add((sext(bits(w, 25, 0) as u64, 26) << 2) as u64);
        return if bit(w, 31) { Op::Bl { target } } else { Op::B { target } };
    }
    if w & 0xFF00_0010 == 0x5400_0000 {
        let target = pc.wrapping_add((sext(bits(w, 23, 5) as u64, 19) << 2) as u64);
        let cond = bits(w, 3, 0);
        if cond >= 14 {
            return Op::B { target };
        }
        return Op::CondBranch { cond: format!("BF_COND(c, {cond})"), target };
    }
    if w & 0x7E00_0000 == 0x3400_0000 {
        let target = pc.wrapping_add((sext(bits(w, 23, 5) as u64, 19) << 2) as u64);
        let r = rsf(bit(w, 31), rt);
        let o = if bit(w, 24) { "!=" } else { "==" };
        return Op::CondBranch { cond: format!("{r} {o} 0"), target };
    }
    if w & 0x7E00_0000 == 0x3600_0000 {
        let target = pc.wrapping_add((sext(bits(w, 18, 5) as u64, 14) << 2) as u64);
        let b = (bits(w, 31, 31) << 5) | bits(w, 23, 19);
        let o = if bit(w, 24) { "!=" } else { "==" };
        return Op::CondBranch { cond: format!("(({} >> {b}) & 1) {o} 0", rx(rt)), target };
    }
    if w & 0xFE1F_FC1F == 0xD61F_0000 {
        let rn = bits(w, 9, 5) as u8;
        return match bits(w, 24, 21) {
            0 => Op::Br { reg: rn },
            1 => Op::Blr { reg: rn },
            2 => Op::Ret { reg: rn },
            _ => Op::Unsupported,
        };
    }
    if w & 0xFFE0_001F == 0xD400_0001 {
        return Op::Svc { imm: bits(w, 20, 5) as u16 };
    }
    if w & 0xFFE0_001F == 0xD420_0000 {
        return Op::Brk { imm: bits(w, 20, 5) as u16 };
    }
    if w & 0xFFFF_F01F == 0xD503_201F {
        let hint = bits(w, 11, 5);
        return Op::C(match hint {
            1 | 2 | 3 => "bf_yield();".into(),
            _ => String::new(),
        });
    }
    match w & 0xFFFF_F0FF {
        0xD503_305F => return Op::C("c->excl_addr = ~0ull;".into()),
        0xD503_309F | 0xD503_30BF => return Op::C("BF_DMB();".into()),
        0xD503_30DF => return Op::C(String::new()),
        _ => {}
    }
    if w & 0xFFF8_0000 == 0xD508_0000 {
        // SYS: DC ZVA zeroes a block; cache maintenance is meaningless on the host.
        if w & 0xFFFF_FFE0 == 0xD50B_7420 {
            return Op::C(format!("bf_dc_zva({});", rx(rt)));
        }
        return Op::C(String::new());
    }
    if w & 0xFFF0_0000 == 0xD530_0000 {
        let id = bits(w, 19, 5);
        return match sysreg_read(id) {
            Some(e) if rt != 31 => Op::C(wx(rt, e)),
            Some(_) => Op::C(String::new()),
            None => Op::Unsupported,
        };
    }
    if w & 0xFFF0_0000 == 0xD510_0000 {
        let v = rx(rt);
        return match bits(w, 19, 5) {
            0x5A10 => Op::C(format!("c->nzcv = {v} & 0xF0000000ull;")),
            0x5A20 => Op::C(format!("c->fpcr = {v};")),
            0x5A21 => Op::C(format!("c->fpsr = {v};")),
            0x5E82 => Op::C(format!("c->tpidr = {v};")),
            _ => Op::Unsupported,
        };
    }
    Op::Unsupported
}

fn dp_reg(w: u32) -> Option<String> {
    let sf = bit(w, 31);
    let rd = bits(w, 4, 0);
    let rn = bits(w, 9, 5);
    let rm = bits(w, 20, 16);
    let t = ty(sf);
    if !bit(w, 28) {
        if !bit(w, 24) {
            // logical (shifted register)
            let amt = bits(w, 15, 10);
            if !sf && amt > 31 {
                return None;
            }
            let mut b = shifted(sf, &rsf(sf, rm), bits(w, 23, 22), amt);
            if bit(w, 21) {
                b = format!("~({t})({b})");
            }
            let a = rsf(sf, rn);
            return Some(match bits(w, 30, 29) {
                0 => wsf(sf, rd, &format!("{a} & {b}")),
                1 => wsf(sf, rd, &format!("{a} | {b}")),
                2 => wsf(sf, rd, &format!("{a} ^ {b}")),
                _ => {
                    let lf = if sf { "bf_logic_flags64" } else { "bf_logic_flags32" };
                    format!("{{ {t} r = ({t})({a} & {b}); {lf}(c, r); {} }}", wsf(sf, rd, "r"))
                }
            });
        }
        let sub = bit(w, 30);
        let s = bit(w, 29);
        let (a, b, dst_sp) = if !bit(w, 21) {
            let kind = bits(w, 23, 22);
            let amt = bits(w, 15, 10);
            if kind == 3 || (!sf && amt > 31) {
                return None;
            }
            (rsf(sf, rn), shifted(sf, &rsf(sf, rm), kind, amt), false)
        } else {
            if bits(w, 23, 22) != 0 {
                return None;
            }
            let amt = bits(w, 12, 10);
            if amt > 4 {
                return None;
            }
            (rsfsp(sf, rn), extended(rm, bits(w, 15, 13), amt), !s)
        };
        if s {
            let f = match (sf, sub) {
                (true, false) => "bf_adds64",
                (true, true) => "bf_subs64",
                (false, false) => "bf_adds32",
                (false, true) => "bf_subs32",
            };
            return Some(wsf(sf, rd, &format!("{f}(c, {a}, {b})")));
        }
        let o = if sub { "-" } else { "+" };
        let e = format!("({t})({a}) {o} ({t})({b})");
        return Some(if dst_sp { wsfsp(sf, rd, &e) } else { wsf(sf, rd, &e) });
    }
    match bits(w, 24, 21) {
        0b0000 => {
            if bits(w, 15, 10) != 0 {
                return None;
            }
            let sub = bit(w, 30);
            let s = bit(w, 29) as u32;
            let b = if sub { format!("~({t}){}", rsf(sf, rm)) } else { rsf(sf, rm) };
            let f = if sf { "bf_adc64" } else { "bf_adc32" };
            Some(wsf(sf, rd, &format!("{f}(c, {}, {b}, BF_CARRY(c), {s})", rsf(sf, rn))))
        }
        0b0010 => {
            if !bit(w, 29) || bit(w, 10) || bit(w, 4) {
                return None;
            }
            let cond = bits(w, 15, 12);
            let nzcv = bits(w, 3, 0);
            let b = if bit(w, 11) { hex(rm as u64) } else { rsf(sf, rm) };
            let f = match (sf, bit(w, 30)) {
                (true, true) => "bf_subs64",
                (true, false) => "bf_adds64",
                (false, true) => "bf_subs32",
                (false, false) => "bf_adds32",
            };
            Some(format!(
                "if (BF_COND(c, {cond})) {{ (void){f}(c, {}, {b}); }} else {{ c->nzcv = 0x{:x}ull; }}",
                rsf(sf, rn),
                (nzcv as u64) << 28
            ))
        }
        0b0100 => {
            if bit(w, 29) || bit(w, 11) {
                return None;
            }
            let cond = bits(w, 15, 12);
            let a = rsf(sf, rn);
            let m = rsf(sf, rm);
            let b = match (bit(w, 30), bits(w, 10, 10)) {
                (false, 0) => m,
                (false, _) => format!("({t})({m} + 1)"),
                (true, 0) => format!("({t})~{m}"),
                (true, _) => format!("({t})(0 - {m})"),
            };
            Some(wsf(sf, rd, &format!("BF_COND(c, {cond}) ? ({t}){a} : ({t}){b}")))
        }
        0b0110 => {
            if bit(w, 29) {
                return None;
            }
            if bit(w, 30) {
                if rm != 0 {
                    return None;
                }
                let a = rsf(sf, rn);
                let opc = bits(w, 15, 10);
                let e = match (opc, sf) {
                    (0, true) => format!("bf_rbit64({a})"),
                    (0, false) => format!("bf_rbit32({a})"),
                    (1, true) => format!("bf_rev16_64({a})"),
                    (1, false) => format!("(uint32_t)bf_rev16_64({a})"),
                    (2, false) => format!("__builtin_bswap32({a})"),
                    (2, true) => format!("bf_rev32_64({a})"),
                    (3, true) => format!("__builtin_bswap64({a})"),
                    (4, true) => format!("bf_clz64({a})"),
                    (4, false) => format!("bf_clz32({a})"),
                    (5, true) => format!("bf_cls64({a})"),
                    (5, false) => format!("bf_cls32({a})"),
                    _ => return None,
                };
                return Some(wsf(sf, rd, &e));
            }
            let a = rsf(sf, rn);
            let b = rsf(sf, rm);
            let sz = if sf { "64" } else { "32" };
            let opc = bits(w, 15, 10);
            let e = match opc {
                2 => format!("bf_udiv{sz}({a}, {b})"),
                3 => format!("bf_sdiv{sz}({a}, {b})"),
                8 => format!("({t})({a}) << ({b} & {})", if sf { 63 } else { 31 }),
                9 => format!("({t})({a}) >> ({b} & {})", if sf { 63 } else { 31 }),
                10 => format!("bf_asr{sz}({a}, {b})"),
                11 => format!("bf_ror{sz}({a}, {b})"),
                16..=23 => {
                    let size = opc & 3;
                    if (size == 3) != sf {
                        return None;
                    }
                    let castagnoli = (opc >> 2) & 1;
                    let n = 1 << size;
                    format!("bf_crc32({}, {}, {n}, {castagnoli})", rw(rn), rx(rm))
                }
                _ => return None,
            };
            Some(wsf(sf, rd, &e))
        }
        op if op & 0b1000 != 0 => {
            if bits(w, 30, 29) != 0 {
                return None;
            }
            let ra = bits(w, 14, 10);
            let o0 = bit(w, 15);
            let op31 = bits(w, 23, 21);
            let sign = if o0 { "-" } else { "+" };
            let e = match op31 {
                0 => format!("({t}){} {sign} ({t})({} * {})", rsf(sf, ra), rsf(sf, rn), rsf(sf, rm)),
                1 | 5 if sf => {
                    let cast = if op31 == 1 { "(uint64_t)(int64_t)(int32_t)" } else { "(uint64_t)(uint32_t)" };
                    format!("{} {sign} {cast}{} * {cast}{}", rx(ra), rx(rn), rx(rm))
                }
                2 if sf && !o0 => format!("bf_smulh({}, {})", rx(rn), rx(rm)),
                6 if sf && !o0 => format!("bf_umulh({}, {})", rx(rn), rx(rm)),
                _ => return None,
            };
            Some(wsf(sf, rd, &e))
        }
        _ => None,
    }
}

fn ld_int(size: u32, signed_to: Option<bool>, addr: &str) -> String {
    let raw = match size {
        0 => format!("LD8({addr})"),
        1 => format!("LD16({addr})"),
        2 => format!("LD32({addr})"),
        _ => format!("LD64({addr})"),
    };
    match signed_to {
        None => format!("(uint64_t){raw}"),
        Some(to64) => {
            let st = ["int8_t", "int16_t", "int32_t", "int64_t"][size as usize];
            if to64 {
                format!("(uint64_t)(int64_t)({st}){raw}")
            } else {
                format!("(uint64_t)(uint32_t)(int32_t)({st}){raw}")
            }
        }
    }
}

fn st_int(size: u32, addr: &str, v: &str) -> String {
    let n = 8 << size;
    format!("ST{n}({addr}, {v});")
}

/// Addressing for single and pair loads/stores: returns (prologue, address expr, writeback).
fn addressing(rn: u32, offset: i64, mode: u32) -> (String, String) {
    // mode: 0 = offset, 1 = post-index, 2 = pre-index
    let base = rxsp(rn);
    let wb = if rn == 31 { "c->sp".to_string() } else { format!("c->x[{rn}]") };
    match mode {
        0 => (format!("uint64_t a = {base} + (uint64_t){offset}ll; "), String::new()),
        1 => (format!("uint64_t a = {base}; "), format!(" {wb} = a + (uint64_t){offset}ll;")),
        _ => (format!("uint64_t a = {base} + (uint64_t){offset}ll; "), format!(" {wb} = a;")),
    }
}

fn ldst(w: u32, pc: u64) -> Option<String> {
    let rt = bits(w, 4, 0);
    let rn = bits(w, 9, 5);
    let v = bit(w, 26);
    if w & 0xBE00_0000 == 0x0C00_0000 {
        return simd::ldst_struct(w);
    }
    // exclusive / acquire-release
    if w & 0x3F00_0000 == 0x0800_0000 {
        if v {
            return None;
        }
        return ldst_excl(w);
    }
    // load literal
    if w & 0x3B00_0000 == 0x1800_0000 {
        let addr = pc.wrapping_add((sext(bits(w, 23, 5) as u64, 19) << 2) as u64);
        let a = hex(addr);
        let opc = bits(w, 31, 30);
        if v {
            return simd::ld_literal(opc, rt, &a);
        }
        return Some(match opc {
            0 => wx(rt, &ld_int(2, None, &a)),
            1 => wx(rt, &ld_int(3, None, &a)),
            2 => wx(rt, &ld_int(2, Some(true), &a)),
            _ => String::new(),
        });
    }
    // pairs
    if w & 0x3A00_0000 == 0x2800_0000 {
        let opc = bits(w, 31, 30);
        let l = bit(w, 22);
        let rt2 = bits(w, 14, 10);
        let mode = match bits(w, 24, 23) {
            0 | 2 => 0,
            1 => 1,
            _ => 2,
        };
        if v {
            if opc == 3 {
                return None;
            }
            let scale = 2 + opc;
            let off = sext(bits(w, 21, 15) as u64, 7) << scale;
            let (pre, wb) = addressing(rn, off, mode);
            return Some(format!("{{ {pre}{}{wb} }}", simd::pair(l, opc, rt, rt2)));
        }
        let (scale, sign) = match opc {
            0 => (2, None),
            1 if l => (2, Some(true)),
            2 => (3, None),
            _ => return None,
        };
        let off = sext(bits(w, 21, 15) as u64, 7) << scale;
        let (pre, wb) = addressing(rn, off, mode);
        let size = scale as u32;
        let step = 1u64 << scale;
        let body = if l {
            // read both before writing so rt == rn behaves
            format!(
                "uint64_t v1 = {}; uint64_t v2 = {}; {} {}",
                ld_int(size, sign, "a"),
                ld_int(size, sign, &format!("a + {step}")),
                wx(rt, "v1"),
                wx(rt2, "v2")
            )
        } else {
            let (x1, x2) = (rx(rt), rx(rt2));
            format!(
                "uint64_t v1 = {x1}; uint64_t v2 = {x2}; {} {}",
                st_int(size, "a", "v1"),
                st_int(size, &format!("a + {step}"), "v2")
            )
        };
        return Some(format!("{{ {pre}{body}{wb} }}"));
    }
    if w & 0x3B00_0000 != 0x3800_0000 && w & 0x3B00_0000 != 0x3900_0000 {
        return None;
    }
    let size = bits(w, 31, 30);
    let opc = bits(w, 23, 22);
    let (pre, wb) = if bit(w, 24) {
        let scale = if v && opc & 2 != 0 { 4 } else { size };
        let off = (bits(w, 21, 10) as i64) << scale;
        addressing(rn, off, 0)
    } else if !bit(w, 21) {
        let off = sext(bits(w, 20, 12) as u64, 9);
        match bits(w, 11, 10) {
            0 | 2 => addressing(rn, off, 0),
            1 => addressing(rn, off, 1),
            _ => addressing(rn, off, 2),
        }
    } else if bits(w, 11, 10) == 2 {
        let option = bits(w, 15, 13);
        if option & 2 == 0 {
            return None;
        }
        let scale = if v && opc & 2 != 0 { 4 } else { size };
        let amt = if bit(w, 12) { scale } else { 0 };
        let rm = bits(w, 20, 16);
        (format!("uint64_t a = {} + {}; ", rxsp(rn), extended(rm, option, amt)), String::new())
    } else {
        return None;
    };
    if v {
        let body = simd::ldst_single(size, opc, rt)?;
        return Some(format!("{{ {pre}{body}{wb} }}"));
    }
    let body = match opc {
        0 => {
            let val = rx(rt);
            format!("uint64_t v1 = {val}; {}", st_int(size, "a", "v1"))
        }
        1 => wx(rt, &ld_int(size, None, "a")),
        2 => {
            if size == 3 {
                return Some(String::new()); // PRFM
            }
            wx(rt, &ld_int(size, Some(true), "a"))
        }
        _ => {
            if size >= 2 {
                return None;
            }
            wx(rt, &ld_int(size, Some(false), "a"))
        }
    };
    Some(format!("{{ {pre}{body}{wb} }}"))
}

fn ldst_excl(w: u32) -> Option<String> {
    let size = bits(w, 31, 30);
    let o2 = bit(w, 23);
    let l = bit(w, 22);
    let o1 = bit(w, 21);
    let rs = bits(w, 20, 16);
    let rt2 = bits(w, 14, 10);
    let rt = bits(w, 4, 0);
    let rn = bits(w, 9, 5);
    let a = rxsp(rn);
    let n = 8 << size;
    let fence = if bit(w, 15) { " BF_DMB();" } else { "" };
    match (o2, o1) {
        (false, false) => {
            if l {
                Some(format!("{{ {}{fence} }}", wx(rt, &format!("bf_ldx{n}(c, {a})"))))
            } else {
                Some(format!("{{{fence} {} }}", wx(rs, &format!("bf_stx{n}(c, {a}, {})", rx(rt)))))
            }
        }
        (false, true) => {
            if size < 2 {
                return None;
            }
            if size == 2 {
                if l {
                    Some(format!(
                        "{{ uint64_t v = bf_ldx64(c, {a}); {} {}{fence} }}",
                        wx(rt, "(uint32_t)v"),
                        wx(rt2, "v >> 32")
                    ))
                } else {
                    Some(format!(
                        "{{{fence} {} }}",
                        wx(rs, &format!("bf_stx64(c, {a}, ((uint64_t){} << 32) | {})", rw(rt2), rw(rt)))
                    ))
                }
            } else if l {
                Some(format!(
                    "{{ uint64_t lo, hi; bf_ldxp64(c, {a}, &lo, &hi); {} {}{fence} }}",
                    wx(rt, "lo"),
                    wx(rt2, "hi")
                ))
            } else {
                Some(format!("{{{fence} {} }}", wx(rs, &format!("bf_stxp64(c, {a}, {}, {})", rx(rt), rx(rt2)))))
            }
        }
        (true, false) => {
            if l {
                Some(wx(rt, &format!("bf_lda{n}({a})")))
            } else {
                Some(format!("bf_stl{n}({a}, {});", rx(rt)))
            }
        }
        (true, true) => None,
    }
}
