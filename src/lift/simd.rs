//! FP and Advanced SIMD lifting. Vector ops become small C loops over `BfVec` lanes; clang/gcc
//! vectorize them back to SSE/AVX. Scalar ops are the same loops with one lane.

use super::decode::{rw, rx, rxsp};

const U: [&str; 4] = ["b", "h", "s", "d"];
const S: [&str; 4] = ["sb", "sh", "ss", "sd"];
const UT: [&str; 4] = ["uint8_t", "uint16_t", "uint32_t", "uint64_t"];
const F: [&str; 2] = ["f", "fd"];
const FI: [&str; 2] = ["s", "d"];
const FS: [&str; 2] = ["f", "d"];
const FT: [&str; 2] = ["float", "double"];

fn bits(w: u32, hi: u32, lo: u32) -> u32 {
    (w >> lo) & ((1u32 << (hi - lo + 1)) - 1)
}
fn bit(w: u32, b: u32) -> bool {
    (w >> b) & 1 == 1
}

/// Lane loop. `stmt` sees A = Vn, B = Vm, D = old Vd and writes R; R starts as D when `keep`,
/// otherwise zero, and replaces Vd afterwards (so Q=0 and scalar forms clear the upper bits).
fn vl(d: u32, n: u32, m: u32, cnt: u32, keep: bool, stmt: &str) -> String {
    let init = if keep { "D" } else { "(BfVec){{0}}" };
    let init = init.replace("{{", "{").replace("}}", "}");
    format!(
        "{{ BfVec A = c->v[{n}], B = c->v[{m}], D = c->v[{d}]; BfVec R = {init}; (void)A; (void)B; (void)D; \
         for (int i = 0; i < {cnt}; i++) {{ {stmt} }} c->v[{d}] = R; }}"
    )
}

fn t3(tmpl: &str, x: &str, y: &str, d: &str) -> String {
    tmpl.replace("$x", x).replace("$y", y).replace("$d", d)
}

fn ones(eb: u32) -> String {
    format!("({})~0ull", UT[(eb.trailing_zeros() - 3) as usize])
}

// ---------------------------------------------------------------- loads and stores
pub fn ld_literal(opc: u32, rt: u32, addr: &str) -> Option<String> {
    let n = match opc {
        0 => 4,
        1 => 8,
        2 => 16,
        _ => return None,
    };
    Some(format!("{{ BfVec t = {{0}}; memcpy(&t, BF_HOST({addr}), {n}); c->v[{rt}] = t; }}"))
}

pub fn pair(load: bool, opc: u32, rt: u32, rt2: u32) -> String {
    let n = 4u32 << opc;
    if load {
        format!(
            "BfVec t1 = {{0}}, t2 = {{0}}; memcpy(&t1, BF_HOST(a), {n}); memcpy(&t2, BF_HOST(a + {n}), {n}); \
             c->v[{rt}] = t1; c->v[{rt2}] = t2;"
        )
    } else {
        format!("memcpy(BF_HOST(a), &c->v[{rt}], {n}); memcpy(BF_HOST(a + {n}), &c->v[{rt2}], {n});")
    }
}

pub fn ldst_single(size: u32, opc: u32, rt: u32) -> Option<String> {
    let (n, load) = if opc & 2 != 0 {
        if size != 0 {
            return None;
        }
        (16, opc & 1 == 1)
    } else {
        (1u32 << size, opc & 1 == 1)
    };
    Some(if load {
        format!("BfVec t = {{0}}; memcpy(&t, BF_HOST(a), {n}); c->v[{rt}] = t;")
    } else {
        format!("memcpy(BF_HOST(a), &c->v[{rt}], {n});")
    })
}

/// LD1-LD4 / ST1-ST4 (multiple structures, single structure, replicate).
pub fn ldst_struct(w: u32) -> Option<String> {
    let q = bit(w, 30);
    let load = bit(w, 22);
    let post = bit(w, 23);
    let rm = bits(w, 20, 16);
    let rn = bits(w, 9, 5);
    let rt = bits(w, 4, 0);
    if !post && rm != 0 {
        return None;
    }
    let base = rxsp(rn);
    let wbreg = if rn == 31 { "c->sp".to_string() } else { format!("c->x[{rn}]") };
    let wb = |total: u32| -> String {
        if !post {
            String::new()
        } else if rm == 31 {
            format!(" {wbreg} = a + {total};")
        } else {
            format!(" {wbreg} = a + c->x[{rm}];")
        }
    };
    let opcode = bits(w, 15, 12);
    if !bit(w, 24) {
        let size = bits(w, 11, 10);
        let es = 1u32 << size;
        let regbytes = if q { 16 } else { 8 };
        let (nregs, interleave) = match opcode {
            0b0000 => (4, 4),
            0b0010 => (4, 1),
            0b0100 => (3, 3),
            0b0110 => (3, 1),
            0b0111 => (1, 1),
            0b1000 => (2, 2),
            0b1010 => (2, 1),
            _ => return None,
        };
        if size == 3 && !q && interleave > 1 {
            return None;
        }
        let total = nregs * regbytes;
        let mut s = format!("{{ uint64_t a = {base}; BfVec t[{nregs}]; memset(t, 0, sizeof t); ");
        if !load {
            for r in 0..nregs {
                s += &format!("t[{r}] = c->v[{}]; ", (rt + r) % 32);
            }
        }
        if interleave == 1 {
            for r in 0..nregs {
                if load {
                    s += &format!("memcpy(&t[{r}], BF_HOST(a + {}), {regbytes}); ", r * regbytes);
                } else {
                    s += &format!("memcpy(BF_HOST(a + {}), &t[{r}], {regbytes}); ", r * regbytes);
                }
            }
        } else {
            let elems = regbytes / es;
            let (dst, src) = if load {
                (format!("&t[r].b[e * {es}]"), format!("BF_HOST(a + (e * {nregs} + r) * {es})"))
            } else {
                (format!("BF_HOST(a + (e * {nregs} + r) * {es})"), format!("&t[r].b[e * {es}]"))
            };
            s += &format!(
                "for (int e = 0; e < {elems}; e++) for (int r = 0; r < {nregs}; r++) memcpy({dst}, {src}, {es}); "
            );
        }
        if load {
            for r in 0..nregs {
                s += &format!("c->v[{}] = t[{r}]; ", (rt + r) % 32);
            }
        }
        s += &wb(total);
        s += " }";
        return Some(s);
    }
    // single structure
    let r = bit(w, 21) as u32;
    let op3 = bits(w, 15, 13);
    let selem = (((op3 & 1) << 1) | r) + 1;
    let s_bit = bits(w, 12, 12);
    let size = bits(w, 11, 10);
    let scale = op3 >> 1;
    let qb = q as u32;
    let (es, index, replicate) = match scale {
        0 => (1, (qb << 3) | (s_bit << 2) | size, false),
        1 => {
            if size & 1 != 0 {
                return None;
            }
            (2, (qb << 2) | (s_bit << 1) | (size >> 1), false)
        }
        2 => {
            if size == 0 {
                (4, (qb << 1) | s_bit, false)
            } else if size == 1 && s_bit == 0 {
                (8, qb, false)
            } else {
                return None;
            }
        }
        _ => {
            if !load || s_bit != 0 {
                return None;
            }
            (1u32 << size, 0, true)
        }
    };
    let total = selem * es;
    let mut s = format!("{{ uint64_t a = {base}; ");
    for k in 0..selem {
        let reg = (rt + k) % 32;
        let off = k * es;
        if replicate {
            let lanes = (if q { 16 } else { 8 }) / es;
            s += &format!(
                "{{ BfVec t = {{0}}; for (int i = 0; i < {lanes}; i++) memcpy(&t.b[i * {es}], BF_HOST(a + {off}), {es}); c->v[{reg}] = t; }} "
            );
        } else if load {
            s += &format!("memcpy(&c->v[{reg}].b[{}], BF_HOST(a + {off}), {es}); ", index * es);
        } else {
            s += &format!("memcpy(BF_HOST(a + {off}), &c->v[{reg}].b[{}], {es}); ", index * es);
        }
    }
    s += &wb(total);
    s += " }";
    Some(s)
}

