use capstone::arch::BuildsCapstone;

use crate::error::{Error, Result};
use crate::module::Module;

/// Capstone listing for `inspect`. This does not execute guest code.
pub fn disassemble(module: &Module, start: u64, limit: usize) -> Result<Vec<String>> {
    let Some(off) = module.offset_of(start) else {
        return Err(Error::message(format!(
            "address {start:#x} is outside the mapped image"
        )));
    };
    let bytes = &module.image[off..];
    let take = bytes.len().min(limit.saturating_mul(4));
    let cs = capstone::Capstone::new()
        .arm64()
        .mode(capstone::arch::arm64::ArchMode::Arm)
        .build()
        .map_err(|err| Error::message(format!("capstone: {err}")))?;
    let insns = cs
        .disasm_all(&bytes[..take], start)
        .map_err(|err| Error::message(format!("capstone: {err}")))?;
    Ok(insns
        .iter()
        .take(limit)
        .map(|insn| {
            format!(
                "{:#x}: {:<8} {}",
                insn.address(),
                insn.mnemonic().unwrap_or("?"),
                insn.op_str().unwrap_or("")
            )
        })
        .collect())
}
