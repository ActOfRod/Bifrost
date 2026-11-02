use crate::error::{Error, Result};
use crate::module::{Import, Module, Reloc, Symbol, SymbolKind, IMPORT_SENTINEL_BASE};

const DT_NULL: i64 = 0;
const DT_NEEDED: i64 = 1;
const DT_HASH: i64 = 4;
const DT_STRTAB: i64 = 5;
const DT_SYMTAB: i64 = 6;
const DT_RELA: i64 = 7;
const DT_RELASZ: i64 = 8;
const DT_STRSZ: i64 = 10;
const DT_INIT: i64 = 12;
const DT_JMPREL: i64 = 23;
const DT_INIT_ARRAY: i64 = 25;
const DT_INIT_ARRAYSZ: i64 = 27;

const R_AARCH64_ABS64: u32 = 257;
const R_AARCH64_ABS32: u32 = 258;
const R_AARCH64_GLOB_DAT: u32 = 1025;
const R_AARCH64_JUMP_SLOT: u32 = 1026;
const R_AARCH64_RELATIVE: u32 = 1027;

pub fn finalize(module: &mut Module) -> Result<()> {
    if let Some(mod0) = find_mod0(module) {
        module.notes.push(format!("MOD0 at {mod0:#x}"));
        if module.hint.dynamic_va.is_none() {
            module.hint.dynamic_va = Some(mod0);
        }
        // dynamic_va currently holds MOD0; the dynamic offset is inside MOD0.
        let dyn_off = read_i32(module, mod0 + 4).unwrap_or(0);
        module.hint.dynamic_va = Some(mod0.wrapping_add(dyn_off as u64));
    }

    let dynamic = match module.hint.dynamic_va {
        Some(va) => read_dynamic(module, va),
        None => Vec::new(),
    };
    if !dynamic.is_empty() {
        module
            .notes
            .push(format!("dynamic section has {} entries", dynamic.len()));
    }

    let strtab = dyn_val(&dynamic, DT_STRTAB).unwrap_or(module.hint.dynstr_va);
    let strsz = dyn_val(&dynamic, DT_STRSZ).unwrap_or(module.hint.dynstr_size);
    let symtab = dyn_val(&dynamic, DT_SYMTAB).unwrap_or(module.hint.dynsym_va);
    let mut sym_count = if module.hint.dynsym_size >= 24 {
        module.hint.dynsym_size / 24
    } else {
        0
    };
    if let Some(hash) = dyn_val(&dynamic, DT_HASH) {
        if let Some(nchain) = module.read_u32(hash + 4) {
            sym_count = nchain as u64;
        }
    }
    if sym_count == 0 && symtab != 0 && strsz != 0 {
        sym_count = scan_symbol_count(module, symtab, strsz);
    }

    if symtab != 0 && sym_count > 0 {
        module.symbols = read_symbols(module, symtab, sym_count, strtab, strsz);
        module.notes.push(format!(
            "dynsym at {symtab:#x}, {} symbols",
            module.symbols.len()
        ));
    }

    for (tag, val) in &dynamic {
        if *tag == DT_NEEDED {
            if let Some(name) = read_cstr(module, strtab + *val, 256) {
                if !name.is_empty() {
                    module.needed.push(name);
                }
            }
        }
    }

    let mut relas = Vec::new();
    if let (Some(rela), Some(relasz)) = (dyn_val(&dynamic, DT_RELA), dyn_val(&dynamic, DT_RELASZ)) {
        relas.push((rela, relasz));
    }
    if let (Some(jmprel), Some(pltrelsz)) = (
        dyn_val(&dynamic, DT_JMPREL),
        dyn_val(&dynamic, 2), // DT_PLTRELSZ
    ) {
        relas.push((jmprel, pltrelsz));
    }
    for (rela, relasz) in relas {
        parse_rela(module, rela, relasz)?;
    }
    apply_relocs(module)?;
    read_init(module, &dynamic);
    Ok(())
}