// ---------------------------------------------------------------- dispatch
pub fn decode_dp(w: u32) -> Option<String> {
    if w & 0xFF00_0000 == 0x1F00_0000 {
        return fp_3src(w);
    }
    if w & 0x7F20_0000 == 0x1E00_0000 {
        return fp_fixed(w);
    }
    if w & 0x7F20_0000 == 0x1E20_0000 {
        if bit(w, 29) {
            return None;
        }
        return match bits(w, 11, 10) {
            1 => fp_ccmp(w),
            2 => fp_2src(w),
            3 => fp_csel(w),
            _ => {
                if bit(w, 12) {
                    fp_imm(w)
                } else if bit(w, 13) {
                    fp_cmp(w)
                } else if bit(w, 14) {
                    fp_1src(w)
                } else if bits(w, 15, 10) == 0 {
                    fp_int(w)
                } else {
                    None
                }
            }
        };
    }
    if w & 0xFF3E_0C00 == 0x4E28_0800 {
        return crypto_aes(w);
    }
    if w & 0xFF20_8C00 == 0x5E00_0000 {
        return crypto_sha3(w);
    }
    if w & 0xFF3E_0C00 == 0x5E28_0800 {
        return crypto_sha2(w);
    }
    if bit(w, 31) {
        return None;
    }
    let scalar = bit(w, 30) && bits(w, 28, 24) & 0b11110 == 0b11110;
    if scalar {
        if w & 0xDF20_0400 == 0x5E20_0400 {
            return three_same(w, true);
        }
        if w & 0xDF3E_0C00 == 0x5E20_0800 {
            return two_misc(w, true);
        }
        if w & 0xDF3E_0C00 == 0x5E30_0800 {
            return scalar_pairwise(w);
        }
        if w & 0xDFE0_8400 == 0x5E00_0400 {
            return scalar_dup(w);
        }
        if w & 0xDF80_0400 == 0x5F00_0400 {
            return shift_imm(w, true);
        }
        if w & 0xDF00_0400 == 0x5F00_0000 {
            return indexed(w, true);
        }
        if w & 0xDF20_0C00 == 0x5E20_0000 {
            return three_diff(w, true);
        }
        return None;
    }
    if w & 0x9F20_0400 == 0x0E20_0400 {
        return three_same(w, false);
    }
    if w & 0x9F20_0C00 == 0x0E20_0000 {
        return three_diff(w, false);
    }
    if w & 0x9F3E_0C00 == 0x0E20_0800 {
        return two_misc(w, false);
    }
    if w & 0x9F3E_0C00 == 0x0E30_0800 {
        return across(w);
    }
    if w & 0x9FE0_8400 == 0x0E00_0400 {
        return copy(w);
    }
    if w & 0x9FF8_0400 == 0x0F00_0400 {
        return mod_imm(w);
    }
    if w & 0x9F80_0400 == 0x0F00_0400 {
        return shift_imm(w, false);
    }
    if w & 0x9F00_0400 == 0x0F00_0000 {
        return indexed(w, false);
    }
    if w & 0xBF20_8C00 == 0x0E00_0800 {
        return permute(w);
    }
    if w & 0xBF20_8400 == 0x2E00_0000 {
        return ext(w);
    }
    if w & 0xBF20_8C00 == 0x0E00_0000 {
        return tbl(w);
    }
    None
}

// ---------------------------------------------------------------- scalar floating point
fn fty(w: u32) -> Option<usize> {
    match bits(w, 23, 22) {
        0 => Some(0),
        1 => Some(1),
        _ => None,
    }
}
fn freg(sz: usize, n: u32) -> String {
    format!("c->v[{n}].{}[0]", F[sz])
}
fn fset(sz: usize, d: u32, e: &str) -> String {
    format!("bf_vset_{}(c, {d}, {e});", F[sz])
}

fn vfp_expand(imm8: u32, sz: usize) -> u64 {
    let sign = ((imm8 >> 7) & 1) as u64;
    let b6 = (imm8 >> 6) & 1;
    let lo = ((imm8 >> 4) & 3) as u64;
    let frac = (imm8 & 0xF) as u64;
    if sz == 0 {
        let exp = (((b6 ^ 1) as u64) << 7) | (if b6 == 1 { 0x7C } else { 0 }) | lo;
        (sign << 31) | (exp << 23) | (frac << 19)
    } else {
        let exp = (((b6 ^ 1) as u64) << 10) | (if b6 == 1 { 0x3FC } else { 0 }) | lo;
        (sign << 63) | (exp << 52) | (frac << 48)
    }
}

fn fp_imm(w: u32) -> Option<String> {
    let sz = fty(w)?;
    if bits(w, 9, 5) != 0 {
        return None;
    }
    let v = vfp_expand(bits(w, 20, 13), sz);
    let rd = bits(w, 4, 0);
    Some(if sz == 0 {
        format!("bf_vset_s(c, {rd}, 0x{v:x}u);")
    } else {
        format!("bf_vset_d(c, {rd}, 0x{v:x}ull);")
    })
}

fn fp_cmp(w: u32) -> Option<String> {
    let sz = fty(w)?;
    if bits(w, 15, 14) != 0 || bits(w, 2, 0) != 0 {
        return None;
    }
    let a = freg(sz, bits(w, 9, 5));
    let b = if bit(w, 3) { "0.0".to_string() } else { freg(sz, bits(w, 20, 16)) };
    Some(format!("c->nzcv = bf_fcmp((double){a}, (double){b});"))
}

fn fp_ccmp(w: u32) -> Option<String> {
    let sz = fty(w)?;
    let a = freg(sz, bits(w, 9, 5));
    let b = freg(sz, bits(w, 20, 16));
    let cond = bits(w, 15, 12);
    let nzcv = (bits(w, 3, 0) as u64) << 28;
    Some(format!("c->nzcv = BF_COND(c, {cond}) ? bf_fcmp((double){a}, (double){b}) : 0x{nzcv:x}ull;"))
}

fn fp_csel(w: u32) -> Option<String> {
    let sz = fty(w)?;
    let (n, m, d) = (bits(w, 9, 5), bits(w, 20, 16), bits(w, 4, 0));
    let cond = bits(w, 15, 12);
    let i = FI[sz];
    Some(format!(
        "{{ uint64_t t = BF_COND(c, {cond}) ? c->v[{n}].{i}[0] : c->v[{m}].{i}[0]; bf_vset_d(c, {d}, t); }}"
    ))
}

fn fp_2src(w: u32) -> Option<String> {
    let sz = fty(w)?;
    let a = freg(sz, bits(w, 9, 5));
    let b = freg(sz, bits(w, 20, 16));
    let s = FS[sz];
    let f = match bits(w, 15, 12) {
        0 => "fmul",
        1 => "fdiv",
        2 => "fadd",
        3 => "fsub",
        4 => "fmax",
        5 => "fmin",
        6 => "fmaxnm",
        7 => "fminnm",
        8 => return Some(fset(sz, bits(w, 4, 0), &format!("-bf_fmul_{s}({a}, {b})"))),
        _ => return None,
    };
    Some(fset(sz, bits(w, 4, 0), &format!("bf_{f}_{s}({a}, {b})")))
}

fn fp_3src(w: u32) -> Option<String> {
    let sz = fty(w)?;
    let n = freg(sz, bits(w, 9, 5));
    let m = freg(sz, bits(w, 20, 16));
    let a = freg(sz, bits(w, 14, 10));
    let s = FS[sz];
    let e = match (bit(w, 21), bit(w, 15)) {
        (false, false) => format!("bf_fma_{s}({n}, {m}, {a})"),
        (false, true) => format!("bf_fma_{s}(-{n}, {m}, {a})"),
        (true, false) => format!("bf_fma_{s}(-{n}, {m}, -{a})"),
        (true, true) => format!("bf_fma_{s}({n}, {m}, -{a})"),
    };
    Some(fset(sz, bits(w, 4, 0), &e))
}

