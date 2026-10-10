use super::{empty_module, read_u16, read_u32, read_u64, SymbolHint};
use crate::error::{Error, Result};
use crate::module::{Format, Module, Segment, MAX_IMAGE, MAX_SEGMENT};

const EM_AARCH64: u16 = 183;
const ET_EXEC: u16 = 2;
const ET_DYN: u16 = 3;
const PT_LOAD: u32 = 1;
const PT_DYNAMIC: u32 = 2;
const PF_X: u32 = 1;

pub fn parse(name: &str, data: &[u8]) -> Result<Module> {
    if data.len() < 64 {
        return Err(Error::message("ELF header is truncated"));
    }
    if data[4] != 2 || data[5] != 1 {
        return Err(Error::message(
            "only 64-bit little-endian ELF is supported",
        ));
    }
    let e_type = read_u16(data, 16)?;
    let e_machine = read_u16(data, 18)?;
    if e_machine != EM_AARCH64 {
        return Err(Error::message(format!(
            "ELF machine is {e_machine}, not AArch64 ({EM_AARCH64})"
        )));
    }
    if e_type != ET_EXEC && e_type != ET_DYN {
        return Err(Error::message(format!("unsupported ELF type {e_type}")));
    }
    let entry = read_u64(data, 24)?;
    let phoff = read_u64(data, 32)? as usize;
    let phentsize = read_u16(data, 54)? as usize;
    let phnum = read_u16(data, 56)? as usize;
    if phentsize < 56 {
        return Err(Error::message("ELF program header is too small"));
    }

    struct Load {
        offset: usize,
        va: u64,
        filesz: u64,
        memsz: u64,
        exec: bool,
    }
    let mut loads = Vec::new();
    let mut dynamic_va = None;
    for i in 0..phnum {
        let off = phoff
            .checked_add(i * phentsize)
            .ok_or_else(|| Error::message("program header table overflows"))?;
        let p_type = read_u32(data, off)?;
        let p_flags = read_u32(data, off + 4)?;
        let p_offset = read_u64(data, off + 8)?;
        let p_vaddr = read_u64(data, off + 16)?;
        let p_filesz = read_u64(data, off + 32)?;
        let p_memsz = read_u64(data, off + 40)?;
        if p_type == PT_DYNAMIC {
            dynamic_va = Some(p_vaddr);
        }
        if p_type != PT_LOAD || p_memsz == 0 {
            continue;
        }
        if p_memsz > MAX_SEGMENT {
            return Err(Error::message("ELF segment exceeds the size limit"));
        }
        loads.push(Load {
            offset: p_offset as usize,
            va: p_vaddr,
            filesz: p_filesz,
            memsz: p_memsz,
            exec: p_flags & PF_X != 0,
        });
    }
    if loads.is_empty() {
        return Err(Error::message("ELF has no loadable segments"));
    }
    let image_base = loads.iter().map(|l| l.va).min().unwrap();
    let image_end = loads
        .iter()
        .map(|l| l.va.saturating_add(l.memsz))
        .max()
        .unwrap();
    let span = image_end.saturating_sub(image_base);
    if span > MAX_IMAGE {
        return Err(Error::message("ELF image exceeds the size limit"));
    }
    let mut image = vec![0u8; span as usize];
    for load in &loads {
        let dst_off = (load.va - image_base) as usize;
        let file_len = load.filesz.min(load.memsz) as usize;
        let src = data.get(load.offset..load.offset + file_len).ok_or_else(|| {
            Error::message(format!(
                "ELF segment at file {:#x} is truncated",
                load.offset
            ))
        })?;
        image[dst_off..dst_off + file_len].copy_from_slice(src);
    }

    let text = loads.iter().find(|l| l.exec).or_else(|| loads.first());
    let text = text.unwrap();
    let ro = loads.iter().find(|l| !l.exec && l.va > text.va);
    let data_seg = loads.iter().rev().find(|l| !l.exec);

    let mut module = empty_module(Format::Elf, name);
    module.image_base = image_base;
    module.image = image;
    module.entry = entry;
    module.text = Segment {
        va: text.va,
        size: text.memsz,
    };
    if let Some(ro) = ro {
        module.rodata = Segment {
            va: ro.va,
            size: ro.memsz,
        };
    }
    if let Some(data_seg) = data_seg {
        module.data = Segment {
            va: data_seg.va,
            size: data_seg.filesz,
        };
        if data_seg.memsz > data_seg.filesz {
            module.bss = Segment {
                va: data_seg.va + data_seg.filesz,
                size: data_seg.memsz - data_seg.filesz,
            };
        }
    }
    module
        .notes
        .push(format!("ELF64 AArch64 type {e_type}, base {image_base:#x}"));
    module.hint = SymbolHint {
        dynamic_va,
        ..SymbolHint::default()
    };
    Ok(module)
}
