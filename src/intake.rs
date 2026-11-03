//! Content intake: NSP (PFS0), XCI (HFS0), plaintext NCA, ExeFS directories/PFS0 images and loose
//! executables, layered base -> updates, plus IPS/IPS32/pchtxt patches.
//!
//! Bifrost never handles keys: NCAs must already be decrypted by the user's own tools
//! (for example `hactool --plaintext`). Encrypted NCAs are rejected with an explanation.

use std::collections::BTreeMap;
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

use anyhow::{anyhow, bail, Context, Result};

use crate::module::Module;

#[derive(Debug, Clone)]
pub struct RomfsRef {
    pub path: PathBuf,
    pub offset: u64,
    pub size: u64,
}

#[derive(Debug, Default)]
pub struct Content {
    pub exefs: BTreeMap<String, Vec<u8>>,
    pub romfs: Option<RomfsRef>,
    /// Extracted RomFS directory; packed into an image at build time.
    pub romfs_dir: Option<PathBuf>,
    /// Also take RomFS from Data/PublicData NCAs (add-on content).
    pub want_data: bool,
    pub title: Option<String>,
    pub title_id: u64,
    pub notes: Vec<String>,
}

fn read_at(f: &mut File, off: u64, len: usize) -> io::Result<Vec<u8>> {
    f.seek(SeekFrom::Start(off))?;
    let mut buf = vec![0; len];
    f.read_exact(&mut buf)?;
    Ok(buf)
}
fn u32le(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes(b[o..o + 4].try_into().unwrap())
}
fn u64le(b: &[u8], o: usize) -> u64 {
    u64::from_le_bytes(b[o..o + 8].try_into().unwrap())
}

#[derive(Debug, Clone)]
pub struct PartEntry {
    pub name: String,
    pub offset: u64,
    pub size: u64,
}

/// Parse a PFS0 or HFS0 partition header located at `base`; entry offsets are absolute.
pub fn read_partition(f: &mut File, base: u64) -> Result<Vec<PartEntry>> {
    let head = read_at(f, base, 16)?;
    let (entry_size, magic) = match &head[0..4] {
        b"PFS0" => (24usize, "PFS0"),
        b"HFS0" => (0x40usize, "HFS0"),
        _ => bail!("expected PFS0/HFS0 at {base:#x}"),
    };
    let n = u32le(&head, 4) as usize;
    let strsz = u32le(&head, 8) as usize;
    if n > 100_000 || strsz > 16 << 20 {
        bail!("{magic} header at {base:#x} is implausible");
    }
    let table = read_at(f, base + 16, n * entry_size + strsz)?;
    let strings = &table[n * entry_size..];
    let data_start = base + 16 + (n * entry_size + strsz) as u64;
    let mut out = Vec::new();
    for i in 0..n {
        let e = &table[i * entry_size..];
        let name_off = u32le(e, 16) as usize;
        let end = strings[name_off.min(strings.len())..].iter().position(|&c| c == 0).unwrap_or(0);
        let name = String::from_utf8_lossy(&strings[name_off.min(strings.len())..name_off.min(strings.len()) + end]).into_owned();
        out.push(PartEntry { name, offset: data_start + u64le(e, 0), size: u64le(e, 8) });
    }
    Ok(out)
}

pub fn parse_pfs0_bytes(data: &[u8]) -> Result<Vec<(String, Vec<u8>)>> {
    if data.len() < 16 || &data[0..4] != b"PFS0" {
        bail!("not a PFS0 image");
    }
    let n = u32le(data, 4) as usize;
    let strsz = u32le(data, 8) as usize;
    let table_end = 16 + n * 24 + strsz;
    if table_end > data.len() {
        bail!("truncated PFS0");
    }
    let strings = &data[16 + n * 24..table_end];
    let mut out = Vec::new();
    for i in 0..n {
        let e = &data[16 + i * 24..];
        let (off, size, name_off) = (u64le(e, 0) as usize, u64le(e, 8) as usize, u32le(e, 16) as usize);
        let s = &strings[name_off.min(strings.len())..];
        let name = String::from_utf8_lossy(&s[..s.iter().position(|&c| c == 0).unwrap_or(s.len())]).into_owned();
        let start = table_end + off;
        let bytes = data.get(start..start + size).ok_or_else(|| anyhow!("PFS0 entry {name} out of range"))?;
        out.push((name, bytes.to_vec()));
    }
    Ok(out)
}

