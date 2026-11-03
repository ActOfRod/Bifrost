use super::{check_hash, empty_module, read_u32, SymbolHint};
use crate::error::{Error, Result};
use crate::module::{Format, Module, Segment, MAX_IMAGE, MAX_SEGMENT};

const FLAG_TEXT_COMPRESS: u32 = 1 << 0;
const FLAG_RO_COMPRESS: u32 = 1 << 1;
const FLAG_DATA_COMPRESS: u32 = 1 << 2;
const FLAG_TEXT_HASH: u32 = 1 << 3;
const FLAG_RO_HASH: u32 = 1 << 4;
const FLAG_DATA_HASH: u32 = 1 << 5;
const FLAG_ZSTD: u32 = 1 << 7;

pub fn parse(name: &str, data: &[u8]) -> Result<Module> {
    if data.len() < 0x100 {
        return Err(Error::message("NSO header is truncated"));
    }
    let version = read_u32(data, 0x4)?;
    let flags = read_u32(data, 0xC)?;
    if flags & FLAG_ZSTD != 0 {
        return Err(Error::message(
            "this NSO uses zstd segment compression (flag bit 7). Bifrost accepts uncompressed and LZ4-compressed segments",
        ));
    }

    let text_file = read_u32(data, 0x10)?;
    let text_mem = read_u32(data, 0x14)?;
    let text_size = read_u32(data, 0x18)?;
    let module_name_off = read_u32(data, 0x1C)?;
    let ro_file = read_u32(data, 0x20)?;
    let ro_mem = read_u32(data, 0x24)?;
    let ro_size = read_u32(data, 0x28)?;
    let module_name_size = read_u32(data, 0x2C)?;
    let data_file = read_u32(data, 0x30)?;
    let data_mem = read_u32(data, 0x34)?;
    let data_size = read_u32(data, 0x38)?;
    let bss_size = read_u32(data, 0x3C)?;
    let mut module_id = [0u8; 32];
    module_id.copy_from_slice(&data[0x40..0x60]);
    let text_file_size = read_u32(data, 0x60)?;
    let ro_file_size = read_u32(data, 0x64)?;
    let data_file_size = read_u32(data, 0x68)?;
    let dynstr_off = read_u32(data, 0x90)?;
    let dynstr_size = read_u32(data, 0x94)?;
    let dynsym_off = read_u32(data, 0x98)?;
    let dynsym_size = read_u32(data, 0x9C)?;

    let text = inflate(
        data,
        text_file,
        text_file_size,
        text_size,
        flags & FLAG_TEXT_COMPRESS != 0,
        ".text",
    )?;
    let ro = inflate(
        data,
        ro_file,
        ro_file_size,
        ro_size,
        flags & FLAG_RO_COMPRESS != 0,
        ".rodata",
    )?;
    let data_seg = inflate(
        data,
        data_file,
        data_file_size,
        data_size,
        flags & FLAG_DATA_COMPRESS != 0,
        ".data",
    )?;

    check_hash(
        ".text",
        &text,
        &data[0xA0..0xC0],
        flags & FLAG_TEXT_HASH != 0,
    )?;
    check_hash(".rodata", &ro, &data[0xC0..0xE0], flags & FLAG_RO_HASH != 0)?;
    check_hash(
        ".data",
        &data_seg,
        &data[0xE0..0x100],
        flags & FLAG_DATA_HASH != 0,
    )?;

    let mut module = empty_module(Format::Nso, name);
    module.module_id = module_id;
    module.image_base = 0;
    module
        .notes
        .push(format!("NSO version {version}, flags {flags:#x}"));
    if flags & (FLAG_TEXT_COMPRESS | FLAG_RO_COMPRESS | FLAG_DATA_COMPRESS) != 0 {
        module
            .notes
            .push("one or more segments were LZ4-compressed".into());
    }

    let bss_va = data_mem as u64 + data_seg.len() as u64;
    let image_end = [
        text_mem as u64 + text.len() as u64,
        ro_mem as u64 + ro.len() as u64,
        bss_va + bss_size as u64,
    ]
    .into_iter()
    .max()
    .unwrap_or(0);
    if image_end > MAX_IMAGE {
        return Err(Error::message(format!(
            "NSO image is {image_end:#x} bytes, over the {MAX_IMAGE:#x} limit"
        )));
    }
    let mut image = vec![0u8; image_end as usize];
    place(&mut image, text_mem, &text)?;
    place(&mut image, ro_mem, &ro)?;
    place(&mut image, data_mem, &data_seg)?;
    module.image = image;
    module.text = Segment {
        va: text_mem as u64,
        size: text.len() as u64,
    };
    module.rodata = Segment {
        va: ro_mem as u64,
        size: ro.len() as u64,
    };
    module.data = Segment {
        va: data_mem as u64,
        size: data_seg.len() as u64,
    };
    module.bss = Segment {
        va: bss_va,
        size: bss_size as u64,
    };
    module.entry = module.text.va;

    if module_name_size > 0 && module_name_size < 512 {
        let start = ro_mem as u64 + module_name_off as u64;
        if let Some(bytes) = module.read(start, module_name_size as usize) {
            let end = bytes.iter().position(|b| *b == 0).unwrap_or(bytes.len());
            if bytes[..end]
                .iter()
                .all(|b| b.is_ascii_graphic() || *b == b' ')
            {
                let label = String::from_utf8_lossy(&bytes[..end]).trim().to_string();
                if !label.is_empty() {
                    module.name = label;
                }
            }
        }
    }

    module.notes.push(format!(
        "symbol hint dynstr={dynstr_off:#x}+{dynstr_size:#x} dynsym={dynsym_off:#x}+{dynsym_size:#x} (rodata-relative)"
    ));
    // Stash the hint in the module name notes; analysis reads it from a side channel
    // stored in the first note slot via the symbol VAs below. We keep the hint on the
    // module by encoding it into init temporarily? No — pass through a thread-local is
    // worse. analysis::finalize looks at MOD0 first and falls back to rodata-relative
    // header fields, which we store by writing them into notes as structured text.
    // Cleaner: attach the hint by placing sentinel relocs?
    // The parser returns the module and finalize is called by load_bytes, which does
    // not see the hint. Store the hint in unused relocation-sized notes instead.
    let _hint = SymbolHint {
        dynstr_va: module.rodata.va + dynstr_off as u64,
        dynstr_size: dynstr_size as u64,
        dynsym_va: module.rodata.va + dynsym_off as u64,
        dynsym_size: dynsym_size as u64,
        dynamic_va: None,
    };
    module.hint = _hint;
    Ok(module)
}

