mod elf;
mod nro;
mod nso;

use crate::analysis;
use crate::error::{magic_string, Error, Result};
use crate::module::{Format, Module};

pub fn load_bytes(name: &str, data: &[u8]) -> Result<Module> {
    if data.len() >= 4 && &data[0..4] == b"NSO0" {
        let mut module = nso::parse(name, data)?;
        analysis::finalize(&mut module)?;
        return Ok(module);
    }
    if data.len() >= 4 && &data[0..4] == b"NRO0" {
        let mut module = nro::parse(name, data, 0)?;
        analysis::finalize(&mut module)?;
        return Ok(module);
    }
    if data.len() >= 0x14 && &data[0x10..0x14] == b"NRO0" {
        let mut module = nro::parse(name, data, 0x10)?;
        analysis::finalize(&mut module)?;
        return Ok(module);
    }
    if data.len() >= 4 && &data[0..4] == b"\x7fELF" {
        let mut module = elf::parse(name, data)?;
        analysis::finalize(&mut module)?;
        return Ok(module);
    }
    refuse_container(data)?;
    let found = data.get(0..4).unwrap_or(b"????");
    Err(Error::BadMagic {
        what: "executable",
        expected: "NSO0, NRO0, or ELF",
        found: magic_string(found),
    })
}

fn refuse_container(data: &[u8]) -> Result<()> {
    let checks: &[(usize, &[u8])] = &[
        (0, b"PFS0"),
        (0, b"HFS0"),
        (0, b"NCA0"),
        (0, b"NCA2"),
        (0, b"NCA3"),
        (0x100, b"HEAD"),
        (0x200, b"NCA0"),
        (0x200, b"NCA2"),
        (0x200, b"NCA3"),
    ];
    for (off, magic) in checks {
        if data.len() >= off + magic.len() && &data[*off..*off + magic.len()] == *magic {
            return Err(Error::Container {
                magic: String::from_utf8_lossy(magic).into_owned(),
            });
        }
    }
    Ok(())
}

pub(crate) fn read_u32(data: &[u8], off: usize) -> Result<u32> {
    let bytes = data
        .get(off..off + 4)
        .ok_or_else(|| Error::message(format!("truncated read of u32 at {off:#x}")))?;
    Ok(u32::from_le_bytes(bytes.try_into().unwrap()))
}

pub(crate) fn read_u16(data: &[u8], off: usize) -> Result<u16> {
    let bytes = data
        .get(off..off + 2)
        .ok_or_else(|| Error::message(format!("truncated read of u16 at {off:#x}")))?;
    Ok(u16::from_le_bytes(bytes.try_into().unwrap()))
}

pub(crate) fn read_u64(data: &[u8], off: usize) -> Result<u64> {
    let bytes = data
        .get(off..off + 8)
        .ok_or_else(|| Error::message(format!("truncated read of u64 at {off:#x}")))?;
    Ok(u64::from_le_bytes(bytes.try_into().unwrap()))
}

pub(crate) fn sha256(data: &[u8]) -> [u8; 32] {
    use sha2::{Digest, Sha256};
    Sha256::digest(data).into()
}

pub(crate) fn check_hash(kind: &str, data: &[u8], expected: &[u8], required: bool) -> Result<bool> {
    if !required && expected.iter().all(|b| *b == 0) {
        return Ok(false);
    }
    let actual = sha256(data);
    if actual.as_slice() != expected {
        return Err(Error::message(format!(
            "{kind} SHA-256 mismatch (header {:02x}{:02x}{:02x}{:02x}..., actual {:02x}{:02x}{:02x}{:02x}...)",
            expected.first().copied().unwrap_or(0),
            expected.get(1).copied().unwrap_or(0),
            expected.get(2).copied().unwrap_or(0),
            expected.get(3).copied().unwrap_or(0),
            actual[0],
            actual[1],
            actual[2],
            actual[3]
        )));
    }
    Ok(true)
}

pub(crate) fn empty_module(format: Format, name: &str) -> Module {
    Module {
        format,
        name: name.to_string(),
        module_id: [0; 32],
        image_base: 0,
        image: Vec::new(),
        text: crate::module::Segment { va: 0, size: 0 },
        rodata: crate::module::Segment { va: 0, size: 0 },
        data: crate::module::Segment { va: 0, size: 0 },
        bss: crate::module::Segment { va: 0, size: 0 },
        entry: 0,
        symbols: Vec::new(),
        needed: Vec::new(),
        imports: Vec::new(),
        relocations: Vec::new(),
        init: Vec::new(),
        notes: Vec::new(),
        hint: SymbolHint::default(),
    }
}

/// Ro-data-relative symbol table hint carried from an NSO/NRO header.
#[derive(Debug, Clone, Copy, Default)]
pub struct SymbolHint {
    pub dynstr_va: u64,
    pub dynstr_size: u64,
    pub dynsym_va: u64,
    pub dynsym_size: u64,
    pub dynamic_va: Option<u64>,
}
