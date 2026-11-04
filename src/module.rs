//! A mapped Switch executable. Addresses are guest virtual addresses.
//! `image[0]` corresponds to `image_base`.

pub const IMPORT_SENTINEL_BASE: u64 = 0xFFFF_FF00_0000_0000;
pub const MAX_SEGMENT: u64 = 256 * 1024 * 1024;
pub const MAX_IMAGE: u64 = 1024 * 1024 * 1024;
pub const STACK_VA: u64 = 0x0000_0008_0000_0000;
pub const STACK_SIZE: usize = 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Format {
    Nso,
    Nro,
    Elf,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SymbolKind {
    Func,
    Object,
    Other,
}

#[derive(Debug, Clone)]
pub struct Segment {
    pub va: u64,
    pub size: u64,
}

impl Segment {
    pub fn contains(&self, va: u64) -> bool {
        va >= self.va && va < self.va.saturating_add(self.size)
    }

    pub fn end(&self) -> u64 {
        self.va.saturating_add(self.size)
    }
}

#[derive(Debug, Clone)]
pub struct Symbol {
    pub name: String,
    pub va: u64,
    pub size: u64,
    pub defined: bool,
    pub kind: SymbolKind,
}

#[derive(Debug, Clone)]
pub struct Import {
    pub name: String,
    pub slot_va: Option<u64>,
    pub sentinel: u64,
}

#[derive(Debug, Clone)]
pub struct Reloc {
    pub offset: u64,
    pub kind: u32,
    pub symbol: String,
    pub addend: i64,
    pub applied: bool,
}

#[derive(Debug, Clone)]
pub struct Module {
    pub format: Format,
    pub name: String,
    pub module_id: [u8; 32],
    pub image_base: u64,
    pub image: Vec<u8>,
    pub text: Segment,
    pub rodata: Segment,
    pub data: Segment,
    pub bss: Segment,
    pub entry: u64,
    pub symbols: Vec<Symbol>,
    pub needed: Vec<String>,
    pub imports: Vec<Import>,
    pub relocations: Vec<Reloc>,
    pub init: Vec<u64>,
    pub notes: Vec<String>,
    pub(crate) hint: crate::format::SymbolHint,
}

impl Module {
    pub fn offset_of(&self, va: u64) -> Option<usize> {
        let off = va.checked_sub(self.image_base)?;
        if off < self.image.len() as u64 {
            Some(off as usize)
        } else {
            None
        }
    }

    pub fn read(&self, va: u64, len: usize) -> Option<&[u8]> {
        let off = self.offset_of(va)?;
        self.image.get(off..off.checked_add(len)?)
    }

    pub fn read_u32(&self, va: u64) -> Option<u32> {
        Some(u32::from_le_bytes(self.read(va, 4)?.try_into().ok()?))
    }

    pub fn read_u64(&self, va: u64) -> Option<u64> {
        Some(u64::from_le_bytes(self.read(va, 8)?.try_into().ok()?))
    }

    pub fn write_bytes(&mut self, va: u64, bytes: &[u8]) -> bool {
        let Some(off) = self.offset_of(va) else {
            return false;
        };
        let Some(dst) = self.image.get_mut(off..off + bytes.len()) else {
            return false;
        };
        dst.copy_from_slice(bytes);
        true
    }

    pub fn in_text(&self, va: u64) -> bool {
        self.text.contains(va)
    }

    pub fn import_by_sentinel(&self, sentinel: u64) -> Option<&Import> {
        self.imports.iter().find(|imp| imp.sentinel == sentinel)
    }

    pub fn symbol_by_name(&self, name: &str) -> Option<&Symbol> {
        self.symbols.iter().find(|sym| sym.name == name)
    }

    pub fn exports(&self) -> impl Iterator<Item = &Symbol> {
        self.symbols
            .iter()
            .filter(|sym| sym.defined && !sym.name.is_empty())
    }

    pub fn undefined(&self) -> impl Iterator<Item = &Symbol> {
        self.symbols
            .iter()
            .filter(|sym| !sym.defined && !sym.name.is_empty())
    }
}

/// Guest memory: the mapped module plus a separate stack.
pub struct AddressSpace<'a> {
    pub module: &'a mut Module,
    pub stack: Vec<u8>,
}

impl<'a> AddressSpace<'a> {
    pub fn new(module: &'a mut Module) -> Self {
        Self {
            module,
            stack: vec![0; STACK_SIZE],
        }
    }

    fn stack_offset(&self, va: u64, len: usize) -> Option<usize> {
        if va < STACK_VA {
            return None;
        }
        let off = (va - STACK_VA) as usize;
        if off.checked_add(len)? <= self.stack.len() {
            Some(off)
        } else {
            None
        }
    }

    pub fn load(&self, va: u64, len: usize) -> Option<u64> {
        let bytes = if let Some(slice) = self.module.read(va, len) {
            slice.to_vec()
        } else {
            let off = self.stack_offset(va, len)?;
            self.stack[off..off + len].to_vec()
        };
        let mut tmp = [0u8; 8];
        tmp[..len].copy_from_slice(&bytes);
        Some(u64::from_le_bytes(tmp))
    }

    pub fn store(&mut self, va: u64, len: usize, value: u64) -> bool {
        let bytes = &value.to_le_bytes()[..len];
        if self.module.write_bytes(va, bytes) {
            return true;
        }
        let Some(off) = self.stack_offset(va, len) else {
            return false;
        };
        self.stack[off..off + len].copy_from_slice(bytes);
        true
    }
}