const MEDIA: u64 = 0x200;

/// Read a plaintext NCA at `base`; adds its ExeFS/RomFS to `content` when it is a Program NCA.
fn read_nca(f: &mut File, path: &Path, base: u64, name: &str, content: &mut Content) -> Result<()> {
    let hdr = read_at(f, base, 0xC00)?;
    let magic = &hdr[0x200..0x204];
    if !matches!(magic, b"NCA3" | b"NCA2") {
        bail!(
            "{name}: NCA header is encrypted (no NCA3 magic). Bifrost does not handle console keys; decrypt the \
             content with your own tools first (e.g. `hactool --plaintext=out.nca {name}`) and pass the plaintext NCA"
        );
    }
    let content_type = hdr[0x205];
    if content_type != 0 && !(content.want_data && matches!(content_type, 4 | 5)) {
        return Ok(());
    }
    let program_id = u64le(&hdr, 0x210);
    if content.title_id == 0 {
        content.title_id = program_id;
    }
    let mut exefs_found = false;
    for i in 0..4 {
        let entry = &hdr[0x240 + i * 16..];
        let (start, end) = (u32le(entry, 0) as u64 * MEDIA, u32le(entry, 4) as u64 * MEDIA);
        if end <= start {
            continue;
        }
        let fs = &hdr[0x400 + i * 0x200..0x600 + i * 0x200];
        let fs_type = fs[2];
        let sec = base + start;
        match fs_type {
            1 => {
                let pfs_off = u64le(fs, 0x8 + 0x38);
                let pfs_size = u64le(fs, 0x8 + 0x40);
                let head = read_at(f, sec + pfs_off, 4)?;
                if &head != b"PFS0" {
                    bail!("{name}: section {i} is not plaintext (expected PFS0); decrypt it with your own tools first");
                }
                let files = read_partition(f, sec + pfs_off)?;
                if files.iter().any(|e| e.name == "main.npdm" || e.name == "main") {
                    let _ = pfs_size;
                    content.exefs.clear();
                    for e in files {
                        let bytes = read_at(f, e.offset, e.size as usize)?;
                        content.exefs.insert(e.name, bytes);
                    }
                    exefs_found = true;
                }
            }
            0 => {
                let ivfc = &fs[0x8..];
                if &ivfc[0..4] == b"IVFC" {
                    // num_levels counts the master hash (7 for a RomFS: data is descriptor 5); accept a
                    // count that excludes it as well, and pick the descriptor whose data is a RomFS header.
                    let levels = u32le(ivfc, 0xC) as usize;
                    let sec_size = end - start;
                    let mut found = None;
                    for idx in [levels.saturating_sub(2), levels.saturating_sub(1)] {
                        if idx == 0 || idx > 5 {
                            continue;
                        }
                        let d = 0x10 + idx * 0x18;
                        let (off, size) = (u64le(ivfc, d), u64le(ivfc, d + 8));
                        if size == 0 || off.checked_add(size).is_none_or(|e| e > sec_size) {
                            continue;
                        }
                        if u64le(&read_at(f, sec + off, 8)?, 0) == 0x50 {
                            found = Some((off, size));
                            break;
                        }
                    }
                    let Some((off, size)) = found else {
                        bail!("{name}: RomFS section {i} is not plaintext; decrypt it with your own tools first");
                    };
                    content.romfs = Some(RomfsRef { path: path.to_path_buf(), offset: sec + off, size });
                } else if fs[4] == 4 || &fs[0x100 + 0x10..0x100 + 0x14] == b"BKTR" {
                    content.notes.push(format!(
                        "{name}: update RomFS is a BKTR patch; merge it with the base RomFS using your own tools and pass --romfs"
                    ));
                }
            }
            _ => {}
        }
    }
    if exefs_found {
        content.notes.push(format!("{name}: ExeFS with {} files", content.exefs.len()));
    }
    Ok(())
}