fn fp_1src(w: u32) -> Option<String> {
    let ty = bits(w, 23, 22);
    let n = bits(w, 9, 5);
    let d = bits(w, 4, 0);
    let opc = bits(w, 20, 15);
    if ty == 3 {
        // half-precision source: only conversions (no FP16 arithmetic on ARMv8.0)
        let h = format!("bf_h2f(c->v[{n}].h[0])");
        return match opc {
            4 => Some(format!("bf_vset_f(c, {d}, {h});")),
            5 => Some(format!("bf_vset_fd(c, {d}, (double){h});")),
            _ => None,
        };
    }
    let sz = fty(w)?;
    let a = freg(sz, n);
    let s = FS[sz];
    let i = FI[sz];
    let sign = if sz == 0 { "0x80000000u" } else { "0x8000000000000000ull" };
    Some(match opc {
        0 => format!("bf_vset_d(c, {d}, c->v[{n}].{i}[0]);"),
        1 => format!("bf_vset_d(c, {d}, c->v[{n}].{i}[0] & ~({}){sign});", UT[2 + sz]),
        2 => format!("bf_vset_d(c, {d}, ({})(c->v[{n}].{i}[0] ^ {sign}));", UT[2 + sz]),
        3 => fset(sz, d, &format!("bf_fsqrt_{s}({a})")),
        4 if sz == 1 => format!("bf_vset_f(c, {d}, (float){a});"),
        5 if sz == 0 => format!("bf_vset_fd(c, {d}, (double){a});"),
        7 => {
            if sz == 0 {
                format!("bf_vset_h(c, {d}, bf_f2h({a}));")
            } else {
                format!("bf_vset_h(c, {d}, bf_d2h({a}));")
            }
        }
        8..=12 | 14 | 15 => {
            let mode = match opc {
                8 => 0,
                9 => 1,
                10 => 2,
                11 => 3,
                12 => 4,
                _ => 0,
            };
            fset(sz, d, &format!("bf_round_{s}({a}, {mode})"))
        }
        _ => return None,
    })
}

fn cvt_fn(signed: bool, to64: bool) -> &'static str {
    match (signed, to64) {
        (true, true) => "bf_cvt_s64",
        (false, true) => "bf_cvt_u64",
        (true, false) => "bf_cvt_s32",
        (false, false) => "bf_cvt_u32",
    }
}

fn fp_int(w: u32) -> Option<String> {
    let sf = bit(w, 31);
    let ty = bits(w, 23, 22);
    let rmode = bits(w, 20, 19);
    let opc = bits(w, 18, 16);
    let n = bits(w, 9, 5);
    let d = bits(w, 4, 0);
    let src = match ty {
        0 => format!("(double)c->v[{n}].f[0]"),
        1 => format!("c->v[{n}].fd[0]"),
        3 => format!("(double)bf_h2f(c->v[{n}].h[0])"),
        _ => String::new(),
    };
    match opc {
        0 | 1 | 4 | 5 => {
            if src.is_empty() || (opc >= 4 && rmode != 0) {
                return None;
            }
            let mode = if opc >= 4 { 4 } else { rmode };
            let f = cvt_fn(opc & 1 == 0, sf);
            Some(format!("c->x[{d}] = {f}({src}, {mode});"))
        }
        2 | 3 => {
            if rmode != 0 {
                return None;
            }
            let v = match (opc == 2, sf) {
                (true, true) => format!("(int64_t){}", rx(n)),
                (true, false) => format!("(int32_t){}", rw(n)),
                (false, true) => rx(n),
                (false, false) => rw(n),
            };
            match ty {
                0 => Some(format!("bf_vset_f(c, {d}, (float)({v}));")),
                1 => Some(format!("bf_vset_fd(c, {d}, (double)({v}));")),
                3 => Some(format!("bf_vset_h(c, {d}, bf_f2h((float)({v})));")),
                _ => None,
            }
        }
        6 => match (sf, ty, rmode) {
            (false, 0, 0) => Some(format!("c->x[{d}] = c->v[{n}].s[0];")),
            (true, 1, 0) => Some(format!("c->x[{d}] = c->v[{n}].d[0];")),
            (true, 2, 1) => Some(format!("c->x[{d}] = c->v[{n}].d[1];")),
            (_, 3, 0) => Some(format!("c->x[{d}] = c->v[{n}].h[0];")),
            _ => None,
        },
        7 => match (sf, ty, rmode) {
            (false, 0, 0) => Some(format!("bf_vset_s(c, {d}, {});", rw(n))),
            (true, 1, 0) => Some(format!("bf_vset_d(c, {d}, {});", rx(n))),
            (true, 2, 1) => Some(format!("c->v[{d}].d[1] = {};", rx(n))),
            (_, 3, 0) => Some(format!("bf_vset_h(c, {d}, (uint16_t){});", rx(n))),
            _ => None,
        },
        _ => None,
    }
}

fn fp_fixed(w: u32) -> Option<String> {
    let sf = bit(w, 31);
    let sz = fty(w)?;
    let rmode = bits(w, 20, 19);
    let opc = bits(w, 18, 16);
    let scale = bits(w, 15, 10);
    if !sf && scale < 32 {
        return None;
    }
    let fbits = 64 - scale;
    let n = bits(w, 9, 5);
    let d = bits(w, 4, 0);
    match (rmode, opc) {
        (3, 0) | (3, 1) => {
            let f = cvt_fn(opc == 0, sf);
            Some(format!("c->x[{d}] = {f}(ldexp((double){}, {fbits}), 3);", freg(sz, n)))
        }
        (0, 2) | (0, 3) => {
            let v = match (opc == 2, sf) {
                (true, true) => format!("(int64_t){}", rx(n)),
                (true, false) => format!("(int32_t){}", rw(n)),
                (false, true) => rx(n),
                (false, false) => rw(n),
            };
            Some(if sz == 0 {
                format!("bf_vset_f(c, {d}, ldexpf((float)({v}), -{fbits}));")
            } else {
                format!("bf_vset_fd(c, {d}, ldexp((double)({v}), -{fbits}));")
            })
        }
        _ => None,
    }
}

// ---------------------------------------------------------------- vector integer / FP: three same
fn fcmp_res(sz: usize, cond: &str) -> String {
    format!("R.{}[i] = ({cond}) ? ({})~0ull : 0;", FI[sz], UT[2 + sz])
}

fn pairwise_stmt(cnt: u32, field: &str, op: &str) -> String {
    let h = (cnt / 2).max(1);
    let x = format!("P->{field}[j]");
    let y = format!("P->{field}[j + 1]");
    format!(
        "const BfVec *P = i < {h} ? &A : &B; int j = (i % {h}) * 2; R.{field}[i] = {};",
        t3(op, &x, &y, "")
    )
}

