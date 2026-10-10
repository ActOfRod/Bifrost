use std::fmt;

/// Recoverable loader, lifter, and emitter failures.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Message(String),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error(
        "refusing '{magic}' container: Bifrost does not decrypt or unpack NSP, XCI, or NCA files. Pass an already-decrypted NSO, NRO, or AArch64 ELF"
    )]
    Container { magic: String },
    #[error("bad {what}: expected {expected}, found {found}")]
    BadMagic {
        what: &'static str,
        expected: &'static str,
        found: String,
    },
}

impl Error {
    pub fn message(text: impl Into<String>) -> Self {
        Self::Message(text.into())
    }
}

pub type Result<T> = std::result::Result<T, Error>;

pub fn magic_string(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|b| {
            if b.is_ascii_graphic() {
                *b as char
            } else {
                '.'
            }
        })
        .collect::<String>()
        + &format!(" ({})", hex(bytes))
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

impl fmt::Display for crate::module::Format {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            crate::module::Format::Nso => write!(f, "NSO"),
            crate::module::Format::Nro => write!(f, "NRO"),
            crate::module::Format::Elf => write!(f, "ELF"),
        }
    }
}
