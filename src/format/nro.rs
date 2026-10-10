use super::{empty_module, read_u32, SymbolHint};
use crate::error::{Error, Result};
use crate::module::{Format, Module, Segment, MAX_IMAGE};

pub fn parse(name: &str, data: &[u8], magic_off: usize) -> Result<Module> {
    if data.len() < magic_off + 0x80 {
        return Err(Error::message("NRO header is truncated"));
    }
    let size = read_u32(data, magic_off + 0x8)? as usize;
    let flags = read_u32(data, magic_off + 0xC)?;
    let text_off = read_u32(data, magic_off + 0x10)?;
    let text_size = read_u32(data, magic_off + 0x14)?;
    let ro_off = read_u32(data, magic_off + 0x18)?;
    let ro_size = read_u32(data, magic_off + 0x1C)?;
    let data_off = read_u32(data, magic_off + 0x20)?;
    let data_size = read_u32(data, magic_off + 0x24)?;
    let bss_size = read_u32(data, magic_off + 0x28)?;
    let mut module_id = [0u8; 32];
    let id_off = magic_off + 0x30;
    module_id.copy_from_slice(
        data.get(id_off..id_off + 32)
            .ok_or_else(|| Error::message("NRO module id is truncated"))?,
    );
    let dynstr_off = read_u32(data, magic_off + 0x60)?;
    let dynstr_size = read_u32(data, magic_off + 0x64)?;
    let dynsym_off = read_u32(data, magic_off + 0x68)?;
    let dynsym_size = read_u32(data, magic_off + 0x6C)?;

    if size == 0 || size > data.len() {
        return Err(Error::message(format!(
            "NRO size {size:#x} does not fit the file ({:#x} bytes)",
            data.len()
        )));
    }
    let bss_va = data_off as u64 + data_size as u64;
    let image_end = bss_va + bss_size as u64;
    let image_end = image_end.max(size as u64);
    if image_end > MAX_IMAGE {
        return Err(Error::message("NRO image exceeds the size limit"));
    }
    let mut image = vec![0u8; image_end as usize];
    image[..size].copy_from_slice(&data[..size]);

    let mut module = empty_module(Format::Nro, name);
    module.module_id = module_id;
    module.image = image;
    module.text = Segment {
        va: text_off as u64,
        size: text_size as u64,
    };
    module.rodata = Segment {
        va: ro_off as u64,
        size: ro_size as u64,
    };
    module.data = Segment {
        va: data_off as u64,
        size: data_size as u64,
    };
    module.bss = Segment {
        va: bss_va,
        size: bss_size as u64,
    };
    module.entry = module.text.va;
    module.notes.push(format!("NRO flags {flags:#x}"));
    if data.len() >= size + 4 && &data[size..size + 4] == b"ASET" {
        module
            .notes
            .push("homebrew asset section follows the NRO and is not mapped".into());
    }
    module.hint = SymbolHint {
        dynstr_va: dynstr_off as u64,
        dynstr_size: dynstr_size as u64,
        dynsym_va: dynsym_off as u64,
        dynsym_size: dynsym_size as u64,
        dynamic_va: None,
    };
    Ok(module)
}
