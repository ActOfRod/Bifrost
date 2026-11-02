use std::collections::{BTreeMap, BTreeSet, HashMap};

use crate::module::Module;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Width {
    W8,
    W16,
    W32,
    W64,
}

impl Width {
    pub fn bits(self) -> u32 {
        match self {
            Self::W8 => 8,
            Self::W16 => 16,
            Self::W32 => 32,
            Self::W64 => 64,
        }
    }

    pub fn bytes(self) -> usize {
        (self.bits() / 8) as usize
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GReg {
    X(u8),
    Sp,
    Zr,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AluOp {
    Add,
    Sub,
    And,
    Or,
    Xor,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ShiftKind {
    Lsl,
    Lsr,
    Asr,
    Ror,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExtendKind {
    Uxtb,
    Uxth,
    Uxtw,
    Uxtx,
    Sxtb,
    Sxth,
    Sxtw,
    Sxtx,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MovKind {
    Z,
    K,
    N,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CselKind {
    Sel,
    Inc,
    Inv,
    Neg,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IndexMode {
    None,
    Pre,
    Post,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Value {
    Reg(GReg),
    Imm(u64),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Op {
    Nop,
    Alu {
        op: AluOp,
        dst: GReg,
        lhs: GReg,
        rhs: Value,
        shift: Option<(ShiftKind, u8)>,
        extend: Option<(ExtendKind, u8)>,
        invert_rhs: bool,
        set_flags: bool,
        width: Width,
    },
    MovWide {
        dst: GReg,
        imm: u16,
        shift: u8,
        kind: MovKind,
        width: Width,
    },
    Bitfield {
        dst: GReg,
        src: GReg,
        immr: u8,
        imms: u8,
        signed: bool,
        width: Width,
    },
    Madd {
        dst: GReg,
        n: GReg,
        m: GReg,
        a: GReg,
        sub: bool,
        width: Width,
    },
    Csel {
        dst: GReg,
        a: GReg,
        b: GReg,
        cond: u8,
        kind: CselKind,
        width: Width,
    },
    SetImm {
        dst: GReg,
        value: u64,
    },
    Load {
        dst: GReg,
        base: GReg,
        offset: i64,
        index: IndexMode,
        width: Width,
        signed: bool,
    },
    Store {
        src: GReg,
        base: GReg,
        offset: i64,
        index: IndexMode,
        width: Width,
    },
    LoadIndexed {
        dst: GReg,
        base: GReg,
        index: GReg,
        extend: ExtendKind,
        shift: u8,
        width: Width,
        signed: bool,
    },
    StoreIndexed {
        src: GReg,
        base: GReg,
        index: GReg,
        extend: ExtendKind,
        shift: u8,
        width: Width,
    },
    LoadPair {
        rt: GReg,
        rt2: GReg,
        base: GReg,
        offset: i64,
        index: IndexMode,
        width: Width,
    },
    StorePair {
        rt: GReg,
        rt2: GReg,
        base: GReg,
        offset: i64,
        index: IndexMode,
        width: Width,
    },
    LoadLit {
        dst: GReg,
        addr: u64,
        width: Width,
        signed: bool,
    },
    B {
        target: u64,
    },
    Bl {
        target: u64,
    },
    BCond {
        cond: u8,
        target: u64,
    },
    Cbz {
        reg: GReg,
        is_zero: bool,
        target: u64,
        width: Width,
    },
    Tbz {
        reg: GReg,
        bit: u8,
        is_zero: bool,
        target: u64,
    },
    Br {
        reg: GReg,
        link: bool,
    },
    Ret {
        reg: GReg,
    },
    Svc {
        imm: u16,
    },
    Brk {
        imm: u16,
    },
    Unsupported,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Insn {
    pub address: u64,
    pub word: u32,
    pub op: Op,
}

#[derive(Debug, Clone, Default)]
pub struct LiftStats {
    pub reachable: usize,
    pub lifted: usize,
    pub unsupported: usize,
    pub groups: BTreeMap<u8, usize>,
    pub examples: Vec<(u64, u32)>,
}

#[derive(Debug, Clone)]
pub struct Lifted {
    pub insns: BTreeMap<u64, Insn>,
    pub leaders: BTreeSet<u64>,
    pub stats: LiftStats,
}

pub fn lift(module: &Module) -> Lifted {
    let mut lifted = Lifted {
        insns: BTreeMap::new(),
        leaders: BTreeSet::new(),
        stats: LiftStats::default(),
    };
    let mut queue = Vec::new();
    let mut queued = BTreeSet::new();
    let push = |queue: &mut Vec<u64>, queued: &mut BTreeSet<u64>, addr: u64| {
        if addr.is_multiple_of(4) && module.in_text(addr) && queued.insert(addr) {
            queue.push(addr);
        }
    };
    push(&mut queue, &mut queued, module.entry);
    for va in &module.init {
        push(&mut queue, &mut queued, *va);
    }
    for symbol in module.exports() {
        if symbol.kind == crate::module::SymbolKind::Func || module.in_text(symbol.va) {
            push(&mut queue, &mut queued, symbol.va);
        }
    }

    while let Some(start) = queue.pop() {
        if lifted.insns.contains_key(&start) && lifted.leaders.contains(&start) {
            continue;
        }
        lifted.leaders.insert(start);
        let mut pc = start;
        loop {
            if pc != start && lifted.leaders.contains(&pc) && lifted.insns.contains_key(&pc) {
                break;
            }
            if !module.in_text(pc) || pc % 4 != 0 {
                break;
            }
            let Some(word) = module.read_u32(pc) else {
                break;
            };
            let op = decode(word, pc);
            let terminator = is_terminator(&op);
            lifted.insns.insert(
                pc,
                Insn {
                    address: pc,
                    word,
                    op,
                },
            );
            let next = pc.wrapping_add(4);
            match &lifted.insns[&pc].op {
                Op::B { target } => push(&mut queue, &mut queued, *target),
                Op::Bl { target } => {
                    push(&mut queue, &mut queued, *target);
                    push(&mut queue, &mut queued, next);
                    lifted.leaders.insert(next);
                }
                Op::BCond { target, .. } | Op::Cbz { target, .. } | Op::Tbz { target, .. } => {
                    push(&mut queue, &mut queued, *target);
                    push(&mut queue, &mut queued, next);
                    lifted.leaders.insert(next);
                }
                Op::Br { .. } | Op::Ret { .. } | Op::Brk { .. } | Op::Unsupported => {}
                _ => {}
            }
            if terminator {
                break;
            }
            pc = next;
        }
    }

    let mut groups = HashMap::<u8, usize>::new();
    for insn in lifted.insns.values() {
        lifted.stats.reachable += 1;
        if matches!(insn.op, Op::Unsupported) {
            lifted.stats.unsupported += 1;
            let key = (insn.word >> 24) as u8;
            *groups.entry(key).or_default() += 1;
            if lifted.stats.examples.len() < 12 {
                lifted.stats.examples.push((insn.address, insn.word));
            }
        } else {
            lifted.stats.lifted += 1;
        }
    }
    lifted.stats.groups = groups.into_iter().collect();
    lifted
}

fn is_terminator(op: &Op) -> bool {
    matches!(
        op,
        Op::B { .. }
            | Op::Bl { .. }
            | Op::BCond { .. }
            | Op::Cbz { .. }
            | Op::Tbz { .. }
            | Op::Br { .. }
            | Op::Ret { .. }
            | Op::Brk { .. }
            | Op::Unsupported
    )
}

pub fn decode(word: u32, pc: u64) -> Op {
    if word == 0xD503201F {
        return Op::Nop;
    }
    if let Some(op) = decode_branch(word, pc) {
        return op;
    }
    if let Some(op) = decode_dp_imm(word, pc) {
        return op;
    }
    if let Some(op) = decode_dp_reg(word) {
        return op;
    }
    if let Some(op) = decode_load_store(word, pc) {
        return op;
    }
    Op::Unsupported
}

fn decode_branch(word: u32, pc: u64) -> Option<Op> {
    let top = word >> 26;
    if top == 0b000101 || top == 0b100101 {
        let imm = sext(((word & 0x03FF_FFFF) as u64) << 2, 28);
        let target = pc.wrapping_add(imm as u64);
        return Some(if top == 0b000101 {
            Op::B { target }
        } else {
            Op::Bl { target }
        });
    }
    if (word & 0xFF00_0010) == 0x5400_0000 {
        let imm = sext((((word >> 5) & 0x7FFFF) as u64) << 2, 21);
        return Some(Op::BCond {
            cond: (word & 0xF) as u8,
            target: pc.wrapping_add(imm as u64),
        });
    }
    if (word & 0x7E00_0000) == 0x3400_0000 {
        let imm = sext((((word >> 5) & 0x7FFFF) as u64) << 2, 21);
        let sf = word >> 31;
        return Some(Op::Cbz {
            reg: reg_zr(word & 0x1F),
            is_zero: (word >> 24) & 1 == 0,
            target: pc.wrapping_add(imm as u64),
            width: if sf == 1 { Width::W64 } else { Width::W32 },
        });
    }
    if (word & 0x7E00_0000) == 0x3600_0000 {
        let imm = sext((((word >> 5) & 0x3FFF) as u64) << 2, 16);
        let bit = (((word >> 31) & 1) << 5) | ((word >> 19) & 0x1F);
        return Some(Op::Tbz {
            reg: reg_zr(word & 0x1F),
            bit: bit as u8,
            is_zero: (word >> 24) & 1 == 0,
            target: pc.wrapping_add(imm as u64),
        });
    }
    if (word & 0xFFFF_FC1F) == 0xD65F_0000 {
        return Some(Op::Ret {
            reg: reg_zr((word >> 5) & 0x1F),
        });
    }
    if (word & 0xFFFF_FC1F) == 0xD61F_0000 {
        return Some(Op::Br {
            reg: reg_zr((word >> 5) & 0x1F),
            link: false,
        });
    }
    if (word & 0xFFFF_FC1F) == 0xD63F_0000 {
        return Some(Op::Br {
            reg: reg_zr((word >> 5) & 0x1F),
            link: true,
        });
    }
    if (word & 0xFFE0_001F) == 0xD400_0001 {
        return Some(Op::Svc {
            imm: ((word >> 5) & 0xFFFF) as u16,
        });
    }
    if (word & 0xFFE0_001F) == 0xD420_0000 {
        return Some(Op::Brk {
            imm: ((word >> 5) & 0xFFFF) as u16,
        });
    }
    None
}

fn decode_dp_imm(word: u32, pc: u64) -> Option<Op> {
    if (word & 0x1F00_0000) == 0x1000_0000 {
        let immlo = (word >> 29) & 0x3;
        let immhi = (word >> 5) & 0x7FFFF;
        let imm = sext(((immhi << 2) | immlo) as u64, 21);
        let dst = reg_zr(word & 0x1F);
        let value = if word >> 31 == 1 {
            (pc & !0xFFF).wrapping_add((imm as u64) << 12)
        } else {
            pc.wrapping_add(imm as u64)
        };
        return Some(Op::SetImm { dst, value });
    }
    if (word & 0x1F80_0000) == 0x1100_0000 {
        let sf = word >> 31;
        let op = (word >> 30) & 1;
        let s = (word >> 29) & 1;
        let shift = (word >> 22) & 1;
        let imm12 = (word >> 10) & 0xFFF;
        if shift == 1 && sf == 0 {
            // valid, imm is imm12 << 12
        }
        let imm = if shift == 1 {
            (imm12 as u64) << 12
        } else {
            imm12 as u64
        };
        let width = width_sf(sf);
        return Some(Op::Alu {
            op: if op == 0 { AluOp::Add } else { AluOp::Sub },
            dst: add_sub_rd(word & 0x1F, s == 1),
            lhs: reg_sp((word >> 5) & 0x1F),
            rhs: Value::Imm(imm),
            shift: None,
            extend: None,
            invert_rhs: false,
            set_flags: s == 1,
            width,
        });
    }
    if (word & 0x1F80_0000) == 0x1200_0000 {
        let sf = word >> 31;
        let opc = (word >> 29) & 0x3;
        let n = (word >> 22) & 1;
        let immr = (word >> 16) & 0x3F;
        let imms = (word >> 10) & 0x3F;
        let imm = decode_logical_imm(sf == 1, n, immr, imms)?;
        let (op, set_flags) = match opc {
            0 => (AluOp::And, false),
            1 => (AluOp::Or, false),
            2 => (AluOp::Xor, false),
            _ => (AluOp::And, true),
        };
        return Some(Op::Alu {
            op,
            dst: reg_zr(word & 0x1F),
            lhs: reg_zr((word >> 5) & 0x1F),
            rhs: Value::Imm(imm),
            shift: None,
            extend: None,
            invert_rhs: false,
            set_flags,
            width: width_sf(sf),
        });
    }
    if (word & 0x1F80_0000) == 0x1280_0000 {
        let sf = word >> 31;
        let opc = (word >> 29) & 0x3;
        let kind = match opc {
            0 => MovKind::N,
            2 => MovKind::Z,
            3 => MovKind::K,
            _ => return None,
        };
        let hw = ((word >> 21) & 0x3) as u8;
        if sf == 0 && hw > 1 {
            return None;
        }
        return Some(Op::MovWide {
            dst: reg_zr(word & 0x1F),
            imm: ((word >> 5) & 0xFFFF) as u16,
            shift: hw * 16,
            kind,
            width: width_sf(sf),
        });
    }
    if (word & 0x1F80_0000) == 0x1300_0000 {
        let sf = word >> 31;
        let opc = (word >> 29) & 0x3;
        let n = (word >> 22) & 1;
        if sf == 0 && n == 1 {
            return None;
        }
        if sf == 1 && n == 0 {
            return None;
        }
        let signed = opc == 0;
        if opc == 1 {
            return None;
        }
        let width = width_sf(sf);
        let immr = ((word >> 16) & 0x3F) as u8;
        let imms = ((word >> 10) & 0x3F) as u8;
        if immr as u32 >= width.bits() || imms as u32 >= width.bits() {
            return None;
        }
        return Some(Op::Bitfield {
            dst: reg_zr(word & 0x1F),
            src: reg_zr((word >> 5) & 0x1F),
            immr,
            imms,
            signed,
            width,
        });
    }
    None
}

fn decode_dp_reg(word: u32) -> Option<Op> {
    if (word & 0x1F20_0000) == 0x0B00_0000 && (word >> 21) & 1 == 0 {
        let sf = word >> 31;
        let op = (word >> 30) & 1;
        let s = (word >> 29) & 1;
        let shift = match (word >> 22) & 0x3 {
            0 => ShiftKind::Lsl,
            1 => ShiftKind::Lsr,
            2 => ShiftKind::Asr,
            _ => return None,
        };
        let amount = ((word >> 10) & 0x3F) as u8;
        let width = width_sf(sf);
        if width == Width::W32 && amount >= 32 {
            return None;
        }
        return Some(Op::Alu {
            op: if op == 0 { AluOp::Add } else { AluOp::Sub },
            dst: reg_zr(word & 0x1F),
            lhs: reg_zr((word >> 5) & 0x1F),
            rhs: Value::Reg(reg_zr((word >> 16) & 0x1F)),
            shift: Some((shift, amount)),
            extend: None,
            invert_rhs: false,
            set_flags: s == 1,
            width,
        });
    }
    if (word & 0x1F20_0000) == 0x0B20_0000 {
        let sf = word >> 31;
        let op = (word >> 30) & 1;
        let s = (word >> 29) & 1;
        if (word >> 22) & 0x3 != 0 {
            return None;
        }
        let option = (word >> 13) & 0x7;
        let amount = ((word >> 10) & 0x7) as u8;
        if amount > 4 {
            return None;
        }
        return Some(Op::Alu {
            op: if op == 0 { AluOp::Add } else { AluOp::Sub },
            dst: reg_sp(word & 0x1F),
            lhs: reg_sp((word >> 5) & 0x1F),
            rhs: Value::Reg(reg_zr((word >> 16) & 0x1F)),
            shift: None,
            extend: Some((extend_kind(option), amount)),
            invert_rhs: false,
            set_flags: s == 1,
            width: width_sf(sf),
        });
    }
    if (word & 0x1F00_0000) == 0x0A00_0000 {
        let sf = word >> 31;
        let opc = (word >> 29) & 0x3;
        let shift = match (word >> 22) & 0x3 {
            0 => ShiftKind::Lsl,
            1 => ShiftKind::Lsr,
            2 => ShiftKind::Asr,
            _ => ShiftKind::Ror,
        };
        let n = (word >> 21) & 1;
        let amount = ((word >> 10) & 0x3F) as u8;
        let (op, set_flags) = match opc {
            0 => (AluOp::And, false),
            1 => (AluOp::Or, false),
            2 => (AluOp::Xor, false),
            _ => (AluOp::And, true),
        };
        return Some(Op::Alu {
            op,
            dst: reg_zr(word & 0x1F),
            lhs: reg_zr((word >> 5) & 0x1F),
            rhs: Value::Reg(reg_zr((word >> 16) & 0x1F)),
            shift: Some((shift, amount)),
            extend: None,
            invert_rhs: n == 1,
            set_flags,
            width: width_sf(sf),
        });
    }
    if (word & 0x1FE0_0000) == 0x1A80_0000 {
        let sf = word >> 31;
        let op = (word >> 30) & 1;
        let o2 = (word >> 10) & 1;
        let kind = match (op, o2) {
            (0, 0) => CselKind::Sel,
            (0, 1) => CselKind::Inc,
            (1, 0) => CselKind::Inv,
            _ => CselKind::Neg,
        };
        return Some(Op::Csel {
            dst: reg_zr(word & 0x1F),
            a: reg_zr((word >> 5) & 0x1F),
            b: reg_zr((word >> 16) & 0x1F),
            cond: ((word >> 12) & 0xF) as u8,
            kind,
            width: width_sf(sf),
        });
    }
    if (word & 0x1FE0_0000) == 0x1B00_0000 {
        let sf = word >> 31;
        return Some(Op::Madd {
            dst: reg_zr(word & 0x1F),
            n: reg_zr((word >> 5) & 0x1F),
            m: reg_zr((word >> 16) & 0x1F),
            a: reg_zr((word >> 10) & 0x1F),
            sub: (word >> 15) & 1 == 1,
            width: width_sf(sf),
        });
    }
    None
}

fn decode_load_store(word: u32, pc: u64) -> Option<Op> {
    if (word & 0x3B00_0000) == 0x1800_0000 && (word >> 26) & 1 == 0 {
        let opc = (word >> 30) & 0x3;
        let imm = sext((((word >> 5) & 0x7FFFF) as u64) << 2, 21);
        let (width, signed) = match opc {
            0 => (Width::W32, false),
            1 => (Width::W64, false),
            2 => (Width::W32, true),
            _ => return None,
        };
        return Some(Op::LoadLit {
            dst: reg_zr(word & 0x1F),
            addr: pc.wrapping_add(imm as u64),
            width,
            signed,
        });
    }
    if (word & 0x3A00_0000) == 0x2800_0000 {
        return decode_pair(word);
    }
    if (word & 0x3B00_0000) == 0x3900_0000 {
        return decode_unsigned(word);
    }
    if (word & 0x3B20_0C00) == 0x3800_0000 {
        return decode_unscaled(word);
    }
    if (word & 0x3B20_0C00) == 0x3820_0800 {
        return decode_reg_offset(word);
    }
    None
}

fn decode_pair(word: u32) -> Option<Op> {
    let opc = (word >> 30) & 0x3;
    let v = (word >> 26) & 1;
    if v == 1 {
        return None;
    }
    let width = match opc {
        0 => Width::W32,
        2 => Width::W64,
        _ => return None,
    };
    let mode = match (word >> 23) & 0x7 {
        0b001 => IndexMode::Post,
        0b011 => IndexMode::Pre,
        0b010 => IndexMode::None,
        _ => return None,
    };
    let imm7 = sext(((word >> 15) & 0x7F) as u64, 7);
    let scale = if width == Width::W64 { 3 } else { 2 };
    let offset = imm7 << scale;
    let load = (word >> 22) & 1 == 1;
    let rt = reg_zr(word & 0x1F);
    let rn = reg_sp((word >> 5) & 0x1F);
    let rt2 = reg_zr((word >> 10) & 0x1F);
    if load {
        Some(Op::LoadPair {
            rt,
            rt2,
            base: rn,
            offset,
            index: mode,
            width,
        })
    } else {
        Some(Op::StorePair {
            rt,
            rt2,
            base: rn,
            offset,
            index: mode,
            width,
        })
    }
}

fn decode_unsigned(word: u32) -> Option<Op> {
    let size = (word >> 30) & 0x3;
    let v = (word >> 26) & 1;
    if v == 1 {
        return None;
    }
    let opc = (word >> 22) & 0x3;
    let imm12 = (word >> 10) & 0xFFF;
    let (width, signed, load) = int_mem_op(size, opc)?;
    let offset = (imm12 as i64) << width.bytes().trailing_zeros();
    let rn = reg_sp((word >> 5) & 0x1F);
    let rt = reg_zr(word & 0x1F);
    if load {
        Some(Op::Load {
            dst: rt,
            base: rn,
            offset,
            index: IndexMode::None,
            width,
            signed,
        })
    } else {
        Some(Op::Store {
            src: rt,
            base: rn,
            offset,
            index: IndexMode::None,
            width,
        })
    }
}

fn decode_unscaled(word: u32) -> Option<Op> {
    let size = (word >> 30) & 0x3;
    let v = (word >> 26) & 1;
    if v == 1 || (word >> 21) & 1 != 0 {
        return None;
    }
    let opc = (word >> 22) & 0x3;
    let imm9 = sext(((word >> 12) & 0x1FF) as u64, 9);
    let mode = match (word >> 10) & 0x3 {
        0b00 => IndexMode::None,
        0b01 => IndexMode::Post,
        0b11 => IndexMode::Pre,
        _ => return None,
    };
    let (width, signed, load) = int_mem_op(size, opc)?;
    let rn = reg_sp((word >> 5) & 0x1F);
    let rt = reg_zr(word & 0x1F);
    if load {
        Some(Op::Load {
            dst: rt,
            base: rn,
            offset: imm9,
            index: mode,
            width,
            signed,
        })
    } else {
        Some(Op::Store {
            src: rt,
            base: rn,
            offset: imm9,
            index: mode,
            width,
        })
    }
}

fn decode_reg_offset(word: u32) -> Option<Op> {
    let size = (word >> 30) & 0x3;
    let v = (word >> 26) & 1;
    if v == 1 {
        return None;
    }
    let opc = (word >> 22) & 0x3;
    let (width, signed, load) = int_mem_op(size, opc)?;
    if !load {
        // stores use the same encoding with opc == 0; int_mem_op handles that.
    }
    let option = (word >> 13) & 0x7;
    let s = ((word >> 12) & 1) as u8;
    let shift = if s == 1 { width.bytes().trailing_zeros() as u8 } else { 0 };
    let base = reg_sp((word >> 5) & 0x1F);
    let index = reg_zr((word >> 16) & 0x1F);
    let extend = extend_kind(option);
    if load {
        Some(Op::LoadIndexed {
            dst: reg_zr(word & 0x1F),
            base,
            index,
            extend,
            shift,
            width,
            signed,
        })
    } else {
        Some(Op::StoreIndexed {
            src: reg_zr(word & 0x1F),
            base,
            index,
            extend,
            shift,
            width,
        })
    }
}

fn int_mem_op(size: u32, opc: u32) -> Option<(Width, bool, bool)> {
    let width = match size {
        0 => Width::W8,
        1 => Width::W16,
        2 => Width::W32,
        _ => Width::W64,
    };
    match opc {
        0 => Some((width, false, false)),
        1 => {
            if size == 3 {
                Some((Width::W64, false, true))
            } else {
                Some((width, false, true))
            }
        }
        2 => match size {
            0..=2 => Some((width, true, true)),
            3 => None,
            _ => None,
        },
        _ => None,
    }
}

fn add_sub_rd(reg: u32, flags: bool) -> GReg {
    if flags {
        reg_zr(reg)
    } else {
        reg_sp(reg)
    }
}

fn reg_zr(reg: u32) -> GReg {
    if reg == 31 {
        GReg::Zr
    } else {
        GReg::X(reg as u8)
    }
}

fn reg_sp(reg: u32) -> GReg {
    if reg == 31 {
        GReg::Sp
    } else {
        GReg::X(reg as u8)
    }
}

fn width_sf(sf: u32) -> Width {
    if sf == 1 {
        Width::W64
    } else {
        Width::W32
    }
}

fn extend_kind(option: u32) -> ExtendKind {
    match option & 7 {
        0 => ExtendKind::Uxtb,
        1 => ExtendKind::Uxth,
        2 => ExtendKind::Uxtw,
        3 => ExtendKind::Uxtx,
        4 => ExtendKind::Sxtb,
        5 => ExtendKind::Sxth,
        6 => ExtendKind::Sxtw,
        _ => ExtendKind::Sxtx,
    }
}

pub fn decode_logical_imm(is_64: bool, n: u32, immr: u32, imms: u32) -> Option<u64> {
    let datasize = if is_64 { 64 } else { 32 };
    if !is_64 && n != 0 {
        return None;
    }
    let combined = ((n & 1) << 6) | ((!imms) & 0x3F);
    if combined == 0 {
        return None;
    }
    let len = 31 - combined.leading_zeros();
    if len < 1 || (1 << len) > datasize {
        return None;
    }
    let levels = (1u32 << len) - 1;
    if imms & levels == levels {
        return None;
    }
    let s = imms & levels;
    let r = immr & levels;
    let esize = 1u32 << len;
    let welem = if s + 1 >= 64 {
        u64::MAX
    } else {
        (1u64 << (s + 1)) - 1
    };
    let rotated = ror(welem, r, esize);
    let mut out = 0u64;
    let mut pos = 0;
    while pos < datasize {
        out |= rotated << pos;
        pos += esize;
    }
    if datasize == 32 {
        out &= 0xFFFF_FFFF;
    }
    Some(out)
}

fn ror(value: u64, rot: u32, width: u32) -> u64 {
    let rot = rot % width;
    let mask = if width == 64 {
        u64::MAX
    } else {
        (1u64 << width) - 1
    };
    let value = value & mask;
    if rot == 0 {
        return value;
    }
    ((value >> rot) | (value << (width - rot))) & mask
}

pub fn bitfield_move(src: u64, immr: u32, imms: u32, width: u32, signed: bool) -> u64 {
    let mask_n = |n: u32| -> u64 {
        if n >= 64 {
            u64::MAX
        } else {
            (1u64 << n) - 1
        }
    };
    let src = src & mask_n(width);
    let result = if imms >= immr {
        let len = imms - immr + 1;
        let mut extracted = (src >> immr) & mask_n(len);
        if signed && len < width && ((extracted >> (len - 1)) & 1) == 1 {
            extracted |= !mask_n(len);
        }
        extracted & mask_n(width)
    } else {
        let len = imms + 1;
        let shift = width - immr;
        let mut extracted = src & mask_n(len);
        if signed && ((extracted >> imms) & 1) == 1 {
            extracted |= !mask_n(len);
        }
        extracted.wrapping_shl(shift) & mask_n(width)
    };
    if width == 32 {
        result & 0xFFFF_FFFF
    } else {
        result
    }
}

fn sext(value: u64, bits: u32) -> i64 {
    let shift = 64 - bits;
    ((value << shift) as i64) >> shift
}

pub fn describe(op: &Op) -> String {
    match op {
        Op::Nop => "nop".into(),
        Op::Alu { op, dst, lhs, rhs, set_flags, .. } => {
            format!("{op:?} {dst:?}, {lhs:?}, {rhs:?} flags={set_flags}")
        }
        Op::MovWide { kind, dst, imm, shift, .. } => {
            format!("mov{kind:?} {dst:?}, #{imm:#x}, lsl #{shift}")
        }
        Op::SetImm { dst, value } => format!("adr {dst:?}, {value:#x}"),
        Op::B { target } => format!("b {target:#x}"),
        Op::Bl { target } => format!("bl {target:#x}"),
        Op::BCond { cond, target } => format!("b.{cond} {target:#x}"),
        Op::Cbz { reg, is_zero, target, .. } => {
            format!("{} {reg:?}, {target:#x}", if *is_zero { "cbz" } else { "cbnz" })
        }
        Op::Tbz { reg, bit, is_zero, target } => {
            format!("{} {reg:?}, #{bit}, {target:#x}", if *is_zero { "tbz" } else { "tbnz" })
        }
        Op::Br { reg, link } => format!("{} {reg:?}", if *link { "blr" } else { "br" }),
        Op::Ret { reg } => format!("ret {reg:?}"),
        Op::Svc { imm } => format!("svc #{imm:#x}"),
        Op::Brk { imm } => format!("brk #{imm:#x}"),
        Op::Load { dst, base, offset, width, .. } => {
            format!("ldr.{width:?} {dst:?}, [{base:?}, #{offset}]")
        }
        Op::Store { src, base, offset, width, .. } => {
            format!("str.{width:?} {src:?}, [{base:?}, #{offset}]")
        }
        Op::LoadLit { dst, addr, .. } => format!("ldr {dst:?}, [{addr:#x}]"),
        Op::LoadPair { rt, rt2, base, offset, .. } => {
            format!("ldp {rt:?}, {rt2:?}, [{base:?}, #{offset}]")
        }
        Op::StorePair { rt, rt2, base, offset, .. } => {
            format!("stp {rt:?}, {rt2:?}, [{base:?}, #{offset}]")
        }
        Op::Bitfield { dst, src, immr, imms, signed, .. } => {
            format!("bfm signed={signed} {dst:?}, {src:?}, #{immr}, #{imms}")
        }
        Op::Madd { dst, n, m, sub, .. } => format!("m{} {dst:?}, {n:?}, {m:?}", if *sub { "sub" } else { "add" }),
        Op::Csel { dst, cond, kind, .. } => format!("csel.{kind:?} {dst:?}, cond={cond}"),
        Op::LoadIndexed { dst, base, index, .. } => format!("ldr {dst:?}, [{base:?}, {index:?}]"),
        Op::StoreIndexed { src, base, index, .. } => format!("str {src:?}, [{base:?}, {index:?}]"),
        Op::Unsupported => "unimplemented".into(),
    }
}
