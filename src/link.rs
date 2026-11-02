//! Multi-module process layout and static linking (main, rtld, subsdk0..9, sdk, ...).

use std::collections::{BTreeMap, BTreeSet};

use crate::lift::cfg::CodeSource;
use crate::module::{Format, Module, SymbolKind, IMPORT_SENTINEL_BASE};

pub const CODE_BASE: u64 = 0x0800_0000;
const MODULE_ALIGN: u64 = 0x20_0000;
const R_AARCH64_ABS64: u32 = 257;
const R_AARCH64_GLOB_DAT: u32 = 1025;
const R_AARCH64_JUMP_SLOT: u32 = 1026;
const R_AARCH64_RELATIVE: u32 = 1027;

#[derive(Debug, Clone)]
pub struct ImageSegment {
    pub va: u64,
    pub size: u64,
    pub perm: u32,
    pub mem_type: u32,
    pub module: String,
    pub bytes: Vec<u8>,
}

pub struct Program {
    pub modules: Vec<Module>,
    pub entry: u64,
    pub is_nro: bool,
    pub imports: Vec<(u64, String)>,
    pub resolved_imports: usize,
}

/// Horizon load order: rtld/main first, then subsdk0..9, then sdk, then anything else.
pub fn module_rank(name: &str) -> (u32, String) {
    let n = name.rsplit(['/', '\\']).next().unwrap_or(name).to_ascii_lowercase();
    let stem = n.split('.').next().unwrap_or(&n).to_string();
    let rank = match stem.as_str() {
        "rtld" => 0,
        "main" => 1,
        "sdk" => 20,
        s if s.starts_with("subsdk") => 2 + s[6..].parse::<u32>().unwrap_or(9).min(17),
        _ => 30,
    };
    (rank, stem)
}

fn align_up(v: u64, a: u64) -> u64 {
    (v + a - 1) & !(a - 1)
}

fn span(m: &Module) -> u64 {
    let end = [m.text.end(), m.rodata.end(), m.data.end(), m.bss.end(), m.image_base + m.image.len() as u64]
        .into_iter()
        .max()
        .unwrap_or(m.image_base);
    end - m.image_base
}

fn rebase(m: &mut Module, base: u64) {
    let delta = base.wrapping_sub(m.image_base);
    if delta == 0 {
        return;
    }
    let relocs = m.relocations.clone();
    for r in relocs.iter().filter(|r| r.applied) {
        if matches!(r.kind, R_AARCH64_RELATIVE | R_AARCH64_ABS64 | R_AARCH64_GLOB_DAT | R_AARCH64_JUMP_SLOT) {
            if let Some(v) = m.read_u64(r.offset) {
                if v < IMPORT_SENTINEL_BASE && (r.kind == R_AARCH64_RELATIVE || v != 0) {
                    m.write_bytes(r.offset, &v.wrapping_add(delta).to_le_bytes());
                }
            }
        }
    }
    m.image_base = base;
    for seg in [&mut m.text, &mut m.rodata, &mut m.data, &mut m.bss] {
        seg.va = seg.va.wrapping_add(delta);
    }
    m.entry = m.entry.wrapping_add(delta);
    for i in &mut m.init {
        *i = i.wrapping_add(delta);
    }
    for s in m.symbols.iter_mut().filter(|s| s.defined) {
        s.va = s.va.wrapping_add(delta);
    }
    for imp in &mut m.imports {
        imp.slot_va = imp.slot_va.map(|v| v.wrapping_add(delta));
    }
    for r in &mut m.relocations {
        r.offset = r.offset.wrapping_add(delta);
    }
}

pub fn link(mut modules: Vec<Module>) -> Program {
    modules.sort_by_key(|m| module_rank(&m.name));
    let mut base = CODE_BASE;
    for m in &mut modules {
        rebase(m, base);
        base = align_up(base + span(m).max(0x1000), MODULE_ALIGN);
    }
    let mut exports: BTreeMap<String, u64> = BTreeMap::new();
    for m in &modules {
        for s in m.symbols.iter().filter(|s| s.defined && !s.name.is_empty() && s.va != 0) {
            exports.entry(s.name.clone()).or_insert(s.va);
        }
    }
    let mut imports = Vec::new();
    let mut resolved = 0;
    for m in &mut modules {
        let mut remaining = Vec::new();
        for mut imp in std::mem::take(&mut m.imports) {
            let addend = imp
                .slot_va
                .and_then(|slot| m.relocations.iter().find(|r| r.offset == slot).map(|r| r.addend))
                .unwrap_or(0);
            match (exports.get(&imp.name), imp.slot_va) {
                (Some(&va), Some(slot)) => {
                    m.write_bytes(slot, &va.wrapping_add(addend as u64).to_le_bytes());
                    resolved += 1;
                }
                _ => {
                    imp.sentinel = IMPORT_SENTINEL_BASE + imports.len() as u64 * 16;
                    if let Some(slot) = imp.slot_va {
                        m.write_bytes(slot, &imp.sentinel.to_le_bytes());
                    }
                    imports.push((imp.sentinel, imp.name.clone()));
                    remaining.push(imp);
                }
            }
        }
        m.imports = remaining;
    }
    let entry = modules.first().map(|m| m.entry).unwrap_or(0);
    let is_nro = modules.first().map(|m| m.format == Format::Nro).unwrap_or(false);
    Program { modules, entry, is_nro, imports, resolved_imports: resolved }
}