fn find_mod0(module: &Module) -> Option<u64> {
    let text = module.text.va;
    let word = module.read_u32(text.wrapping_add(4))? as u64;
    let candidates = [
        text.wrapping_add(word),
        text.wrapping_add(4).wrapping_add(word as i32 as u64),
    ];
    candidates.into_iter().find(|va| {
        module
            .read(*va, 4)
            .is_some_and(|bytes| bytes == b"MOD0")
    })
}

fn read_i32(module: &Module, va: u64) -> Option<i32> {
    Some(module.read_u32(va)? as i32)
}

fn read_dynamic(module: &Module, va: u64) -> Vec<(i64, u64)> {
    let mut out = Vec::new();
    let mut cursor = va;
    for _ in 0..4096 {
        let Some(bytes) = module.read(cursor, 16) else {
            break;
        };
        let tag = i64::from_le_bytes(bytes[0..8].try_into().unwrap());
        let val = u64::from_le_bytes(bytes[8..16].try_into().unwrap());
        out.push((tag, val));
        if tag == DT_NULL {
            break;
        }
        cursor = cursor.wrapping_add(16);
    }
    out
}

fn dyn_val(dynamic: &[(i64, u64)], tag: i64) -> Option<u64> {
    dynamic
        .iter()
        .find(|(found, _)| *found == tag)
        .map(|(_, val)| *val)
}

fn read_cstr(module: &Module, va: u64, limit: usize) -> Option<String> {
    let off = module.offset_of(va)?;
    let bytes = module.image.get(off..)?;
    let end = bytes.iter().take(limit).position(|b| *b == 0)?;
    let text = &bytes[..end];
    if text.iter().all(|b| b.is_ascii()) {
        Some(String::from_utf8_lossy(text).into_owned())
    } else {
        None
    }
}

fn scan_symbol_count(module: &Module, symtab: u64, strsz: u64) -> u64 {
    let mut count = 0u64;
    while count < 100_000 {
        let va = symtab + count * 24;
        let Some(name_off) = module.read_u32(va) else {
            break;
        };
        if count > 0 && name_off as u64 >= strsz {
            break;
        }
        count += 1;
    }
    count
}

fn read_symbols(
    module: &Module,
    symtab: u64,
    count: u64,
    strtab: u64,
    strsz: u64,
) -> Vec<Symbol> {
    let mut symbols = Vec::new();
    for i in 0..count {
        let va = symtab + i * 24;
        let Some(bytes) = module.read(va, 24) else {
            break;
        };
        let name_off = u32::from_le_bytes(bytes[0..4].try_into().unwrap());
        let info = bytes[4];
        let shndx = u16::from_le_bytes(bytes[6..8].try_into().unwrap());
        let value = u64::from_le_bytes(bytes[8..16].try_into().unwrap());
        let size = u64::from_le_bytes(bytes[16..24].try_into().unwrap());
        let name = if (name_off as u64) < strsz {
            read_cstr(module, strtab + name_off as u64, 512).unwrap_or_default()
        } else {
            String::new()
        };
        let kind = match info & 0xf {
            2 => SymbolKind::Func,
            1 => SymbolKind::Object,
            _ => SymbolKind::Other,
        };
        symbols.push(Symbol {
            name,
            va: value,
            size,
            defined: shndx != 0,
            kind,
        });
    }
    symbols
}