fn inflate(
    file: &[u8],
    file_off: u32,
    file_size: u32,
    mem_size: u32,
    compressed: bool,
    name: &str,
) -> Result<Vec<u8>> {
    if mem_size as u64 > MAX_SEGMENT {
        return Err(Error::message(format!(
            "{name} is larger than {MAX_SEGMENT:#x}"
        )));
    }
    let take = if compressed { file_size } else { mem_size };
    let start = file_off as usize;
    let end = start
        .checked_add(take as usize)
        .ok_or_else(|| Error::message(format!("{name} file range overflows")))?;
    let bytes = file.get(start..end).ok_or_else(|| {
        Error::message(format!(
            "{name} file range {start:#x}..{end:#x} exceeds the file"
        ))
    })?;
    if !compressed {
        return Ok(bytes.to_vec());
    }
    lz4_flex::block::decompress(bytes, mem_size as usize)
        .map_err(|err| Error::message(format!("{name} LZ4 decompress failed: {err}")))
}

fn place(image: &mut [u8], mem_off: u32, bytes: &[u8]) -> Result<()> {
    let start = mem_off as usize;
    let end = start
        .checked_add(bytes.len())
        .ok_or_else(|| Error::message("segment placement overflows"))?;
    let dst = image
        .get_mut(start..end)
        .ok_or_else(|| Error::message(format!("segment at {start:#x} does not fit the image")))?;
    if dst.iter().any(|b| *b != 0) {
        return Err(Error::message(format!(
            "segment at {start:#x} overlaps another segment"
        )));
    }
    dst.copy_from_slice(bytes);
    Ok(())
}