fn three_same(w: u32, scalar: bool) -> Option<String> {
    let q = bit(w, 30) || scalar;
    let u = bit(w, 29);
    let size = bits(w, 23, 22) as usize;
    let (m, n, d) = (bits(w, 20, 16), bits(w, 9, 5), bits(w, 4, 0));
    let opcode = bits(w, 15, 11);
    let width = if bit(w, 30) && !scalar { 128 } else { 64 };
    if opcode == 3 {
        if scalar {
            return None;
        }
        let cnt = width / 64;
        let e = match (u, size) {
            (false, 0) => "A.d[i] & B.d[i]",
            (false, 1) => "A.d[i] & ~B.d[i]",
            (false, 2) => "A.d[i] | B.d[i]",
            (false, _) => "A.d[i] | ~B.d[i]",
            (true, 0) => "A.d[i] ^ B.d[i]",
            (true, 1) => "(D.d[i] & A.d[i]) | (~D.d[i] & B.d[i])",
            (true, 2) => "(D.d[i] & ~B.d[i]) | (A.d[i] & B.d[i])",
            (true, _) => "(D.d[i] & B.d[i]) | (A.d[i] & ~B.d[i])",
        };
        return Some(vl(d, n, m, cnt, false, &format!("R.d[i] = {e};")));
    }
    if opcode >= 0x18 {
        let sz = size & 1;
        let hi = size >> 1;
        if !q && sz == 1 {
            return None;
        }
        let cnt = if scalar { 1 } else { width / (32 << sz) };
        let f = F[sz];
        let s = FS[sz];
        let (a, b, dd) = (format!("A.{f}[i]"), format!("B.{f}[i]"), format!("D.{f}[i]"));
        let bin = |name: &str| format!("R.{f}[i] = bf_{name}_{s}({a}, {b});");
        let pair = |name: &str| pairwise_stmt(cnt, f, &format!("bf_{name}_{s}($x, $y)"));
        let stmt = match (u, opcode, hi) {
            (false, 0x18, 0) if !scalar => bin("fmaxnm"),
            (false, 0x18, 1) if !scalar => bin("fminnm"),
            (false, 0x19, 0) if !scalar => format!("R.{f}[i] = bf_fma_{s}({a}, {b}, {dd});"),
            (false, 0x19, 1) if !scalar => format!("R.{f}[i] = bf_fma_{s}(-{a}, {b}, {dd});"),
            (false, 0x1A, 0) if !scalar => bin("fadd"),
            (false, 0x1A, 1) if !scalar => bin("fsub"),
            (false, 0x1B, 0) => bin("fmulx"),
            (false, 0x1C, 0) => fcmp_res(sz, &format!("{a} == {b}")),
            (false, 0x1E, 0) if !scalar => bin("fmax"),
            (false, 0x1E, 1) if !scalar => bin("fmin"),
            (false, 0x1F, 0) => bin("frecps"),
            (false, 0x1F, 1) => bin("frsqrts"),
            (true, 0x18, 0) if !scalar => pair("fmaxnm"),
            (true, 0x18, 1) if !scalar => pair("fminnm"),
            (true, 0x1A, 0) if !scalar => pair("fadd"),
            (true, 0x1A, 1) => format!("R.{f}[i] = fabs(bf_fsub_{s}({a}, {b}));"),
            (true, 0x1B, 0) if !scalar => bin("fmul"),
            (true, 0x1C, 0) => fcmp_res(sz, &format!("{a} >= {b}")),
            (true, 0x1C, 1) => fcmp_res(sz, &format!("{a} > {b}")),
            (true, 0x1D, 0) => fcmp_res(sz, &format!("fabs({a}) >= fabs({b})")),
            (true, 0x1D, 1) => fcmp_res(sz, &format!("fabs({a}) > fabs({b})")),
            (true, 0x1E, 0) if !scalar => pair("fmax"),
            (true, 0x1E, 1) if !scalar => pair("fmin"),
            (true, 0x1F, 0) if !scalar => bin("fdiv"),
            _ => return None,
        };
        return Some(vl(d, n, m, cnt, false, &stmt));
    }
    let eb = 8u32 << size;
    if size == 3 && !q {
        return None;
    }
    let cnt = if scalar { 1 } else { width / eb };
    let (uf, sf, ut) = (U[size], S[size], UT[size]);
    let (ua, ub, ud) = (format!("A.{uf}[i]"), format!("B.{uf}[i]"), format!("D.{uf}[i]"));
    let (sa, sb) = (format!("A.{sf}[i]"), format!("B.{sf}[i]"));
    let (xa, xb) = if u { (ua.clone(), ub.clone()) } else { (sa.clone(), sb.clone()) };
    let wide = if u { "(unsigned __int128)" } else { "(__int128)" };
    let all = ones(eb);
    let scalar_ok = matches!(opcode, 1 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 16 | 17 | 22);
    if scalar && (!scalar_ok || (size != 3 && !matches!(opcode, 1 | 5 | 9 | 11 | 22))) {
        return None;
    }
    let sat = if u { "bf_sat_u" } else { "bf_sat_s" };
    let absdiff = format!("({ut})({wide}{xa} > {wide}{xb} ? {wide}{xa} - {xb} : {wide}{xb} - {xa})");
    let stmt = match (u, opcode) {
        (_, 0) => format!("R.{uf}[i] = ({ut})(({wide}{xa} + {xb}) >> 1);"),
        (_, 1) => format!("R.{uf}[i] = ({ut}){sat}((__int128){xa} + (__int128){xb}, {eb});"),
        (_, 2) => format!("R.{uf}[i] = ({ut})(({wide}{xa} + {xb} + 1) >> 1);"),
        (_, 4) => format!("R.{uf}[i] = ({ut})(((__int128){xa} - (__int128){xb}) >> 1);"),
        (_, 5) => format!("R.{uf}[i] = ({ut}){sat}((__int128){xa} - (__int128){xb}, {eb});"),
        (_, 6) => format!("R.{uf}[i] = ({xa} > {xb}) ? {all} : 0;"),
        (_, 7) => format!("R.{uf}[i] = ({xa} >= {xb}) ? {all} : 0;"),
        (_, 8..=11) => format!(
            "R.{uf}[i] = ({ut})bf_vshl({ua}, (int8_t){ub}, {eb}, {}, {}, {});",
            !u as u32,
            (opcode >> 1) & 1,
            opcode & 1
        ),
        (_, 12) => format!("R.{uf}[i] = {xa} > {xb} ? {xa} : {xb};"),
        (_, 13) => format!("R.{uf}[i] = {xa} < {xb} ? {xa} : {xb};"),
        (_, 14) => format!("R.{uf}[i] = {absdiff};"),
        (_, 15) => format!("R.{uf}[i] = ({ut})({ud} + {absdiff});"),
        (false, 16) => format!("R.{uf}[i] = ({ut})({ua} + {ub});"),
        (true, 16) => format!("R.{uf}[i] = ({ut})({ua} - {ub});"),
        (false, 17) => format!("R.{uf}[i] = ({ua} & {ub}) ? {all} : 0;"),
        (true, 17) => format!("R.{uf}[i] = ({ua} == {ub}) ? {all} : 0;"),
        (false, 18) if size < 3 => format!("R.{uf}[i] = ({ut})({ud} + {ua} * {ub});"),
        (true, 18) if size < 3 => format!("R.{uf}[i] = ({ut})({ud} - {ua} * {ub});"),
        (false, 19) if size < 3 => format!("R.{uf}[i] = ({ut})({ua} * {ub});"),
        (true, 19) if size == 0 => format!("R.b[i] = bf_pmul8({ua}, {ub});"),
        (_, 20) if size < 3 => pairwise_stmt(cnt, if u { uf } else { sf }, "($x > $y ? $x : $y)"),
        (_, 21) if size < 3 => pairwise_stmt(cnt, if u { uf } else { sf }, "($x < $y ? $x : $y)"),
        (false, 22) | (true, 22) if size == 1 || size == 2 => {
            let rnd = if u { format!(" + ((__int128)1 << {})", eb - 1) } else { String::new() };
            format!("R.{uf}[i] = ({ut})bf_sat_s((2 * (__int128){sa} * {sb}{rnd}) >> {eb}, {eb});")
        }
        (false, 23) => pairwise_stmt(cnt, uf, &format!("({ut})($x + $y)")),
        _ => return None,
    };
    Some(vl(d, n, m, cnt, false, &stmt))
}