fn parse_rela(module: &mut Module, rela: u64, relasz: u64) -> Result<()> {
    if !relasz.is_multiple_of(24) {
        module
            .notes
            .push(format!("ignoring RELA at {rela:#x}: size {relasz} is not a multiple of 24"));
        return Ok(());
    }
    let count = relasz / 24;
    if count > 2_000_000 {
        return Err(Error::message("relocation table is unreasonably large"));
    }
    for i in 0..count {
        let va = rela + i * 24;
        let Some(bytes) = module.read(va, 24) else {
            module.notes.push(format!("RELA at {va:#x} is truncated"));
            break;
        };
        let offset = u64::from_le_bytes(bytes[0..8].try_into().unwrap());
        let info = u64::from_le_bytes(bytes[8..16].try_into().unwrap());
        let addend = i64::from_le_bytes(bytes[16..24].try_into().unwrap());
        let kind = info as u32;
        let sym_index = (info >> 32) as usize;
        let symbol = module
            .symbols
            .get(sym_index)
            .map(|sym| sym.name.clone())
            .unwrap_or_default();
        module.relocations.push(Reloc {
            offset,
            kind,
            symbol,
            addend,
            applied: false,
        });
    }
    Ok(())
}

fn apply_relocs(module: &mut Module) -> Result<()> {
    let mut next_import = module
        .imports
        .len() as u64;
    let relocs = module.relocations.clone();
    for reloc in relocs {
        let symbol = module.symbols.iter().find(|sym| {
            !reloc.symbol.is_empty() && sym.name == reloc.symbol
        });
        let defined = symbol.map(|sym| sym.defined).unwrap_or(false);
        let symbol_va = symbol.map(|sym| sym.va).unwrap_or(0);
        let applied = match reloc.kind {
            R_AARCH64_RELATIVE => {
                let value = module.image_base.wrapping_add(reloc.addend as u64);
                write_u64(module, reloc.offset, value)
            }
            R_AARCH64_ABS64 | R_AARCH64_GLOB_DAT | R_AARCH64_JUMP_SLOT => {
                if reloc.symbol.is_empty() || defined {
                    let value = symbol_va.wrapping_add(reloc.addend as u64);
                    write_u64(module, reloc.offset, value)
                } else {
                    let sentinel = IMPORT_SENTINEL_BASE + next_import;
                    next_import += 1;
                    module.imports.push(Import {
                        name: reloc.symbol.clone(),
                        slot_va: Some(reloc.offset),
                        sentinel,
                    });
                    write_u64(module, reloc.offset, sentinel)
                }
            }
            R_AARCH64_ABS32
                if (defined || reloc.symbol.is_empty()) => {
                    let value = symbol_va.wrapping_add(reloc.addend as u64) as u32;
                    write_u32(module, reloc.offset, value)
                }
            _ => false,
        };
        if let Some(stored) = module.relocations.iter_mut().find(|item| {
            item.offset == reloc.offset && item.kind == reloc.kind && item.addend == reloc.addend
        }) {
            stored.applied = applied;
        }
    }

    for symbol in module.symbols.clone() {
        if symbol.defined || symbol.name.is_empty() {
            continue;
        }
        if module.imports.iter().any(|imp| imp.name == symbol.name) {
            continue;
        }
        let sentinel = IMPORT_SENTINEL_BASE + next_import;
        next_import += 1;
        module.imports.push(Import {
            name: symbol.name,
            slot_va: None,
            sentinel,
        });
    }
    Ok(())
}

fn write_u64(module: &mut Module, va: u64, value: u64) -> bool {
    module.write_bytes(va, &value.to_le_bytes())
}

fn write_u32(module: &mut Module, va: u64, value: u32) -> bool {
    module.write_bytes(va, &value.to_le_bytes())
}

fn read_init(module: &mut Module, dynamic: &[(i64, u64)]) {
    if let Some(init) = dyn_val(dynamic, DT_INIT) {
        if init != 0 {
            module.init.push(init);
        }
    }
    let Some(array) = dyn_val(dynamic, DT_INIT_ARRAY) else {
        return;
    };
    let size = dyn_val(dynamic, DT_INIT_ARRAYSZ).unwrap_or(0);
    let count = size / 8;
    for i in 0..count {
        if let Some(ptr) = module.read_u64(array + i * 8) {
            if ptr != 0 {
                module.init.push(ptr);
            }
        }
    }
}