impl Program {
    pub fn module_at(&self, va: u64) -> Option<&Module> {
        self.modules.iter().find(|m| va >= m.image_base && va < m.image_base + span(m))
    }

    pub fn segments(&self) -> Vec<ImageSegment> {
        let mut out = Vec::new();
        for m in &self.modules {
            let rw_end = m.data.end().max(m.bss.end());
            let parts = [
                (m.text.va, m.text.end(), 5u32, 3u32),
                (m.rodata.va, m.rodata.end(), 1, 3),
                (m.data.va.min(if m.bss.size > 0 { m.bss.va } else { m.data.va }), rw_end, 3, 4),
            ];
            for (start, end, perm, mem_type) in parts {
                if end <= start {
                    continue;
                }
                let va = start & !0xFFF;
                let size = align_up(end, 0x1000) - va;
                let avail = (m.image_base + m.image.len() as u64).saturating_sub(va).min(size) as usize;
                let bytes = m.read(va, avail).map(|b| b.to_vec()).unwrap_or_default();
                out.push(ImageSegment { va, size, perm, mem_type, module: m.name.clone(), bytes });
            }
        }
        // overlapping page-rounded segments: keep the earlier one's bytes, trim later starts
        out.sort_by_key(|s| s.va);
        for i in 1..out.len() {
            let prev_end = out[i - 1].va + out[i - 1].size;
            if out[i].va < prev_end {
                let cut = prev_end - out[i].va;
                let s = &mut out[i];
                s.va += cut;
                s.size = s.size.saturating_sub(cut);
                s.bytes.drain(..(cut as usize).min(s.bytes.len()));
            }
        }
        out.retain(|s| s.size > 0);
        out
    }

    /// Code entry points visible statically: module entries, init functions, exported functions,
    /// pointers into code from data, and ADRP+ADD address materializations in code.
    pub fn entry_points(&self) -> BTreeSet<u64> {
        let mut out = BTreeSet::new();
        for m in &self.modules {
            out.insert(m.entry);
            out.extend(m.init.iter().copied());
            for s in &m.symbols {
                if s.defined && matches!(s.kind, SymbolKind::Func) && m.in_text(s.va) {
                    out.insert(s.va);
                }
            }
            for seg in [&m.rodata, &m.data] {
                let mut va = align_up(seg.va, 8);
                while va + 8 <= seg.end() {
                    if let Some(v) = m.read_u64(va) {
                        if v % 4 == 0 && self.is_code(v) {
                            out.insert(v);
                        }
                    }
                    va += 8;
                }
            }
            let mut va = m.text.va;
            while va + 8 <= m.text.end() {
                let (Some(a), Some(b)) = (m.read_u32(va), m.read_u32(va + 4)) else { break };
                if a & 0x9F00_0000 == 0x9000_0000 && b & 0xFF80_0000 == 0x9100_0000 && b & 0x1F == a & 0x1F && (b >> 5) & 0x1F == a & 0x1F {
                    let imm = (((a >> 5) & 0x7FFFF) as u64) << 2 | ((a >> 29) & 3) as u64;
                    let page = (va & !0xFFF).wrapping_add((((imm << 12) << 31) as i64 >> 31) as u64);
                    let t = page.wrapping_add(((b >> 10) & 0xFFF) as u64);
                    if t % 4 == 0 && self.is_code(t) {
                        out.insert(t);
                    }
                }
                va += 4;
            }
        }
        out.retain(|&a| self.is_code(a) && a % 4 == 0);
        out
    }
}

impl CodeSource for Program {
    fn word(&self, va: u64) -> Option<u32> {
        self.module_at(va)?.read_u32(va)
    }
    fn is_code(&self, va: u64) -> bool {
        self.module_at(va).map(|m| m.in_text(va)).unwrap_or(false)
    }
    fn read_bytes(&self, va: u64, len: usize) -> Option<Vec<u8>> {
        self.module_at(va)?.read(va, len).map(|b| b.to_vec())
    }
}