fn read_container(path: &Path, content: &mut Content) -> Result<()> {
    let mut f = File::open(path).with_context(|| format!("open {}", path.display()))?;
    let file_len = f.metadata()?.len() as usize;
    let head = read_at(&mut f, 0, 0x400.min(file_len))?;
    let file_name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    if head.len() >= 4 && &head[0..4] == b"PFS0" {
        let entries = read_partition(&mut f, 0)?;
        if entries.iter().any(|e| e.name == "main" || e.name == "main.npdm") {
            for e in entries {
                content.exefs.insert(e.name.clone(), read_at(&mut f, e.offset, e.size as usize)?);
            }
            return Ok(());
        }
        let mut ncas: Vec<_> = entries.into_iter().filter(|e| e.name.ends_with(".nca") || e.name.ends_with(".ncz")).collect();
        if ncas.iter().any(|e| e.name.ends_with(".ncz")) {
            bail!("{file_name}: NCZ-compressed content is not supported; decompress to NSP first");
        }
        ncas.sort_by_key(|e| std::cmp::Reverse(e.size));
        for e in ncas {
            read_nca(&mut f, path, e.offset, &e.name, content)?;
        }
        return Ok(());
    }
    if head.len() >= 0x140 && &head[0x100..0x104] == b"HEAD" {
        let root = u64le(&head, 0x130);
        for part in read_partition(&mut f, root)? {
            if part.name != "secure" {
                continue;
            }
            for e in read_partition(&mut f, part.offset)? {
                if e.name.ends_with(".nca") {
                    read_nca(&mut f, path, e.offset, &e.name, content)?;
                }
            }
        }
        return Ok(());
    }
    if head.len() >= 0x204 && (&head[0x200..0x204] == b"NCA3" || &head[0x200..0x204] == b"NCA2") {
        return read_nca(&mut f, path, 0, &file_name, content);
    }
    if file_name.to_ascii_lowercase().ends_with(".nca") {
        return read_nca(&mut f, path, 0, &file_name, content);
    }
    if head.len() >= 8 && u64le(&head, 0) == 0x50 && file_name.to_ascii_lowercase().contains("romfs") {
        content.romfs = Some(RomfsRef { path: path.to_path_buf(), offset: 0, size: f.metadata()?.len() });
        return Ok(());
    }
    // loose executable (NSO/NRO/ELF)
    let mut data = Vec::new();
    f.seek(SeekFrom::Start(0))?;
    f.read_to_end(&mut data)?;
    content.exefs.insert(file_name, data);
    Ok(())
}

fn read_dir(path: &Path, content: &mut Content) -> Result<()> {
    let exefs = if path.join("exefs").is_dir() { path.join("exefs") } else { path.to_path_buf() };
    let mut found = BTreeMap::new();
    for entry in std::fs::read_dir(&exefs)? {
        let p = entry?.path();
        if !p.is_file() {
            continue;
        }
        let name = p.file_name().unwrap().to_string_lossy().into_owned();
        let stem = name.split('.').next().unwrap_or("").to_ascii_lowercase();
        if matches!(stem.as_str(), "main" | "rtld" | "sdk") || stem.starts_with("subsdk") || name.ends_with(".nso") || name.ends_with(".nro") {
            found.insert(name, std::fs::read(&p)?);
        }
    }
    if found.is_empty() && path.file_name().is_some_and(|n| n.eq_ignore_ascii_case("romfs")) {
        content.romfs_dir = Some(path.to_path_buf());
        return Ok(());
    }
    if found.is_empty() {
        bail!("{}: no ExeFS files (main, rtld, subsdk*, sdk, main.npdm) found", path.display());
    }
    content.exefs = found;
    for cand in ["romfs.bin", "romfs.romfs", "romfs.img"] {
        let p = path.join(cand);
        if p.is_file() {
            content.romfs = Some(RomfsRef { size: p.metadata()?.len(), path: p, offset: 0 });
        }
    }
    if path.join("romfs").is_dir() && content.romfs.is_none() {
        content.romfs_dir = Some(path.join("romfs"));
    }
    Ok(())
}

