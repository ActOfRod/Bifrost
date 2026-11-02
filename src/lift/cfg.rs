//! Control-flow recovery: functions, blocks and jump tables reachable from a set of entry points.

use std::collections::{BTreeMap, BTreeSet, VecDeque};

use super::decode::{decode, Op};

#[derive(Debug, Clone)]
pub struct Insn {
    pub address: u64,
    pub word: u32,
    pub op: Op,
}

#[derive(Debug, Clone, Default)]
pub struct LiftStats {
    pub lifted: usize,
    pub unsupported: usize,
    pub examples: Vec<(u64, u32)>,
    pub jump_tables: usize,
}

#[derive(Debug, Clone, Default)]
pub struct Lifted {
    pub insns: BTreeMap<u64, Insn>,
    /// function entry -> block leaders that belong to it (entry included)
    pub functions: BTreeMap<u64, BTreeSet<u64>>,
    /// address of a `BR` -> recovered jump-table targets
    pub jump_tables: BTreeMap<u64, Vec<u64>>,
    pub stats: LiftStats,
}

impl Lifted {
    /// Every address the generated code can be entered at (function entries and block leaders).
    pub fn entry_points(&self) -> BTreeSet<u64> {
        self.functions.values().flatten().copied().collect()
    }
}

pub trait CodeSource {
    fn word(&self, va: u64) -> Option<u32>;
    fn is_code(&self, va: u64) -> bool;
    fn read_bytes(&self, va: u64, len: usize) -> Option<Vec<u8>>;
}

fn bits(w: u32, hi: u32, lo: u32) -> u32 {
    (w >> lo) & ((1u32 << (hi - lo + 1)) - 1)
}

pub fn lift_program(src: &dyn CodeSource, entries: &BTreeSet<u64>) -> Lifted {
    let mut out = Lifted::default();
    let mut fn_queue: VecDeque<u64> = entries.iter().copied().filter(|&a| src.is_code(a) && a % 4 == 0).collect();
    let mut known_fns: BTreeSet<u64> = fn_queue.iter().copied().collect();
    while let Some(entry) = fn_queue.pop_front() {
        let mut leaders = BTreeSet::new();
        let mut q = VecDeque::from([entry]);
        while let Some(start) = q.pop_front() {
            if !leaders.insert(start) {
                continue;
            }
            let mut pc = start;
            let mut window: Vec<(u64, u32)> = Vec::new();
            while let Some(word) = src.word(pc) {
                if !src.is_code(pc) {
                    break;
                }
                let op = out
                    .insns
                    .entry(pc)
                    .or_insert_with(|| Insn { address: pc, word, op: decode(word, pc) })
                    .op
                    .clone();
                window.push((pc, word));
                match &op {
                    Op::Bl { target } => {
                        if src.is_code(*target) && known_fns.insert(*target) {
                            fn_queue.push_back(*target);
                        }
                    }
                    Op::B { target } => {
                        if known_fns.contains(target) || !src.is_code(*target) {
                            // tail call: handled by dispatch
                        } else {
                            q.push_back(*target);
                        }
                    }
                    Op::CondBranch { target, .. } => {
                        if src.is_code(*target) {
                            q.push_back(*target);
                        }
                    }
                    Op::Br { .. } => {
                        if let Some(targets) = jump_table(src, &window) {
                            for &t in &targets {
                                q.push_back(t);
                            }
                            out.stats.jump_tables += 1;
                            out.jump_tables.insert(pc, targets);
                        }
                    }
                    _ => {}
                }
                if op.is_terminator() {
                    break;
                }
                pc += 4;
                if leaders.contains(&pc) {
                    break;
                }
            }
        }
        out.functions.insert(entry, leaders);
    }
    // a tail-call target discovered later as a function must not also live inside another function
    let fn_entries: BTreeSet<u64> = out.functions.keys().copied().collect();
    for (entry, leaders) in out.functions.iter_mut() {
        leaders.retain(|l| l == entry || !fn_entries.contains(l));
    }
    for insn in out.insns.values() {
        if insn.op == Op::Unsupported {
            out.stats.unsupported += 1;
            if out.stats.examples.len() < 32 {
                out.stats.examples.push((insn.address, insn.word));
            }
        } else {
            out.stats.lifted += 1;
        }
    }
    out
}

