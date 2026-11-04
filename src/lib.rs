//! Bifrost lifts an already-decrypted Nintendo Switch executable into a form
//! that can run as a native Windows program. It does not decrypt, unpack, or
//! request keys for NSP, XCI, or NCA containers.

mod analysis;
pub mod android;
pub mod codegen;
mod disasm;
mod emit;
mod error;
mod format;
mod hos;
pub mod intake;
mod interp;
pub mod lift;
pub mod link;
mod module;
pub mod modernize;
pub mod pipeline;
pub mod project;
pub mod romfs;

pub use disasm::disassemble;
pub use emit::{emit, report_text, EmitReport};
pub use error::{Error, Result};
pub use format::load_bytes;
pub use interp::{execute, ExecResult, Stop};
pub use lift::{decode, lift, Lifted, Op};
pub use module::{Format, Module};

use std::fs;
use std::path::Path;

pub fn load_path(path: &Path) -> Result<Module> {
    let data = fs::read(path)?;
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("module");
    load_bytes(name, &data)
}

#[cfg(test)]
mod tests;