/// Layer base content, then each update in order (an update's ExeFS replaces the previous one).
pub fn gather(base: &[PathBuf], updates: &[PathBuf]) -> Result<Content> {
    let mut content = Content::default();
    for (i, path) in base.iter().chain(updates).enumerate() {
        let mut layer = Content::default();
        if path.is_dir() {
            read_dir(path, &mut layer)?;
        } else {
            read_container(path, &mut layer)?;
        }
        let is_update = i >= base.len();
        if !layer.exefs.is_empty() {
            if is_update || layer.exefs.contains_key("main.npdm") {
                content.exefs = layer.exefs;
            } else {
                content.exefs.extend(layer.exefs);
            }
        }
        if layer.romfs.is_some() {
            content.romfs = layer.romfs;
            content.romfs_dir = None;
        } else if layer.romfs_dir.is_some() {
            content.romfs_dir = layer.romfs_dir;
            content.romfs = None;
        }
        if layer.title_id != 0 {
            content.title_id = layer.title_id & !0x800;
        }
        content.notes.extend(layer.notes);
    }
    if let Some(npdm) = content.exefs.get("main.npdm") {
        if npdm.len() >= 0x80 && &npdm[0..4] == b"META" {
            let name = &npdm[0x20..0x30];
            let end = name.iter().position(|&c| c == 0).unwrap_or(name.len());
            let s = String::from_utf8_lossy(&name[..end]).trim().to_string();
            if !s.is_empty() {
                content.title = Some(s);
            }
            let aci = u32le(npdm, 0x70) as usize;
            if npdm.len() >= aci + 0x18 && &npdm[aci..aci + 4] == b"ACI0" && content.title_id == 0 {
                content.title_id = u64le(npdm, aci + 0x10);
            }
        }
    }
    Ok(content)
}

pub fn load_modules(content: &Content) -> Result<(Vec<Module>, Vec<String>)> {
    let mut modules = Vec::new();
    let mut notes = Vec::new();
    for (name, bytes) in &content.exefs {
        if name.ends_with(".npdm") {
            continue;
        }
        match crate::load_bytes(name, bytes) {
            Ok(m) => modules.push(m),
            Err(e) => notes.push(format!("skipped {name}: {e}")),
        }
    }
    if modules.is_empty() {
        bail!("no loadable executables in the input ({})", notes.join("; "));
    }
    Ok((modules, notes))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02X}")).collect()
}

fn module_for<'a>(modules: &'a mut [Module], build_id: Option<&str>) -> Option<&'a mut Module> {
    match build_id {
        Some(id) if !id.is_empty() => {
            let id = id.to_ascii_uppercase();
            modules.iter_mut().find(|m| {
                let mid = hex(&m.module_id);
                let significant = mid.trim_end_matches('0');
                !significant.is_empty() && (mid.starts_with(&id) || (significant.len() >= 8 && id.starts_with(significant)))
            })
        }
        _ => modules.iter_mut().find(|m| crate::link::module_rank(&m.name).0 == 1),
    }
}

fn write_image(m: &mut Module, off: u64, bytes: &[u8]) -> bool {
    m.write_bytes(m.image_base + off, bytes)
}