// ---------------------------------------------------------------- two-register misc
fn two_misc(w: u32, scalar: bool) -> Option<String> {
    let q = bit(w, 30) && !scalar;
    let u = bit(w, 29);
    let size = bits(w, 23, 22) as usize;
    let opcode = bits(w, 16, 12);
    let (n, d) = (bits(w, 9, 5), bits(w, 4, 0));
    let width = if q { 128 } else { 64 };
    let eb = 8u32 << size;
    // FP group
    let fp = opcode >= 0x16 || (opcode >= 0x0C && opcode <= 0x0F && size >= 2) || (opcode == 0x1F);
    if fp && !(opcode >= 0x16 && opcode <= 0x17) {
        let sz = size & 1;
        let hi = size >> 1;
        if !scalar && !q && sz == 1 {
            return None;
        }
        let cnt = if scalar { 1 } else { width / (32 << sz) };
        let (f, fi, s) = (F[sz], FI[sz], FS[sz]);
        let a = format!("A.{f}[i]");
        let to64 = sz == 1;
        let stmt = match (u, opcode, hi) {
            (_, 0x18, _) | (_, 0x19, _) if !scalar => {
                let mode = match (u, opcode, hi) {
                    (false, 0x18, 0) => 0,
                    (false, 0x18, 1) => 1,
                    (false, 0x19, 0) => 2,
                    (false, 0x19, 1) => 3,
                    (true, 0x18, 0) => 4,
                    (true, 0x19, _) => 0,
                    _ => return None,
                };
                format!("R.{f}[i] = bf_round_{s}({a}, {mode});")
            }
            (_, 0x1A, _) | (_, 0x1B, _) | (_, 0x1C, 0) => {
                let mode = match (opcode, hi) {
                    (0x1A, 0) => 0,
                    (0x1A, 1) => 1,
                    (0x1B, 0) => 2,
                    (0x1B, 1) => 3,
                    _ => 4,
                };
                format!("R.{fi}[i] = {}((double){a}, {mode});", cvt_fn(!u, to64))
            }
            (false, 0x1C, 1) if sz == 0 && !scalar => "R.s[i] = bf_urecpe(A.s[i]);".to_string(),
            (true, 0x1C, 1) if sz == 0 && !scalar => "R.s[i] = bf_ursqrte(A.s[i]);".to_string(),
            (_, 0x1D, 0) => {
                let ty = FT[sz];
                if u {
                    format!("R.{f}[i] = ({ty})A.{fi}[i];")
                } else {
                    format!("R.{f}[i] = ({ty})A.{}[i];", S[2 + sz])
                }
            }
            (false, 0x1D, 1) => format!("R.{f}[i] = bf_frecpe_{s}({a});"),
            (true, 0x1D, 1) => format!("R.{f}[i] = bf_frsqrte_{s}({a});"),
            (false, 0x1F, 1) if scalar => format!("R.{f}[i] = bf_frecpx_{s}({a});"),
            (true, 0x1F, 1) if !scalar => format!("R.{f}[i] = bf_fsqrt_{s}({a});"),
            (false, 0x0C, 1) => fcmp_res(sz, &format!("{a} > 0")),
            (true, 0x0C, 1) => fcmp_res(sz, &format!("{a} >= 0")),
            (false, 0x0D, 1) => fcmp_res(sz, &format!("{a} == 0")),
            (true, 0x0D, 1) => fcmp_res(sz, &format!("{a} <= 0")),
            (false, 0x0E, 1) => fcmp_res(sz, &format!("{a} < 0")),
            (false, 0x0F, 1) if !scalar => {
                format!("R.{fi}[i] = A.{fi}[i] & ~(({})1 << {});", UT[2 + sz], 31 + 32 * sz)
            }
            (true, 0x0F, 1) if !scalar => {
                format!("R.{fi}[i] = A.{fi}[i] ^ (({})1 << {});", UT[2 + sz], 31 + 32 * sz)
            }
            _ => return None,
        };
        return Some(vl(d, n, n, cnt, false, &stmt));
    }
    if opcode == 0x16 || opcode == 0x17 {
        if scalar {
            return None;
        }
        let sz = size & 1;
        let half = if q { 1 } else { 0 };
        if opcode == 0x16 {
            if u {
                return None;
            }
            // FCVTN: narrow into the low (or, for FCVTN2, high) half
            let (cnt, stmt) = if sz == 0 {
                (4, format!("R.h[i + {}] = bf_f2h(A.f[i]);", half * 4))
            } else {
                (2, format!("R.f[i + {}] = (float)A.fd[i];", half * 2))
            };
            return Some(vl(d, n, n, cnt, q, &stmt));
        }
        if u {
            return None;
        }
        let (cnt, stmt) = if sz == 0 {
            (4, format!("R.f[i] = bf_h2f(A.h[i + {}]);", half * 4))
        } else {
            (2, format!("R.fd[i] = (double)A.f[i + {}];", half * 2))
        };
        return Some(vl(d, n, n, cnt, false, &stmt));
    }
    let (uf, sf, ut) = (U[size], S[size], UT[size]);
    let (ua, sa) = (format!("A.{uf}[i]"), format!("A.{sf}[i]"));
    let all = ones(eb);
    let cnt = if scalar { 1 } else { width / eb };
    if size == 3 && !q && !scalar && !matches!(opcode, 0x12 | 0x13 | 0x14) {
        return None;
    }
    if scalar && !matches!(opcode, 7 | 8 | 9 | 10 | 11 | 0x12 | 0x14) {
        return None;
    }
    if scalar && matches!(opcode, 8..=11) && size != 3 {
        return None;
    }
    let stmt = match (u, opcode) {
        (false, 0) if size < 3 => format!("R.{uf}[i] = A.{uf}[i ^ {}];", 64 / eb - 1),
        (true, 0) if size < 2 => format!("R.{uf}[i] = A.{uf}[i ^ {}];", 32 / eb - 1),
        (false, 1) if size == 0 => "R.b[i] = A.b[i ^ 1];".to_string(),
        (_, 2) | (_, 6) if size < 3 => {
            let cnt2 = width / (2 * eb);
            let (wf, wt) = (U[size + 1], UT[size + 1]);
            let src = if u { uf } else { sf };
            let acc = if opcode == 6 { format!("D.{wf}[i] + ") } else { String::new() };
            let st = format!(
                "R.{wf}[i] = ({wt})({acc}({wt})(int64_t)A.{src}[2 * i] + ({wt})(int64_t)A.{src}[2 * i + 1]);"
            );
            let st = if u { st.replace("(int64_t)", "") } else { st };
            return Some(vl(d, n, n, cnt2, false, &st));
        }
        (false, 4) if size < 3 => format!("R.{uf}[i] = ({ut})(bf_cls32((uint32_t)(int32_t){sa}) - {});", 32 - eb),
        (true, 4) if size < 3 => format!("R.{uf}[i] = ({ut})(bf_clz32((uint32_t){ua}) - {});", 32 - eb),
        (false, 5) if size == 0 => "R.b[i] = (uint8_t)__builtin_popcount(A.b[i]);".to_string(),
        (true, 5) if size == 0 => "R.b[i] = (uint8_t)~A.b[i];".to_string(),
        (true, 5) if size == 1 => {
            let c8 = width / 8;
            return Some(vl(d, n, n, c8, false, "R.b[i] = (uint8_t)(bf_rbit32(A.b[i]) >> 24);"));
        }
        (false, 7) => format!("R.{uf}[i] = ({ut})bf_sat_s({sa} < 0 ? -(__int128){sa} : (__int128){sa}, {eb});"),
        (true, 7) => format!("R.{uf}[i] = ({ut})bf_sat_s(-(__int128){sa}, {eb});"),
        (false, 8) => format!("R.{uf}[i] = ({sa} > 0) ? {all} : 0;"),
        (true, 8) => format!("R.{uf}[i] = ({sa} >= 0) ? {all} : 0;"),
        (false, 9) => format!("R.{uf}[i] = ({sa} == 0) ? {all} : 0;"),
        (true, 9) => format!("R.{uf}[i] = ({sa} <= 0) ? {all} : 0;"),
        (false, 10) => format!("R.{uf}[i] = ({sa} < 0) ? {all} : 0;"),
        (false, 11) => format!("R.{uf}[i] = ({ut})({sa} < 0 ? 0 - {ua} : {ua});"),
        (true, 11) => format!("R.{uf}[i] = ({ut})(0 - {ua});"),
        (_, 0x12) | (_, 0x14) if size < 3 => {
            // XTN / SQXTN / UQXTN / SQXTUN: narrow 2*esize -> esize
            let cntn = if scalar { 1 } else { 64 / eb };
            let off = if q { cntn } else { 0 };
            let (wu, ws) = (U[size + 1], S[size + 1]);
            let v = match (u, opcode) {
                (false, 0x12) => format!("({ut})A.{wu}[i]"),
                (true, 0x12) => format!("({ut})bf_sat_u(A.{ws}[i], {eb})"),
                (false, _) => format!("({ut})bf_sat_s(A.{ws}[i], {eb})"),
                (true, _) => format!("({ut})bf_sat_u(A.{wu}[i], {eb})"),
            };
            if scalar && !u && opcode == 0x12 {
                return None;
            }
            return Some(vl(d, n, n, cntn, q, &format!("R.{uf}[i + {off}] = {v};")));
        }
        (true, 0x13) if size < 3 => {
            let cntn = 64 / eb;
            let off = if q { cntn } else { 0 };
            let (wf, wt) = (U[size + 1], UT[size + 1]);
            return Some(vl(d, n, n, cntn, false, &format!("R.{wf}[i] = ({wt})(({wt})A.{uf}[i + {off}] << {eb});")));
        }
        _ => return None,
    };
    Some(vl(d, n, n, cnt, false, &stmt))
}

fn scalar_pairwise(w: u32) -> Option<String> {
    let u = bit(w, 29);
    let size = bits(w, 23, 22) as usize;
    let opcode = bits(w, 16, 12);
    let (n, d) = (bits(w, 9, 5), bits(w, 4, 0));
    let stmt = if !u && opcode == 0x1B && size == 3 {
        "R.d[0] = A.d[0] + A.d[1];".to_string()
    } else if u {
        let sz = size & 1;
        let hi = size >> 1;
        let (f, s) = (F[sz], FS[sz]);
        let name = match (opcode, hi) {
            (0x0C, 0) => "fmaxnm",
            (0x0C, 1) => "fminnm",
            (0x0D, 0) => "fadd",
            (0x0F, 0) => "fmax",
            (0x0F, 1) => "fmin",
            _ => return None,
        };
        format!("R.{f}[0] = bf_{name}_{s}(A.{f}[0], A.{f}[1]);")
    } else {
        return None;
    };
    Some(vl(d, n, n, 1, false, &stmt.replace("[0] =", "[i] =")))
}

fn scalar_dup(w: u32) -> Option<String> {
    if bit(w, 29) || bits(w, 14, 11) != 0 {
        return None;
    }
    let imm5 = bits(w, 20, 16);
    let size = imm5.trailing_zeros() as usize;
    if size > 3 {
        return None;
    }
    let idx = imm5 >> (size + 1);
    let f = U[size];
    Some(vl(bits(w, 4, 0), bits(w, 9, 5), 0, 1, false, &format!("R.{f}[i] = A.{f}[{idx}];")))
}