/// Recognise the compiler's switch lowering ending at the last instruction of `window`:
///   cmp wI, #N ; b.hi default ; adrp/add xT, table ; ldr{b,h,sw} wE, [xT, xI, lsl #s] ;
///   adr/adrp+add xB, base ; add xJ, xB, wE, sxt? #2 ; br xJ
fn jump_table(src: &dyn CodeSource, window: &[(u64, u32)]) -> Option<Vec<u64>> {
    let (_, br) = *window.last()?;
    let jreg = bits(br, 9, 5);
    let start = window.len().saturating_sub(24);
    let w = &window[start..];
    let mut consts: BTreeMap<u32, u64> = BTreeMap::new();
    let mut bound: Option<(u32, u64)> = None;
    let mut load: Option<(u32, u32, u32, u32, bool)> = None; // (dst, table reg, index reg, size, signed)
    let mut add: Option<(u32, u32, u32, u32)> = None; // (dst, base, elem, shift)
    for &(pc, word) in w {
        if word & 0x9F00_0000 == 0x9000_0000 {
            let imm = ((bits(word, 23, 5) as u64) << 2 | bits(word, 30, 29) as u64) << 12;
            let off = ((imm << 31) as i64 >> 31) as u64;
            consts.insert(bits(word, 4, 0), (pc & !0xFFF).wrapping_add(off));
        } else if word & 0x9F00_0000 == 0x1000_0000 {
            let imm = (bits(word, 23, 5) as u64) << 2 | bits(word, 30, 29) as u64;
            let off = ((imm << 43) as i64 >> 43) as u64;
            consts.insert(bits(word, 4, 0), pc.wrapping_add(off));
        } else if word & 0xFF80_0000 == 0x9100_0000 {
            let (rd, rn) = (bits(word, 4, 0), bits(word, 9, 5));
            let imm = (bits(word, 21, 10) as u64) << if bits(word, 22, 22) == 1 { 12 } else { 0 };
            match consts.get(&rn) {
                Some(&v) => {
                    consts.insert(rd, v.wrapping_add(imm));
                }
                None => {
                    consts.remove(&rd);
                }
            }
        } else if word & 0x7F80_001F == 0x7100_001F {
            // cmp wI/xI, #imm
            let imm = (bits(word, 21, 10) as u64) << if bits(word, 22, 22) == 1 { 12 } else { 0 };
            bound = Some((bits(word, 9, 5), imm));
        } else if word & 0x3F20_0C00 == 0x3820_0800 && bits(word, 26, 26) == 0 {
            // ldr (register offset)
            let size = bits(word, 31, 30);
            let opc = bits(word, 23, 22);
            if opc == 1 || opc == 2 {
                load = Some((bits(word, 4, 0), bits(word, 9, 5), bits(word, 20, 16), size, opc == 2));
            }
        } else if word & 0x7FE0_0000 == 0x0B20_0000 || word & 0xFFE0_0000 == 0x8B20_0000 {
            add = Some((bits(word, 4, 0), bits(word, 9, 5), bits(word, 20, 16), bits(word, 12, 10)));
        }
    }
    let (adst, abase, aelem, ashift) = add?;
    if adst != jreg {
        return None;
    }
    let (ldst, ltable, lidx, lsize, lsigned) = load?;
    if ldst != aelem {
        return None;
    }
    let table = *consts.get(&ltable)?;
    let base = *consts.get(&abase)?;
    let (breg, n) = bound?;
    if breg != lidx || n > 4096 {
        return None;
    }
    let es = 1usize << lsize;
    let raw = src.read_bytes(table, es * (n as usize + 1))?;
    let mut targets = Vec::new();
    for i in 0..=n as usize {
        let b = &raw[i * es..(i + 1) * es];
        let v: i64 = match (es, lsigned) {
            (1, false) => b[0] as i64,
            (1, true) => b[0] as i8 as i64,
            (2, false) => u16::from_le_bytes([b[0], b[1]]) as i64,
            (2, true) => i16::from_le_bytes([b[0], b[1]]) as i64,
            (4, _) => i32::from_le_bytes([b[0], b[1], b[2], b[3]]) as i64,
            _ => return None,
        };
        let t = base.wrapping_add((v << ashift) as u64);
        if !src.is_code(t) || t % 4 != 0 {
            return None;
        }
        targets.push(t);
    }
    targets.sort_unstable();
    targets.dedup();
    Some(targets)
}
