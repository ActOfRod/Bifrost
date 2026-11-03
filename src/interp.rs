use std::fmt;

use crate::hos::{self, SvcEffect};
use crate::lift::{
    bitfield_move, AluOp, CselKind, ExtendKind, GReg, IndexMode, Insn, Lifted, MovKind, Op,
    ShiftKind, Value, Width,
};
use crate::module::{AddressSpace, Module, STACK_SIZE};

pub const HALT_LR: u64 = 0xFFFF_FFFF_FFFF_FFFE;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Stop {
    Return,
    Exit,
    Break,
    Unsupported { addr: u64, word: u32 },
    Unmapped { addr: u64 },
    StepLimit,
    Svc { addr: u64, imm: u16, name: String },
}

impl fmt::Display for Stop {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Return => write!(f, "returned"),
            Self::Exit => write!(f, "process exit"),
            Self::Break => write!(f, "svcBreak"),
            Self::Unsupported { addr, word } => {
                write!(f, "unimplemented instruction {word:#x} at {addr:#x}")
            }
            Self::Unmapped { addr } => write!(f, "unmapped address {addr:#x}"),
            Self::StepLimit => write!(f, "step limit"),
            Self::Svc { addr, imm, name } => {
                write!(f, "unimplemented {name} #{imm:#x} at {addr:#x}")
            }
        }
    }
}

#[derive(Debug, Clone)]
pub struct ExecResult {
    pub x0: u64,
    pub steps: u64,
    pub imports: Vec<String>,
    pub debug: Vec<String>,
    pub stop: Stop,
}

#[derive(Clone)]
struct Cpu {
    x: [u64; 31],
    sp: u64,
    nzcv: u32,
    pc: u64,
    tick: u64,
}

pub fn execute(
    module: &mut Module,
    lifted: &Lifted,
    entry: u64,
    args: &[u64],
    max_steps: u64,
    boot: bool,
) -> ExecResult {
    let mut space = AddressSpace::new(module);
    let mut cpu = Cpu {
        x: [0; 31],
        sp: (crate::module::STACK_VA + STACK_SIZE as u64) & !0xF,
        nzcv: 0,
        pc: entry,
        tick: 0,
    };
    for (i, arg) in args.iter().take(8).enumerate() {
        cpu.x[i] = *arg;
    }
    cpu.x[30] = HALT_LR;

    let mut imports = Vec::new();
    let mut debug = Vec::new();
    let mut steps = 0u64;
    let mut targets = Vec::new();
    if boot {
        targets.extend(space.module.init.iter().copied());
    }
    targets.push(entry);

    let mut stop = Stop::Return;
    'targets: for target in targets {
        cpu.pc = target;
        cpu.x[30] = HALT_LR;
        while cpu.pc != HALT_LR {
            if steps >= max_steps {
                stop = Stop::StepLimit;
                break 'targets;
            }
            steps += 1;
            let Some(insn) = lifted.insns.get(&cpu.pc) else {
                if let Some(effect) = take_import(&mut cpu, space.module, &mut imports) {
                    if !effect {
                        stop = Stop::Unmapped { addr: cpu.pc };
                        break 'targets;
                    }
                    continue;
                }
                stop = Stop::Unmapped { addr: cpu.pc };
                break 'targets;
            };
            match step(&mut cpu, &mut space, insn, &mut imports, &mut debug) {
                Step::Next => {}
                Step::Stop(reason) => {
                    stop = reason;
                    break 'targets;
                }
            }
        }
    }

    ExecResult {
        x0: cpu.x[0],
        steps,
        imports,
        debug,
        stop,
    }
}

enum Step {
    Next,
    Stop(Stop),
}