// ---------------------------------------------------------------- across lanes
fn across(w: u32) -> Option<String> {
    let q = bit(w, 30);
    let u = bit(w, 29);
    let size = bits(w, 23, 22) as usize;
    let opcode = bits(w, 16, 12);
    let (n, d) = (bits(w, 9, 5), bits(w, 4, 0));
    let width = if q { 128 } else { 64 };
    if u && (opcode == 0x0C || opcode == 0x0F) {
        if !q || size & 1 != 0 {
            return None;
        }
        let name = match (opcode, size >> 1) {
            (0x0C, 0) => "fmaxnm",
            (0x0C, _) => "fminnm",
            (0x0F, 0) => "fmax",
            _ => "fmin",
        };
        return Some(format!(
            "{{ BfVec A = c->v[{n}]; float r = bf_{name}_f(bf_{name}_f(A.f[0], A.f[1]), bf_{name}_f(A.f[2], A.f[3])); bf_vset_f(c, {d}, r); }}"
        ));
    }
    if size == 3 || (size == 2 && !q) {
        return None;
    }
    let eb = 8u32 << size;
    let cnt = width / eb;
    let src = if u { U[size] } else { S[size] };
    let acc_t = if u { "uint64_t" } else { "int64_t" };
    let (init, step, out) = match opcode {
        0x03 => ("0", "acc += A.$f[i];".to_string(), U[size + 1]),
        0x0A => ("A.$f[0]", "if (A.$f[i] > acc) acc = A.$f[i];".to_string(), U[size]),
        0x1A => ("A.$f[0]", "if (A.$f[i] < acc) acc = A.$f[i];".to_string(), U[size]),
        0x1B if !u => ("0", "acc += A.$f[i];".to_string(), U[size]),
        _ => return None,
    };
    let init = init.replace("$f", src);
    let step = step.replace("$f", src);
    Some(format!(
        "{{ BfVec A = c->v[{n}]; {acc_t} acc = {init}; for (int i = 0; i < {cnt}; i++) {{ {step} }} \
         BfVec R = {{0}}; R.{out}[0] = acc; c->v[{d}] = R; }}"
    ))
}

// ---------------------------------------------------------------- copy, immediates, permutes
fn copy(w: u32) -> Option<String> {
    let q = bit(w, 30);
    let op = bit(w, 29);
    let imm5 = bits(w, 20, 16);
    let imm4 = bits(w, 14, 11);
    let (n, d) = (bits(w, 9, 5), bits(w, 4, 0));
    let size = imm5.trailing_zeros() as usize;
    if size > 3 {
        return None;
    }
    let idx = imm5 >> (size + 1);
    let (f, sf, ut) = (U[size], S[size], UT[size]);
    let eb = 8u32 << size;
    let cnt = (if q { 128 } else { 64 }) / eb;
    if op {
        if !q {
            return None;
        }
        let idx2 = imm4 >> size;
        return Some(format!("{{ {ut} t = c->v[{n}].{f}[{idx2}]; c->v[{d}].{f}[{idx}] = t; }}"));
    }
    match imm4 {
        0 => {
            if size == 3 && !q {
                return None;
            }
            Some(vl(d, n, n, cnt, false, &format!("R.{f}[i] = A.{f}[{idx}];")))
        }
        1 => {
            if size == 3 && !q {
                return None;
            }
            Some(format!(
                "{{ {ut} t = ({ut}){}; BfVec R = {{0}}; for (int i = 0; i < {cnt}; i++) R.{f}[i] = t; c->v[{d}] = R; }}",
                rx(n)
            ))
        }
        3 => Some(format!("c->v[{d}].{f}[{idx}] = ({ut}){};", rx(n))),
        5 => {
            if size >= 2 + q as usize {
                return None;
            }
            if q {
                Some(format!("c->x[{d}] = (uint64_t)(int64_t)c->v[{n}].{sf}[{idx}];"))
            } else {
                Some(format!("c->x[{d}] = (uint32_t)(int32_t)c->v[{n}].{sf}[{idx}];"))
            }
        }
        7 => {
            if (size == 3) != q {
                return None;
            }
            Some(format!("c->x[{d}] = c->v[{n}].{f}[{idx}];"))
        }
        _ => None,
    }
}

fn mod_imm(w: u32) -> Option<String> {
    let q = bit(w, 30);
    let op = bit(w, 29);
    let cmode = bits(w, 15, 12);
    if bit(w, 11) {
        return None;
    }
    let d = bits(w, 4, 0);
    let imm8 = ((bits(w, 18, 16) << 5) | bits(w, 9, 5)) as u64;
    let rep32 = |v: u64| v | (v << 32);
    let rep16 = |v: u64| v * 0x0001_0001_0001_0001;
    let (imm, kind) = match cmode >> 1 {
        0..=3 => {
            let v = rep32(imm8 << (8 * (cmode >> 1)));
            (v, if cmode & 1 == 0 { if op { 1 } else { 0 } } else if op { 3 } else { 2 })
        }
        4 | 5 => {
            let v = rep16(imm8 << (8 * ((cmode >> 1) & 1)));
            (v, if cmode & 1 == 0 { if op { 1 } else { 0 } } else if op { 3 } else { 2 })
        }
        6 => {
            let v = if cmode & 1 == 0 { (imm8 << 8) | 0xFF } else { (imm8 << 16) | 0xFFFF };
            (rep32(v), if op { 1 } else { 0 })
        }
        _ => {
            if cmode & 1 == 0 {
                if !op {
                    (imm8 * 0x0101_0101_0101_0101, 0)
                } else {
                    let mut v = 0u64;
                    for i in 0..8 {
                        if (imm8 >> i) & 1 == 1 {
                            v |= 0xFF << (8 * i);
                        }
                    }
                    (v, 0)
                }
            } else if !op {
                (rep32(vfp_expand(imm8 as u32, 0)), 0)
            } else {
                if !q {
                    return None;
                }
                (vfp_expand(imm8 as u32, 1), 0)
            }
        }
    };
    let cnt = if q { 2 } else { 1 };
    Some(match kind {
        0 | 1 => {
            let v = if kind == 1 { !imm } else { imm };
            if q {
                format!("c->v[{d}].d[0] = 0x{v:x}ull; c->v[{d}].d[1] = 0x{v:x}ull;")
            } else {
                format!("bf_vset_d(c, {d}, 0x{v:x}ull);")
            }
        }
        2 => vl(d, d, d, cnt, false, &format!("R.d[i] = D.d[i] | 0x{imm:x}ull;")),
        _ => vl(d, d, d, cnt, false, &format!("R.d[i] = D.d[i] & ~0x{imm:x}ull;")),
    })
}

fn permute(w: u32) -> Option<String> {
    let q = bit(w, 30);
    let size = bits(w, 23, 22) as usize;
    if size == 3 && !q {
        return None;
    }
    let (m, n, d) = (bits(w, 20, 16), bits(w, 9, 5), bits(w, 4, 0));
    let cnt = (if q { 128 } else { 64 }) / (8u32 << size);
    let h = cnt / 2;
    let f = U[size];
    let stmt = match bits(w, 14, 12) {
        1 => format!("R.{f}[i] = i < {h} ? A.{f}[2 * i] : B.{f}[2 * (i - {h})];"),
        5 => format!("R.{f}[i] = i < {h} ? A.{f}[2 * i + 1] : B.{f}[2 * (i - {h}) + 1];"),
        2 => format!("R.{f}[i] = (i & 1) == 0 ? A.{f}[i] : B.{f}[i - 1];"),
        6 => format!("R.{f}[i] = (i & 1) == 0 ? A.{f}[i + 1] : B.{f}[i];"),
        3 => format!("R.{f}[i] = (i & 1) == 0 ? A.{f}[i / 2] : B.{f}[i / 2];"),
        7 => format!("R.{f}[i] = (i & 1) == 0 ? A.{f}[{h} + i / 2] : B.{f}[{h} + i / 2];"),
        _ => return None,
    };
    Some(vl(d, n, m, cnt, false, &stmt))
}

fn ext(w: u32) -> Option<String> {
    let q = bit(w, 30);
    let imm4 = bits(w, 14, 11);
    if !q && imm4 >= 8 {
        return None;
    }
    let (m, n, d) = (bits(w, 20, 16), bits(w, 9, 5), bits(w, 4, 0));
    let cnt = if q { 16 } else { 8 };
    Some(vl(
        d,
        n,
        m,
        cnt,
        false,
        &format!("R.b[i] = (i + {imm4} < {cnt}) ? A.b[i + {imm4}] : B.b[i + {imm4} - {cnt}];"),
    ))
}