/// Apply an IPS/IPS32 (offsets include the 0x100 NSO header) or pchtxt patch file.
pub fn apply_patch(modules: &mut [Module], path: &Path) -> Result<String> {
    let data = std::fs::read(path)?;
    let stem = path.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let is_hex_id = stem.len() >= 16 && stem.chars().all(|c| c.is_ascii_hexdigit());
    if data.starts_with(b"PATCH") || data.starts_with(b"IPS32") {
        let wide = data.starts_with(b"IPS32");
        let m = module_for(modules, is_hex_id.then_some(stem.as_str())).ok_or_else(|| anyhow!("{}: no module with build id {stem}", path.display()))?;
        let name = m.name.clone();
        let (mut p, osz) = (5usize, if wide { 4 } else { 3 });
        let mut n = 0;
        loop {
            let tail = &data[p.min(data.len())..];
            if (wide && tail.starts_with(b"EEOF")) || (!wide && tail.starts_with(b"EOF") && tail.len() == 3) || tail.len() < osz + 2 {
                break;
            }
            let off = tail[..osz].iter().fold(0u64, |a, &b| a << 8 | b as u64);
            let size = u16::from_be_bytes([tail[osz], tail[osz + 1]]) as usize;
            p += osz + 2;
            let bytes = if size == 0 {
                let cnt = u16::from_be_bytes([data[p], data[p + 1]]) as usize;
                let v = data[p + 2];
                p += 3;
                vec![v; cnt]
            } else {
                let b = data.get(p..p + size).ok_or_else(|| anyhow!("{}: truncated IPS record", path.display()))?.to_vec();
                p += size;
                b
            };
            if off >= 0x100 && write_image(m, off - 0x100, &bytes) {
                n += 1;
            }
        }
        return Ok(format!("{}: {n} IPS records applied to {name}", path.display()));
    }
    let text = String::from_utf8_lossy(&data);
    if text.contains("@nsobid") || text.contains("@enabled") || text.contains("@flag") {
        let (mut build_id, mut shift, mut enabled, mut n) = (None::<String>, 0i64, false, 0);
        let mut target_name = String::new();
        for line in text.lines() {
            let line = line.split("//").next().unwrap_or("").trim();
            if let Some(id) = line.strip_prefix("@nsobid-") {
                build_id = Some(id.trim().to_string());
            } else if let Some(rest) = line.strip_prefix("@flag offset_shift") {
                let v = rest.trim().trim_start_matches("0x");
                shift = i64::from_str_radix(v, 16).unwrap_or(0);
            } else if line.starts_with("@enabled") {
                enabled = true;
            } else if line.starts_with("@disabled") || line.starts_with("@stop") {
                enabled = false;
            } else if enabled && !line.is_empty() && !line.starts_with('@') {
                let mut parts = line.split_whitespace();
                let (Some(o), Some(v)) = (parts.next(), parts.next()) else { continue };
                let Ok(off) = i64::from_str_radix(o.trim_start_matches("0x"), 16) else { continue };
                let v = v.trim_matches('"');
                let bytes: Option<Vec<u8>> = (0..v.len() / 2).map(|i| u8::from_str_radix(&v[i * 2..i * 2 + 2], 16).ok()).collect();
                let Some(bytes) = bytes else { continue };
                let m = module_for(modules, build_id.as_deref()).ok_or_else(|| anyhow!("{}: no module matches the patch build id", path.display()))?;
                target_name = m.name.clone();
                let image_off = off + shift - 0x100;
                if image_off >= 0 && write_image(m, image_off as u64, &bytes) {
                    n += 1;
                }
            }
        }
        return Ok(format!("{}: {n} pchtxt lines applied to {target_name}", path.display()));
    }
    bail!("{}: unrecognised patch format (expected IPS, IPS32 or pchtxt)", path.display())
}

pub fn expand_patch_paths(paths: &[PathBuf]) -> Result<Vec<PathBuf>> {
    let mut out = Vec::new();
    for p in paths {
        if p.is_dir() {
            let mut v: Vec<_> = std::fs::read_dir(p)?.filter_map(|e| e.ok().map(|e| e.path())).filter(|p| p.is_file()).collect();
            v.sort();
            out.extend(v);
        } else {
            out.push(p.clone());
        }
    }
    Ok(out)
}

/// Where a RomFS comes from: an image (file or a section of a container) or an extracted directory.
#[derive(Debug, Clone)]
pub enum RomfsSource {
    Image(RomfsRef),
    Dir(PathBuf),
}

/// Resolve one piece of dumped add-on content (an NSP/NCA with plaintext data NCAs, a RomFS image,
/// or an extracted RomFS directory) to its RomFS.
pub fn dlc_romfs(path: &Path) -> Result<RomfsSource> {
    if path.is_dir() {
        let romfs = if path.join("romfs").is_dir() { path.join("romfs") } else { path.to_path_buf() };
        for cand in ["romfs.bin", "romfs.romfs", "romfs.img"] {
            let p = path.join(cand);
            if p.is_file() {
                return Ok(RomfsSource::Image(RomfsRef { size: p.metadata()?.len(), path: p, offset: 0 }));
            }
        }
        return Ok(RomfsSource::Dir(romfs));
    }
    let mut f = File::open(path).with_context(|| format!("open {}", path.display()))?;
    let len = f.metadata()?.len();
    let head = read_at(&mut f, 0, 8.min(len as usize))?;
    if head.len() == 8 && u64le(&head, 0) == 0x50 {
        return Ok(RomfsSource::Image(RomfsRef { size: len, path: path.to_path_buf(), offset: 0 }));
    }
    let mut content = Content { want_data: true, ..Default::default() };
    read_container(path, &mut content)?;
    content
        .romfs
        .map(RomfsSource::Image)
        .ok_or_else(|| anyhow!("{}: no plaintext RomFS found in this add-on content", path.display()))
}

pub fn copy_romfs(r: &RomfsRef, dst: &Path) -> Result<()> {
    let mut f = File::open(&r.path)?;
    f.seek(SeekFrom::Start(r.offset))?;
    let mut out = File::create(dst)?;
    io::copy(&mut f.take(r.size), &mut out)?;
    Ok(())
}