fn step(
    cpu: &mut Cpu,
    space: &mut AddressSpace<'_>,
    insn: &Insn,
    imports: &mut Vec<String>,
    debug: &mut Vec<String>,
) -> Step {
    let pc = insn.address;
    match insn.op {
        Op::Nop => cpu.pc = pc.wrapping_add(4),
        Op::SetImm { dst, value } => {
            write_reg(cpu, dst, Width::W64, value);
            cpu.pc = pc.wrapping_add(4);
        }
        Op::MovWide {
            dst,
            imm,
            shift,
            kind,
            width,
        } => {
            let mask = mask_of(width);
            let shifted = (imm as u64).wrapping_shl(shift as u32) & mask;
            let current = read_reg(cpu, dst, width);
            let value = match kind {
                MovKind::Z => shifted,
                MovKind::K => (current & !(0xFFFFu64.wrapping_shl(shift as u32) & mask)) | shifted,
                MovKind::N => (!shifted) & mask,
            };
            write_reg(cpu, dst, width, value);
            cpu.pc = pc.wrapping_add(4);
        }
        Op::Alu {
            op,
            dst,
            lhs,
            rhs,
            shift,
            extend,
            invert_rhs,
            set_flags,
            width,
        } => {
            let a = read_reg(cpu, lhs, width);
            let mut b = match rhs {
                Value::Imm(imm) => imm,
                Value::Reg(reg) => read_reg(cpu, reg, Width::W64),
            };
            if let Some((kind, amount)) = shift {
                b = apply_shift(b, kind, amount, width);
            }
            if let Some((kind, amount)) = extend {
                b = apply_extend(b, kind, amount);
            }
            if invert_rhs {
                b = !b;
            }
            b &= mask_of(width);
            let (result, flags) = match op {
                AluOp::Add => add_flags(a, b, width, set_flags),
                AluOp::Sub => sub_flags(a, b, width, set_flags),
                AluOp::And => logic_flags(a & b, width, set_flags),
                AluOp::Or => logic_flags(a | b, width, set_flags),
                AluOp::Xor => logic_flags(a ^ b, width, set_flags),
            };
            if set_flags {
                cpu.nzcv = flags;
            }
            write_reg(cpu, dst, width, result);
            cpu.pc = pc.wrapping_add(4);
        }
        Op::Bitfield {
            dst,
            src,
            immr,
            imms,
            signed,
            width,
        } => {
            let value = bitfield_move(
                read_reg(cpu, src, width),
                immr as u32,
                imms as u32,
                width.bits(),
                signed,
            );
            write_reg(cpu, dst, width, value);
            cpu.pc = pc.wrapping_add(4);
        }
        Op::Madd {
            dst,
            n,
            m,
            a,
            sub,
            width,
        } => {
            let mask = mask_of(width);
            let left = read_reg(cpu, n, width);
            let right = read_reg(cpu, m, width);
            let acc = read_reg(cpu, a, width);
            let product = left.wrapping_mul(right) & mask;
            let value = if sub {
                acc.wrapping_sub(product)
            } else {
                acc.wrapping_add(product)
            } & mask;
            write_reg(cpu, dst, width, value);
            cpu.pc = pc.wrapping_add(4);
        }
        Op::Csel {
            dst,
            a,
            b,
            cond,
            kind,
            width,
        } => {
            let take_a = cond_holds(cond, cpu.nzcv);
            let value = if take_a {
                read_reg(cpu, a, width)
            } else {
                let raw = read_reg(cpu, b, width);
                match kind {
                    CselKind::Sel => raw,
                    CselKind::Inc => raw.wrapping_add(1) & mask_of(width),
                    CselKind::Inv => (!raw) & mask_of(width),
                    CselKind::Neg => raw.wrapping_neg() & mask_of(width),
                }
            };
            write_reg(cpu, dst, width, value);
            cpu.pc = pc.wrapping_add(4);
        }
        Op::Load {
            dst,
            base,
            offset,
            index,
            width,
            signed,
        } => {
            let (addr, writeback) = mem_addr(cpu, base, offset, index);
            if let Some(updated) = writeback {
                if index == IndexMode::Pre {
                    write_reg(cpu, base, Width::W64, updated);
                }
            }
            let Some(raw) = space.load(addr, width.bytes()) else {
                return Step::Stop(Stop::Unmapped { addr });
            };
            let value = extend_load(raw, width, signed);
            write_reg(
                cpu,
                dst,
                if signed {
                    Width::W64
                } else {
                    width_for_load(width)
                },
                value,
            );
            if index == IndexMode::Post {
                if let Some(updated) = writeback {
                    write_reg(cpu, base, Width::W64, updated);
                }
            }
            cpu.pc = pc.wrapping_add(4);
        }
        Op::Store {
            src,
            base,
            offset,
            index,
            width,
        } => {
            let (addr, writeback) = mem_addr(cpu, base, offset, index);
            if index == IndexMode::Pre {
                if let Some(updated) = writeback {
                    write_reg(cpu, base, Width::W64, updated);
                }
            }
            let value = read_reg(cpu, src, width);
            if !space.store(addr, width.bytes(), value) {
                return Step::Stop(Stop::Unmapped { addr });
            }
            if index == IndexMode::Post {
                if let Some(updated) = writeback {
                    write_reg(cpu, base, Width::W64, updated);
                }
            }
            cpu.pc = pc.wrapping_add(4);
        }
        Op::LoadIndexed {
            dst,
            base,
            index,
            extend,
            shift,
            width,
            signed,
        } => {
            let addr = indexed_addr(cpu, base, index, extend, shift);
            let Some(raw) = space.load(addr, width.bytes()) else {
                return Step::Stop(Stop::Unmapped { addr });
            };
            write_reg(
                cpu,
                dst,
                if signed {
                    Width::W64
                } else {
                    width_for_load(width)
                },
                extend_load(raw, width, signed),
            );
            cpu.pc = pc.wrapping_add(4);
        }
        Op::StoreIndexed {
            src,
            base,
            index,
            extend,
            shift,
            width,
        } => {
            let addr = indexed_addr(cpu, base, index, extend, shift);
            if !space.store(addr, width.bytes(), read_reg(cpu, src, width)) {
                return Step::Stop(Stop::Unmapped { addr });
            }
            cpu.pc = pc.wrapping_add(4);
        }
        Op::LoadPair {
            rt,
            rt2,
            base,
            offset,
            index,
            width,
        } => {
            let (addr, writeback) = mem_addr(cpu, base, offset, index);
            if index == IndexMode::Pre {
                if let Some(updated) = writeback {
                    write_reg(cpu, base, Width::W64, updated);
                }
            }
            let step_size = width.bytes();
            let Some(first) = space.load(addr, step_size) else {
                return Step::Stop(Stop::Unmapped { addr });
            };
            let second_addr = addr.wrapping_add(step_size as u64);
            let Some(second) = space.load(second_addr, step_size) else {
                return Step::Stop(Stop::Unmapped { addr: second_addr });
            };
            write_reg(cpu, rt, width, first);
            write_reg(cpu, rt2, width, second);
            if index == IndexMode::Post {
                if let Some(updated) = writeback {
                    write_reg(cpu, base, Width::W64, updated);
                }
            }
            cpu.pc = pc.wrapping_add(4);
        }
        Op::StorePair {
            rt,
            rt2,
            base,
            offset,
            index,
            width,
        } => {
            let (addr, writeback) = mem_addr(cpu, base, offset, index);
            if index == IndexMode::Pre {
                if let Some(updated) = writeback {
                    write_reg(cpu, base, Width::W64, updated);
                }
            }
            let step_size = width.bytes();
            if !space.store(addr, step_size, read_reg(cpu, rt, width)) {
                return Step::Stop(Stop::Unmapped { addr });
            }
            let second = addr.wrapping_add(step_size as u64);
            if !space.store(second, step_size, read_reg(cpu, rt2, width)) {
                return Step::Stop(Stop::Unmapped { addr: second });
            }
            if index == IndexMode::Post {
                if let Some(updated) = writeback {
                    write_reg(cpu, base, Width::W64, updated);
                }
            }
            cpu.pc = pc.wrapping_add(4);
        }
        Op::LoadLit {
            dst,
            addr,
            width,
            signed,
        } => {
            let Some(raw) = space.load(addr, width.bytes()) else {
                return Step::Stop(Stop::Unmapped { addr });
            };
            write_reg(
                cpu,
                dst,
                if signed {
                    Width::W64
                } else {
                    width_for_load(width)
                },
                extend_load(raw, width, signed),
            );
            cpu.pc = pc.wrapping_add(4);
        }
        Op::B { target } => return transfer(cpu, space.module, target, false, pc, imports),
        Op::Bl { target } => return transfer(cpu, space.module, target, true, pc, imports),
        Op::BCond { cond, target } => {
            cpu.pc = if cond_holds(cond, cpu.nzcv) {
                target
            } else {
                pc.wrapping_add(4)
            };
        }
        Op::Cbz {
            reg,
            is_zero,
            target,
            width,
        } => {
            let value = read_reg(cpu, reg, width);
            let taken = if is_zero { value == 0 } else { value != 0 };
            cpu.pc = if taken { target } else { pc.wrapping_add(4) };
        }
        Op::Tbz {
            reg,
            bit,
            is_zero,
            target,
        } => {
            let value = read_reg(cpu, reg, Width::W64);
            let set = (value >> bit) & 1 == 1;
            let taken = if is_zero { !set } else { set };
            cpu.pc = if taken { target } else { pc.wrapping_add(4) };
        }
        Op::Br { reg, link } => {
            let target = read_reg(cpu, reg, Width::W64);
            return transfer(cpu, space.module, target, link, pc, imports);
        }
        Op::Ret { reg } => {
            cpu.pc = read_reg(cpu, reg, Width::W64);
        }
        Op::Svc { imm } => match hos::classify_svc(imm) {
            SvcEffect::Continue => {
                if imm == 0x1E {
                    cpu.tick = cpu.tick.wrapping_add(1);
                    cpu.x[0] = cpu.tick;
                }
                cpu.pc = pc.wrapping_add(4);
            }
            SvcEffect::DebugString(_) => {
                let ptr = cpu.x[0];
                let len = cpu.x[1] as usize;
                let text = read_guest_string(space, ptr, len.min(4096));
                debug.push(text);
                cpu.x[0] = 0;
                cpu.pc = pc.wrapping_add(4);
            }
            SvcEffect::Exit => return Step::Stop(Stop::Exit),
            SvcEffect::Break => return Step::Stop(Stop::Break),
            SvcEffect::Unimplemented => {
                let name = hos::svc_name(imm).unwrap_or("svc").to_string();
                return Step::Stop(Stop::Svc {
                    addr: pc,
                    imm,
                    name,
                });
            }
        },
        Op::Brk { .. } => return Step::Stop(Stop::Break),
        Op::Unsupported => {
            return Step::Stop(Stop::Unsupported {
                addr: pc,
                word: insn.word,
            });
        }
    }
    Step::Next
}