fn tbl(w: u32) -> Option<String> {
    let q = bit(w, 30);
    let (m, n, d) = (bits(w, 20, 16), bits(w, 9, 5), bits(w, 4, 0));
    let len = bits(w, 14, 13) + 1;
    let tbx = bit(w, 12);
    let cnt = if q { 16 } else { 8 };
    let mut s = String::from("uint8_t T[64]; ");
    for k in 0..len {
        s += &format!("memcpy(T + {}, &c->v[{}], 16); ", k * 16, (n + k) % 32);
    }
    let miss = if tbx { "D.b[i]" } else { "0" };
    let body = vl(d, n, m, cnt, false, &format!("R.b[i] = B.b[i] < {} ? T[B.b[i]] : {miss};", len * 16));
    Some(format!("{{ {s}{body} }}"))
}

// ---------------------------------------------------------------- shifts by immediate
fn shift_imm(w: u32, scalar: bool) -> Option<String> {
    let q = bit(w, 30) && !scalar;
    let u = bit(w, 29);
    let immh = bits(w, 22, 19);
    if immh == 0 {
        return None;
    }
    let immhb = bits(w, 22, 16);
    let opcode = bits(w, 15, 11);
    let (n, d) = (bits(w, 9, 5), bits(w, 4, 0));
    let hsb = 31 - immh.leading_zeros();
    let size = hsb as usize;
    let eb = 8u32 << hsb;
    let width = if q { 128 } else { 64 };
    let rsh = 2 * eb - immhb;
    let lsh = immhb - eb;
    let (uf, sf, ut) = (U[size], S[size], UT[size]);
    let (ua, sa, ud) = (format!("A.{uf}[i]"), format!("A.{sf}[i]"), format!("D.{uf}[i]"));
    let cnt = if scalar { 1 } else { width / eb };
    let simple = matches!(opcode, 0 | 2 | 4 | 6 | 8 | 10);
    if simple && ((scalar && size != 3) || (!scalar && size == 3 && !q)) {
        return None;
    }
    let shr = |round: u32| format!("({ut})bf_vshl({ua}, -{rsh}, {eb}, {}, {round}, 0)", !u as u32);
    let stmt = match (u, opcode) {
        (_, 0) => format!("R.{uf}[i] = {};", shr(0)),
        (_, 2) => format!("R.{uf}[i] = ({ut})({ud} + {});", shr(0)),
        (_, 4) => format!("R.{uf}[i] = {};", shr(1)),
        (_, 6) => format!("R.{uf}[i] = ({ut})({ud} + {});", shr(1)),
        (true, 8) => {
            if rsh == eb {
                format!("R.{uf}[i] = {ud};")
            } else {
                let mask = (u64::MAX >> (64 - eb)) >> rsh;
                format!("R.{uf}[i] = ({ut})(({ud} & ~({ut})0x{mask:x}ull) | ({ua} >> {rsh}));")
            }
        }
        (false, 10) => format!("R.{uf}[i] = ({ut})((uint64_t){ua} << {lsh});"),
        (true, 10) => {
            let mask = (u64::MAX >> (64 - eb)) << lsh & (u64::MAX >> (64 - eb));
            format!("R.{uf}[i] = ({ut})(({ud} & ~({ut})0x{mask:x}ull) | ((uint64_t){ua} << {lsh}));")
        }
        (_, 14) => format!("R.{uf}[i] = ({ut})bf_vshl({ua}, {lsh}, {eb}, {}, 0, 1);", !u as u32),
        (true, 12) => format!("R.{uf}[i] = ({ut})bf_sat_u((__int128){sa} << {lsh}, {eb});"),
        (_, 0x10..=0x13) => {
            if size == 3 {
                return None;
            }
            let rsh_n = 2 * eb - immhb;
            let cntn = if scalar { 1 } else { 64 / eb };
            let off = if q { cntn } else { 0 };
            let (wu, ws) = (U[size + 1], S[size + 1]);
            let round = opcode & 1 == 1;
            let src_signed = !(u && opcode >= 0x12);
            let src = if src_signed && !(opcode <= 0x11 && !u) {
                format!("(__int128)A.{ws}[i]")
            } else {
                format!("(__int128)A.{wu}[i]")
            };
            let rnd = if round { format!(" + ((__int128)1 << {})", rsh_n - 1) } else { String::new() };
            let v = format!("(({src}{rnd}) >> {rsh_n})");
            let val = match (u, opcode >> 1) {
                (false, 8) => format!("({ut}){v}"),
                (false, 9) => format!("({ut})bf_sat_s({v}, {eb})"),
                (true, 8) => format!("({ut})bf_sat_u({v}, {eb})"),
                (true, _) => format!("({ut})bf_sat_u({v}, {eb})"),
                _ => return None,
            };
            if scalar && !u && opcode >> 1 == 8 {
                return None;
            }
            return Some(vl(d, n, n, cntn, q, &format!("R.{uf}[i + {off}] = {val};")));
        }
        (_, 0x14) => {
            if size == 3 || scalar {
                return None;
            }
            let cntn = 64 / eb;
            let off = if q { cntn } else { 0 };
            let (wf, wt) = (U[size + 1], UT[size + 1]);
            let src = if u { format!("A.{uf}[i + {off}]") } else { format!("(int64_t)A.{sf}[i + {off}]") };
            return Some(vl(d, n, n, cntn, false, &format!("R.{wf}[i] = ({wt})((uint64_t)({src}) << {lsh});")));
        }
        (_, 0x1C) | (_, 0x1F) => {
            if size < 2 || (!scalar && size == 3 && !q) {
                return None;
            }
            let sz = size - 2;
            let fbits = rsh;
            let (f, fi) = (F[sz], FI[sz]);
            if opcode == 0x1C {
                let v = if u { format!("A.{fi}[i]") } else { format!("A.{}[i]", S[size]) };
                if sz == 0 {
                    format!("R.f[i] = ldexpf((float){v}, -{fbits});")
                } else {
                    format!("R.fd[i] = ldexp((double){v}, -{fbits});")
                }
            } else {
                format!("R.{fi}[i] = {}(ldexp((double)A.{f}[i], {fbits}), 3);", cvt_fn(!u, sz == 1))
            }
        }
        _ => return None,
    };
    Some(vl(d, n, n, cnt, false, &stmt))
}

// ---------------------------------------------------------------- by element, three different
fn indexed(w: u32, scalar: bool) -> Option<String> {
    let q = bit(w, 30) && !scalar;
    let u = bit(w, 29);
    let size = bits(w, 23, 22) as usize;
    let (l, mbit, h) = (bits(w, 21, 21), bits(w, 20, 20), bits(w, 11, 11));
    let rm4 = bits(w, 19, 16);
    let opcode = bits(w, 15, 12);
    let (n, d) = (bits(w, 9, 5), bits(w, 4, 0));
    let width = if q { 128 } else { 64 };
    let is_fp = matches!((u, opcode), (false, 1) | (false, 5) | (false, 9) | (true, 9));
    if is_fp {
        if size < 2 {
            return None;
        }
        let sz = size & 1;
        let (idx, m) = if sz == 0 { ((h << 1) | l, (mbit << 4) | rm4) } else {
            if l == 1 {
                return None;
            }
            (h, (mbit << 4) | rm4)
        };
        if !scalar && !q && sz == 1 {
            return None;
        }
        let cnt = if scalar { 1 } else { width / (32 << sz) };
        let (f, s) = (F[sz], FS[sz]);
        let e = format!("B.{f}[{idx}]");
        let a = format!("A.{f}[i]");
        let stmt = match (u, opcode) {
            (false, 1) => format!("R.{f}[i] = bf_fma_{s}({a}, {e}, D.{f}[i]);"),
            (false, 5) => format!("R.{f}[i] = bf_fma_{s}(-{a}, {e}, D.{f}[i]);"),
            (false, 9) => format!("R.{f}[i] = bf_fmul_{s}({a}, {e});"),
            _ => format!("R.{f}[i] = bf_fmulx_{s}({a}, {e});"),
        };
        return Some(vl(d, n, m, cnt, false, &stmt));
    }
    let (idx, m) = match size {
        1 => ((h << 2) | (l << 1) | mbit, rm4),
        2 => ((h << 1) | l, (mbit << 4) | rm4),
        _ => return None,
    };
    let eb = 8u32 << size;
    let (uf, sf, ut) = (U[size], S[size], UT[size]);
    let cnt = if scalar { 1 } else { width / eb };
    let long = matches!(opcode, 2 | 3 | 6 | 7 | 10 | 11);
    if long {
        let cntl = if scalar { 1 } else { 64 / eb };
        let off = if q { cntl } else { 0 };
        let (wf, wt) = (U[size + 1], UT[size + 1]);
        let prod = if u {
            format!("(({wt})A.{uf}[i + {off}] * ({wt})B.{uf}[{idx}])")
        } else {
            format!("({wt})((int64_t)A.{sf}[i + {off}] * (int64_t)B.{sf}[{idx}])")
        };
        let sq = format!("bf_sat_s(2 * (__int128)A.{sf}[i + {off}] * B.{sf}[{idx}], {})", 2 * eb);
        let stmt = match (u, opcode) {
            (_, 2) if !scalar => format!("R.{wf}[i] = ({wt})(D.{wf}[i] + {prod});"),
            (_, 6) if !scalar => format!("R.{wf}[i] = ({wt})(D.{wf}[i] - {prod});"),
            (_, 10) if !scalar => format!("R.{wf}[i] = {prod};"),
            (false, 3) => format!("R.{wf}[i] = ({wt})bf_sat_s((__int128)D.{}[i] + {sq}, {});", S[size + 1], 2 * eb),
            (false, 7) => format!("R.{wf}[i] = ({wt})bf_sat_s((__int128)D.{}[i] - {sq}, {});", S[size + 1], 2 * eb),
            (false, 11) => format!("R.{wf}[i] = ({wt}){sq};"),
            _ => return None,
        };
        return Some(vl(d, n, m, cntl, false, &stmt));
    }
    let e = format!("B.{uf}[{idx}]");
    let stmt = match (u, opcode) {
        (true, 0) if !scalar => format!("R.{uf}[i] = ({ut})(D.{uf}[i] + A.{uf}[i] * {e});"),
        (true, 4) if !scalar => format!("R.{uf}[i] = ({ut})(D.{uf}[i] - A.{uf}[i] * {e});"),
        (false, 8) if !scalar => format!("R.{uf}[i] = ({ut})(A.{uf}[i] * {e});"),
        (false, 12) | (false, 13) => {
            let rnd = if opcode == 13 { format!(" + ((__int128)1 << {})", eb - 1) } else { String::new() };
            format!("R.{uf}[i] = ({ut})bf_sat_s((2 * (__int128)A.{sf}[i] * B.{sf}[{idx}]{rnd}) >> {eb}, {eb});")
        }
        _ => return None,
    };
    Some(vl(d, n, m, cnt, false, &stmt))
}

fn three_diff(w: u32, scalar: bool) -> Option<String> {
    let q = bit(w, 30) && !scalar;
    let u = bit(w, 29);
    let size = bits(w, 23, 22) as usize;
    let opcode = bits(w, 15, 12);
    let (m, n, d) = (bits(w, 20, 16), bits(w, 9, 5), bits(w, 4, 0));
    if !u && opcode == 14 && !scalar {
        let off = if q { 1 } else { 0 };
        return match size {
            0 => Some(vl(d, n, m, 8, false, &format!("R.h[i] = bf_pmull8(A.b[i + {}], B.b[i + {}]);", off * 8, off * 8))),
            3 => Some(format!(
                "{{ uint64_t lo, hi; bf_pmull64(c->v[{n}].d[{off}], c->v[{m}].d[{off}], &lo, &hi); c->v[{d}].d[0] = lo; c->v[{d}].d[1] = hi; }}"
            )),
            _ => None,
        };
    }
    if size == 3 {
        return None;
    }
    let eb = 8u32 << size;
    let cnt = if scalar { 1 } else { 64 / eb };
    let off = if q { cnt } else { 0 };
    let (nf, nsf, nt) = (U[size], S[size], UT[size]);
    let (wf, wsf, wt) = (U[size + 1], S[size + 1], UT[size + 1]);
    let ext = |v: &str, f: &str, sfield: &str| -> String {
        if u {
            format!("({wt})({v}.{f}[i + {off}])")
        } else {
            format!("({wt})(int64_t)({v}.{sfield}[i + {off}])")
        }
    };
    let (ea, eb_) = (ext("A", nf, nsf), ext("B", nf, nsf));
    let absd = if u {
        format!("({wt})(A.{nf}[i + {off}] > B.{nf}[i + {off}] ? A.{nf}[i + {off}] - B.{nf}[i + {off}] : B.{nf}[i + {off}] - A.{nf}[i + {off}])")
    } else {
        format!("({wt})(A.{nsf}[i + {off}] > B.{nsf}[i + {off}] ? (int64_t)A.{nsf}[i + {off}] - B.{nsf}[i + {off}] : (int64_t)B.{nsf}[i + {off}] - A.{nsf}[i + {off}])")
    };
    let prod = format!("({wt})({ea} * {eb_})");
    if scalar && !(matches!(opcode, 9 | 11 | 13) && !u) {
        return None;
    }
    let sq = format!("bf_sat_s(2 * (__int128)A.{nsf}[i + {off}] * B.{nsf}[i + {off}], {})", 2 * eb);
    let stmt = match (u, opcode) {
        (_, 0) => format!("R.{wf}[i] = ({wt})({ea} + {eb_});"),
        (_, 1) => format!("R.{wf}[i] = ({wt})(A.{wf}[i] + {eb_});"),
        (_, 2) => format!("R.{wf}[i] = ({wt})({ea} - {eb_});"),
        (_, 3) => format!("R.{wf}[i] = ({wt})(A.{wf}[i] - {eb_});"),
        (_, 4) | (_, 6) => {
            let op = if opcode == 4 { "+" } else { "-" };
            let rnd = if u { format!(" + (({wt})1 << {})", eb - 1) } else { String::new() };
            return Some(vl(
                d,
                n,
                m,
                cnt,
                q,
                &format!("R.{nf}[i + {off}] = ({nt})((({wt})(A.{wf}[i] {op} B.{wf}[i]){rnd}) >> {eb});"),
            ));
        }
        (_, 5) => format!("R.{wf}[i] = ({wt})(D.{wf}[i] + {absd});"),
        (_, 7) => format!("R.{wf}[i] = {absd};"),
        (_, 8) => format!("R.{wf}[i] = ({wt})(D.{wf}[i] + {prod});"),
        (_, 10) => format!("R.{wf}[i] = ({wt})(D.{wf}[i] - {prod});"),
        (_, 12) => format!("R.{wf}[i] = {prod};"),
        (false, 9) => format!("R.{wf}[i] = ({wt})bf_sat_s((__int128)D.{wsf}[i] + {sq}, {});", 2 * eb),
        (false, 11) => format!("R.{wf}[i] = ({wt})bf_sat_s((__int128)D.{wsf}[i] - {sq}, {});", 2 * eb),
        (false, 13) => format!("R.{wf}[i] = ({wt}){sq};"),
        _ => return None,
    };
    Some(vl(d, n, m, cnt, false, &stmt))
}

// ---------------------------------------------------------------- crypto extension
fn crypto_aes(w: u32) -> Option<String> {
    let (n, d) = (bits(w, 9, 5), bits(w, 4, 0));
    let f = match bits(w, 16, 12) {
        4 => "bf_aese",
        5 => "bf_aesd",
        6 => "bf_aesmc",
        7 => "bf_aesimc",
        _ => return None,
    };
    Some(format!("{f}(&c->v[{d}], &c->v[{n}]);"))
}

fn crypto_sha3(w: u32) -> Option<String> {
    let (m, n, d) = (bits(w, 20, 16), bits(w, 9, 5), bits(w, 4, 0));
    let f = match bits(w, 14, 12) {
        0 => "bf_sha1c",
        1 => "bf_sha1p",
        2 => "bf_sha1m",
        3 => "bf_sha1su0",
        4 => "bf_sha256h",
        5 => "bf_sha256h2",
        6 => "bf_sha256su1",
        _ => return None,
    };
    Some(format!("{f}(&c->v[{d}], &c->v[{n}], &c->v[{m}]);"))
}

fn crypto_sha2(w: u32) -> Option<String> {
    let (n, d) = (bits(w, 9, 5), bits(w, 4, 0));
    let f = match bits(w, 16, 12) {
        0 => "bf_sha1h",
        1 => "bf_sha1su1",
        2 => "bf_sha256su0",
        _ => return None,
    };
    Some(format!("{f}(&c->v[{d}], &c->v[{n}]);"))
}