fn transfer(
    cpu: &mut Cpu,
    module: &Module,
    target: u64,
    link: bool,
    pc: u64,
    imports: &mut Vec<String>,
) -> Step {
    if link {
        write_reg(cpu, GReg::X(30), Width::W64, pc.wrapping_add(4));
    }
    if module.import_by_sentinel(target).is_some() || target == 0 {
        cpu.pc = target;
        return if take_import(cpu, module, imports).unwrap_or(false) {
            Step::Next
        } else {
            Step::Stop(Stop::Unmapped { addr: target })
        };
    }
    cpu.pc = target;
    Step::Next
}

fn take_import(cpu: &mut Cpu, module: &Module, imports: &mut Vec<String>) -> Option<bool> {
    let import = module.import_by_sentinel(cpu.pc)?;
    imports.push(import.name.clone());
    cpu.x[0] = 0;
    cpu.pc = cpu.x[30];
    Some(true)
}

fn read_guest_string(space: &AddressSpace<'_>, va: u64, len: usize) -> String {
    let mut bytes = Vec::with_capacity(len);
    for i in 0..len {
        match space.load(va.wrapping_add(i as u64), 1) {
            Some(b) => bytes.push(b as u8),
            None => break,
        }
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

fn mem_addr(cpu: &Cpu, base: GReg, offset: i64, index: IndexMode) -> (u64, Option<u64>) {
    let base_val = read_reg(cpu, base, Width::W64);
    let updated = base_val.wrapping_add(offset as u64);
    match index {
        IndexMode::None => (base_val.wrapping_add(offset as u64), None),
        IndexMode::Pre => (updated, Some(updated)),
        IndexMode::Post => (base_val, Some(updated)),
    }
}

fn indexed_addr(cpu: &Cpu, base: GReg, index: GReg, extend: ExtendKind, shift: u8) -> u64 {
    let base_val = read_reg(cpu, base, Width::W64);
    let index_val = apply_extend(read_reg(cpu, index, Width::W64), extend, shift);
    base_val.wrapping_add(index_val)
}

fn read_reg(cpu: &Cpu, reg: GReg, width: Width) -> u64 {
    let value = match reg {
        GReg::X(i) => cpu.x[i as usize],
        GReg::Sp => cpu.sp,
        GReg::Zr => 0,
    };
    value & mask_of(width)
}

fn write_reg(cpu: &mut Cpu, reg: GReg, width: Width, value: u64) {
    let value = if width == Width::W32 {
        value & 0xFFFF_FFFF
    } else if width == Width::W64 {
        value
    } else {
        // Loads of W8/W16 zero-extend into the full register.
        value & mask_of(Width::W64)
    };
    match reg {
        GReg::X(i) => cpu.x[i as usize] = value,
        GReg::Sp => cpu.sp = value,
        GReg::Zr => {}
    }
}

fn width_for_load(width: Width) -> Width {
    match width {
        Width::W32 => Width::W32,
        _ => Width::W64,
    }
}

fn extend_load(raw: u64, width: Width, signed: bool) -> u64 {
    if !signed {
        return raw & mask_of(width);
    }
    let bits = width.bits();
    let shift = 64 - bits;
    ((raw << shift) as i64 >> shift) as u64
}

fn mask_of(width: Width) -> u64 {
    match width {
        Width::W8 => 0xFF,
        Width::W16 => 0xFFFF,
        Width::W32 => 0xFFFF_FFFF,
        Width::W64 => u64::MAX,
    }
}

fn apply_shift(value: u64, kind: ShiftKind, amount: u8, width: Width) -> u64 {
    let amount = amount as u32;
    let value = value & mask_of(width);
    if amount == 0 {
        return value;
    }
    match kind {
        ShiftKind::Lsl => value.wrapping_shl(amount) & mask_of(width),
        ShiftKind::Lsr => value >> amount,
        ShiftKind::Asr => {
            if width == Width::W32 {
                ((value as i32) >> amount.min(31)) as u32 as u64
            } else {
                ((value as i64) >> amount.min(63)) as u64
            }
        }
        ShiftKind::Ror => {
            let bits = width.bits();
            let amount = amount % bits;
            if width == Width::W32 {
                (value as u32).rotate_right(amount) as u64
            } else {
                value.rotate_right(amount)
            }
        }
    }
}

fn apply_extend(value: u64, kind: ExtendKind, amount: u8) -> u64 {
    let (extracted, bits, signed) = match kind {
        ExtendKind::Uxtb => (value & 0xFF, 8, false),
        ExtendKind::Sxtb => (value & 0xFF, 8, true),
        ExtendKind::Uxth => (value & 0xFFFF, 16, false),
        ExtendKind::Sxth => (value & 0xFFFF, 16, true),
        ExtendKind::Uxtw => (value & 0xFFFF_FFFF, 32, false),
        ExtendKind::Sxtw => (value & 0xFFFF_FFFF, 32, true),
        ExtendKind::Uxtx => (value, 64, false),
        ExtendKind::Sxtx => (value, 64, true),
    };
    let extended = if signed && bits < 64 {
        let shift = 64 - bits;
        ((extracted << shift) as i64 >> shift) as u64
    } else {
        extracted
    };
    extended.wrapping_shl(amount as u32)
}

fn add_flags(a: u64, b: u64, width: Width, set_flags: bool) -> (u64, u32) {
    let bits = width.bits();
    let mask = mask_of(width);
    let a = a & mask;
    let b = b & mask;
    let result = a.wrapping_add(b) & mask;
    if !set_flags {
        return (result, 0);
    }
    let n = ((result >> (bits - 1)) & 1) as u32;
    let z = u32::from(result == 0);
    let c = u32::from((a as u128) + (b as u128) > mask as u128);
    let sign_a = (a >> (bits - 1)) & 1;
    let sign_b = (b >> (bits - 1)) & 1;
    let sign_r = (result >> (bits - 1)) & 1;
    let v = u32::from(sign_a == sign_b && sign_a != sign_r);
    (result, (n << 3) | (z << 2) | (c << 1) | v)
}

fn sub_flags(a: u64, b: u64, width: Width, set_flags: bool) -> (u64, u32) {
    let bits = width.bits();
    let mask = mask_of(width);
    let a = a & mask;
    let b = b & mask;
    let result = a.wrapping_sub(b) & mask;
    if !set_flags {
        return (result, 0);
    }
    let n = ((result >> (bits - 1)) & 1) as u32;
    let z = u32::from(result == 0);
    let c = u32::from(a >= b);
    let sign_a = (a >> (bits - 1)) & 1;
    let sign_b = (b >> (bits - 1)) & 1;
    let sign_r = (result >> (bits - 1)) & 1;
    let v = u32::from(sign_a != sign_b && sign_a != sign_r);
    (result, (n << 3) | (z << 2) | (c << 1) | v)
}

fn logic_flags(result: u64, width: Width, set_flags: bool) -> (u64, u32) {
    let result = result & mask_of(width);
    if !set_flags {
        return (result, 0);
    }
    let n = ((result >> (width.bits() - 1)) & 1) as u32;
    let z = u32::from(result == 0);
    (result, (n << 3) | (z << 2))
}

fn cond_holds(cond: u8, nzcv: u32) -> bool {
    let n = nzcv & 8 != 0;
    let z = nzcv & 4 != 0;
    let c = nzcv & 2 != 0;
    let v = nzcv & 1 != 0;
    let base = match cond & 0xE {
        0x0 => z,
        0x2 => c,
        0x4 => n,
        0x6 => v,
        0x8 => c && !z,
        0xA => n == v,
        0xC => !z && (n == v),
        _ => true,
    };
    if cond & 1 == 1 && cond != 0xF {
        !base
    } else {
        base
    }
}
