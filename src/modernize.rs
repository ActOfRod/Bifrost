//! Modernizer: takes an older Windows game (XP / Windows 7 era) and writes an updated copy of it that runs better on
//! current Windows. The original install is never modified.
//!
//! Steps: scan the install for its main executable and the graphics/audio/input APIs it uses → copy the game →
//! Large Address Aware patch → DXVK (Direct3D 8-11 → Vulkan) with a generated `dxvk.conf` → Windows compatibility
//! script → `bifrost_modernize.txt` report.
use anyhow::{bail, Context, Result};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::Read;
use std::path::{Component, Path, PathBuf};

use crate::pipeline::Event;

// ---- PE parsing ----

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Arch {
    X86,
    X64,
}

impl Arch {
    pub fn label(self) -> &'static str {
        match self {
            Arch::X86 => "32-bit (x86)",
            Arch::X64 => "64-bit (x64)",
        }
    }
}

#[derive(Debug, Clone)]
pub struct PeInfo {
    pub arch: Arch,
    pub large_address_aware: bool,
    /// Windows GUI subsystem (as opposed to console).
    pub gui: bool,
    /// Has an Authenticode signature, which any patch invalidates.
    pub signed: bool,
    /// Imported DLL names, lower case, including delay-loaded ones.
    pub imports: BTreeSet<String>,
    characteristics_off: usize,
    checksum_off: usize,
}

fn rd16(d: &[u8], o: usize) -> Result<u16> {
    d.get(o..o + 2).map(|b| u16::from_le_bytes([b[0], b[1]])).context("truncated PE")
}
fn rd32(d: &[u8], o: usize) -> Result<u32> {
    d.get(o..o + 4).map(|b| u32::from_le_bytes(b.try_into().unwrap())).context("truncated PE")
}
fn rd64(d: &[u8], o: usize) -> Result<u64> {
    d.get(o..o + 8).map(|b| u64::from_le_bytes(b.try_into().unwrap())).context("truncated PE")
}

pub fn parse_pe(d: &[u8]) -> Result<PeInfo> {
    if d.get(..2) != Some(b"MZ") {
        bail!("not a Windows executable (no MZ header)");
    }
    let pe = rd32(d, 0x3c)? as usize;
    if d.get(pe..pe + 4) != Some(b"PE\0\0") {
        bail!("not a PE executable");
    }
    let coff = pe + 4;
    let arch = match rd16(d, coff)? {
        0x14c => Arch::X86,
        0x8664 => Arch::X64,
        m => bail!("unsupported machine type {m:#x} (only x86 and x64 games can be modernized)"),
    };
    let nsec = rd16(d, coff + 2)? as usize;
    let opt_size = rd16(d, coff + 16)? as usize;
    let characteristics_off = coff + 18;
    let characteristics = rd16(d, characteristics_off)?;
    let opt = coff + 20;
    let pe32plus = match rd16(d, opt)? {
        0x10b => false,
        0x20b => true,
        m => bail!("unknown optional header magic {m:#x}"),
    };
    let image_base = if pe32plus { rd64(d, opt + 24)? } else { rd32(d, opt + 28)? as u64 };
    let checksum_off = opt + 64;
    let subsystem = rd16(d, opt + 68)?;
    let (ndirs_off, dirs) = if pe32plus { (opt + 108, opt + 112) } else { (opt + 92, opt + 96) };
    let ndirs = rd32(d, ndirs_off)? as usize;
    let dir = |i: usize| -> Result<(u32, u32)> {
        if i >= ndirs {
            return Ok((0, 0));
        }
        Ok((rd32(d, dirs + i * 8)?, rd32(d, dirs + i * 8 + 4)?))
    };
    let secs = opt + opt_size;
    let mut sections = Vec::new();
    for i in 0..nsec {
        let s = secs + i * 40;
        let vsize = rd32(d, s + 8)?;
        let va = rd32(d, s + 12)?;
        let raw_size = rd32(d, s + 16)?;
        let raw = rd32(d, s + 20)?;
        sections.push((va, vsize.max(raw_size), raw));
    }
    let to_off = |rva: u32| -> Option<usize> {
        sections
            .iter()
            .find(|&&(va, size, _)| rva >= va && rva - va < size)
            .map(|&(va, _, raw)| (rva - va + raw) as usize)
    };
    let cstr = |rva: u32| -> Option<String> {
        let o = to_off(rva)?;
        let end = d.get(o..)?.iter().take(260).position(|&b| b == 0)?;
        Some(String::from_utf8_lossy(&d[o..o + end]).to_ascii_lowercase())
    };
    let mut imports = BTreeSet::new();
    let (imp, _) = dir(1)?;
    if let Some(mut o) = (imp != 0).then(|| to_off(imp)).flatten() {
        while d.len() >= o + 20 && d[o..o + 20].iter().any(|&b| b != 0) {
            if let Some(n) = cstr(rd32(d, o + 12)?) {
                imports.insert(n);
            }
            o += 20;
        }
    }
    let (delay, _) = dir(13)?;
    if let Some(mut o) = (delay != 0).then(|| to_off(delay)).flatten() {
        while d.len() >= o + 32 && d[o..o + 32].iter().any(|&b| b != 0) {
            let attrs = rd32(d, o)?;
            let mut name = rd32(d, o + 4)? as u64;
            if attrs & 1 == 0 {
                name = name.wrapping_sub(image_base); // old-style descriptors hold VAs
            }
            if let Some(n) = cstr(name as u32) {
                imports.insert(n);
            }
            o += 32;
        }
    }
    Ok(PeInfo {
        arch,
        large_address_aware: characteristics & 0x20 != 0,
        gui: subsystem == 2,
        signed: dir(4)?.1 != 0,
        imports,
        characteristics_off,
        checksum_off,
    })
}

/// The PE image checksum (as computed by `CheckSumMappedFile`).
fn pe_checksum(d: &[u8], checksum_off: usize) -> u32 {
    let mut sum: u64 = 0;
    for (i, ch) in d.chunks(2).enumerate() {
        let pos = i * 2;
        if pos == checksum_off || pos == checksum_off + 2 {
            continue;
        }
        sum += ch[0] as u64 | (ch.get(1).copied().unwrap_or(0) as u64) << 8;
        sum = (sum & 0xffff) + (sum >> 16);
    }
    sum = (sum & 0xffff) + (sum >> 16);
    (sum as u32).wrapping_add(d.len() as u32)
}

/// Sets IMAGE_FILE_LARGE_ADDRESS_AWARE so a 32-bit game can use 4 GiB of address space on 64-bit Windows instead of
/// 2 GiB. Returns false when the flag was already set. The checksum is refreshed when the image had one.
pub fn patch_large_address_aware(d: &mut [u8]) -> Result<bool> {
    let info = parse_pe(d)?;
    if info.large_address_aware {
        return Ok(false);
    }
    let c = rd16(d, info.characteristics_off)? | 0x20;
    d[info.characteristics_off..info.characteristics_off + 2].copy_from_slice(&c.to_le_bytes());
    if rd32(d, info.checksum_off)? != 0 {
        let sum = pe_checksum(d, info.checksum_off);
        d[info.checksum_off..info.checksum_off + 4].copy_from_slice(&sum.to_le_bytes());
    }
    Ok(true)
}

// ---- API detection ----

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Api {
    DirectDraw,
    Direct3D8,
    Direct3D9,
    Direct3D10,
    Direct3D11,
    Dxgi,
    OpenGl,
    Glide,
    DirectSound,
    WinMm,
    DirectInput,
    XInput,
}

impl Api {
    fn from_dll(dll: &str) -> Option<Api> {
        Some(match dll {
            "ddraw.dll" => Api::DirectDraw,
            "d3d8.dll" => Api::Direct3D8,
            "d3d9.dll" => Api::Direct3D9,
            "d3d10.dll" | "d3d10_1.dll" | "d3d10core.dll" => Api::Direct3D10,
            "d3d11.dll" => Api::Direct3D11,
            "dxgi.dll" => Api::Dxgi,
            "opengl32.dll" => Api::OpenGl,
            "glide.dll" | "glide2x.dll" | "glide3x.dll" => Api::Glide,
            "dsound.dll" => Api::DirectSound,
            "winmm.dll" => Api::WinMm,
            "dinput.dll" | "dinput8.dll" => Api::DirectInput,
            d if d.starts_with("xinput") => Api::XInput,
            _ => return None,
        })
    }

    pub fn label(self) -> &'static str {
        match self {
            Api::DirectDraw => "DirectDraw",
            Api::Direct3D8 => "Direct3D 8",
            Api::Direct3D9 => "Direct3D 9",
            Api::Direct3D10 => "Direct3D 10",
            Api::Direct3D11 => "Direct3D 11",
            Api::Dxgi => "DXGI",
            Api::OpenGl => "OpenGL",
            Api::Glide => "3dfx Glide",
            Api::DirectSound => "DirectSound",
            Api::WinMm => "WinMM (audio/joystick)",
            Api::DirectInput => "DirectInput",
            Api::XInput => "XInput",
        }
    }

    /// The DXVK DLLs that translate this API to Vulkan (D3D8 runs on DXVK's D3D9).
    fn dxvk_dlls(self) -> &'static [&'static str] {
        match self {
            Api::Direct3D8 => &["d3d8.dll", "d3d9.dll"],
            Api::Direct3D9 => &["d3d9.dll"],
            Api::Direct3D10 => &["d3d10core.dll", "d3d11.dll", "dxgi.dll"],
            Api::Direct3D11 => &["d3d11.dll", "dxgi.dll"],
            Api::Dxgi => &["dxgi.dll"],
            _ => &[],
        }
    }
}

// ---- scanning ----

#[derive(Debug, Clone)]
pub struct ExeCandidate {
    /// Relative to the game folder.
    pub path: PathBuf,
    pub info: PeInfo,
    pub size: u64,
}

#[derive(Debug, Clone)]
pub struct Scan {
    pub root: PathBuf,
    pub exes: Vec<ExeCandidate>,
    /// Index into `exes` of the most likely game executable.
    pub main: Option<usize>,
}

/// What a game uses, from its executable plus the DLLs beside it (engines often draw from a DLL, e.g. a renderer
/// module, rather than from the .exe itself).
#[derive(Debug, Clone, Default)]
pub struct ApiReport {
    /// API → the files that import it (relative to the game folder).
    pub apis: BTreeMap<Api, Vec<PathBuf>>,
    /// DLLs shipped in the executable's folder that shadow system DLLs Bifrost would install (e.g. an existing wrapper).
    pub bundled_system_dlls: Vec<String>,
}

const SKIP_EXE: &[&str] = &[
    "unins", "setup", "install", "vcredist", "vc_redist", "dxsetup", "dxwebsetup", "dotnet", "crashreport", "crashhandler",
    "bugreport", "uninst", "autorun", "launcherupdate", "updater", "physx", "oalinst",
];

fn walk(dir: &Path, depth: usize, out: &mut Vec<PathBuf>) {
    let Ok(rd) = fs::read_dir(dir) else { return };
    let mut entries: Vec<_> = rd.flatten().map(|e| e.path()).collect();
    entries.sort();
    for p in entries {
        if p.is_dir() {
            if depth > 0 {
                walk(&p, depth - 1, out);
            }
        } else {
            out.push(p);
        }
    }
}

fn ext_is(p: &Path, ext: &str) -> bool {
    p.extension().and_then(|e| e.to_str()).is_some_and(|e| e.eq_ignore_ascii_case(ext))
}

/// Finds the game's executables. `game` may be the install folder or the game's .exe itself.
pub fn scan(game: &Path) -> Result<Scan> {
    let (root, only) = if game.is_file() {
        let parent = game.parent().filter(|p| !p.as_os_str().is_empty()).unwrap_or(Path::new("."));
        (parent.to_path_buf(), Some(game.to_path_buf()))
    } else if game.is_dir() {
        (game.to_path_buf(), None)
    } else {
        bail!("{} does not exist", game.display());
    };
    let mut files = Vec::new();
    match &only {
        Some(f) => files.push(f.clone()),
        None => walk(&root, 4, &mut files),
    }
    let mut exes = Vec::new();
    for f in files.into_iter().filter(|f| ext_is(f, "exe")) {
        let name = f.file_name().and_then(|n| n.to_str()).unwrap_or("").to_ascii_lowercase();
        if only.is_none() && SKIP_EXE.iter().any(|s| name.contains(s)) {
            continue;
        }
        let Ok(data) = fs::read(&f) else { continue };
        let Ok(info) = parse_pe(&data) else { continue };
        let rel = f.strip_prefix(&root).unwrap_or(&f).to_path_buf();
        exes.push(ExeCandidate { path: rel, info, size: data.len() as u64 });
    }
    let score = |e: &ExeCandidate| -> u64 {
        let draws = e.info.imports.iter().filter_map(|d| Api::from_dll(d)).any(|a| a <= Api::Glide);
        (draws as u64) * (1 << 40) + (e.info.gui as u64) * (1 << 39) + e.size
    };
    let main = (0..exes.len()).max_by_key(|&i| score(&exes[i]));
    if exes.is_empty() {
        bail!("no Windows game executable found in {}", root.display());
    }
    Ok(Scan { root, exes, main })
}

/// Collects the APIs used by `exe` (relative to `root`) and the DLLs in its folder.
pub fn detect_apis(root: &Path, exe: &Path) -> ApiReport {
    let mut rep = ApiReport::default();
    let exe_dir = root.join(exe).parent().map(Path::to_path_buf).unwrap_or_else(|| root.to_path_buf());
    let mut files = vec![root.join(exe)];
    if let Ok(rd) = fs::read_dir(&exe_dir) {
        let mut dlls: Vec<_> = rd.flatten().map(|e| e.path()).filter(|p| ext_is(p, "dll")).collect();
        dlls.sort();
        files.extend(dlls);
    }
    let installable: BTreeSet<&str> = [Api::Direct3D8, Api::Direct3D10, Api::Direct3D11]
        .iter()
        .flat_map(|a| a.dxvk_dlls().iter().copied())
        .collect();
    for f in files {
        let name = f.file_name().and_then(|n| n.to_str()).unwrap_or("").to_ascii_lowercase();
        if installable.contains(name.as_str()) {
            rep.bundled_system_dlls.push(name);
            continue; // a bundled d3d9.dll etc. is a wrapper, not the game
        }
        let Ok(data) = fs::read(&f) else { continue };
        let Ok(info) = parse_pe(&data) else { continue };
        let rel = f.strip_prefix(root).unwrap_or(&f).to_path_buf();
        for api in info.imports.iter().filter_map(|d| Api::from_dll(d)) {
            let v = rep.apis.entry(api).or_default();
            if !v.contains(&rel) {
                v.push(rel.clone());
            }
        }
    }
    rep
}

// ---- DXVK ----

pub const DXVK_VERSION: &str = "3.1.1";
const DXVK_URL: &str = "https://github.com/doitsujin/dxvk/releases/download/v3.1.1/dxvk-3.1.1.tar.gz";
const DXVK_SHA256: &str = "40565b4a724aadc4433fa4e010b4b23916d9b1f1baeee64e17186db94f54e608";
const DXVK_LICENSE: &str = include_str!("../third_party/dxvk/LICENSE");

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DxvkSource {
    /// Download the pinned DXVK release (checked against its SHA-256) and cache it.
    Download,
    /// A DXVK release archive (.tar.gz) or an extracted release folder containing x32/ and x64/.
    Local(PathBuf),
}

pub fn cache_dir() -> PathBuf {
    let base = if cfg!(windows) {
        std::env::var_os("LOCALAPPDATA").map(PathBuf::from)
    } else {
        std::env::var_os("XDG_CACHE_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".cache")))
    };
    base.unwrap_or_else(std::env::temp_dir).join("bifrost")
}

fn sha256_hex(data: &[u8]) -> String {
    Sha256::digest(data).iter().map(|b| format!("{b:02x}")).collect()
}

fn download_dxvk(r: &mut Reporter, lo: f32, hi: f32) -> Result<Vec<u8>> {
    let path = cache_dir().join(format!("dxvk-{DXVK_VERSION}.tar.gz"));
    if let Ok(data) = fs::read(&path) {
        if sha256_hex(&data) == DXVK_SHA256 {
            r.log(format!("Using cached DXVK {DXVK_VERSION} ({})", path.display()));
            return Ok(data);
        }
    }
    r.at(lo, format!("Downloading DXVK {DXVK_VERSION}"));
    let resp = ureq::get(DXVK_URL).call().context("downloading DXVK")?;
    let total: Option<u64> = resp.header("Content-Length").and_then(|v| v.parse().ok());
    let mut reader = resp.into_reader().take(256 << 20);
    let mut data = Vec::new();
    let mut buf = [0u8; 1 << 16];
    loop {
        let n = reader.read(&mut buf).context("downloading DXVK")?;
        if n == 0 {
            break;
        }
        data.extend_from_slice(&buf[..n]);
        if let Some(t) = total.filter(|&t| t > 0) {
            r.at(lo + (hi - lo) * (data.len() as f32 / t as f32), format!("Downloading DXVK {DXVK_VERSION}"));
        }
    }
    if sha256_hex(&data) != DXVK_SHA256 {
        bail!("the downloaded DXVK archive does not match its expected SHA-256; not using it");
    }
    if fs::create_dir_all(path.parent().unwrap()).is_ok() {
        let _ = fs::write(&path, &data);
    }
    Ok(data)
}

/// Reads the DLLs for `arch` from a DXVK release, keyed by lower-case file name.
fn dxvk_dlls(src: &DxvkSource, arch: Arch, r: &mut Reporter, lo: f32, hi: f32) -> Result<BTreeMap<String, Vec<u8>>> {
    let sub = match arch {
        Arch::X86 => "x32",
        Arch::X64 => "x64",
    };
    let mut out = BTreeMap::new();
    let archive = match src {
        DxvkSource::Download => download_dxvk(r, lo, hi)?,
        DxvkSource::Local(p) if p.is_dir() => {
            let dir = [p.join(sub), p.clone()].into_iter().find(|d| d.join("d3d9.dll").is_file());
            let dir = dir.with_context(|| format!("{} has no {sub}/d3d9.dll; expected an extracted DXVK release", p.display()))?;
            for e in fs::read_dir(&dir)?.flatten() {
                let name = e.file_name().to_string_lossy().to_ascii_lowercase();
                if name.ends_with(".dll") {
                    out.insert(name, fs::read(e.path())?);
                }
            }
            return Ok(out);
        }
        DxvkSource::Local(p) => fs::read(p).with_context(|| format!("reading {}", p.display()))?,
    };
    let mut tar = tar::Archive::new(flate2::read::GzDecoder::new(&archive[..]));
    for entry in tar.entries().context("reading the DXVK archive")? {
        let mut entry = entry?;
        let path = entry.path()?.to_path_buf();
        let parent = path.parent().and_then(|p| p.file_name()).and_then(|n| n.to_str());
        let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("").to_ascii_lowercase();
        if parent == Some(sub) && name.ends_with(".dll") {
            let mut data = Vec::new();
            entry.read_to_end(&mut data)?;
            out.insert(name, data);
        }
    }
    if out.is_empty() {
        bail!("the DXVK archive has no {sub}/ DLLs");
    }
    Ok(out)
}

// ---- options and run ----

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Compat {
    /// Override high-DPI scaling so Windows doesn't blur or mis-size the game.
    pub high_dpi: bool,
    /// Turn off fullscreen optimizations, which break exclusive fullscreen in many older games.
    pub disable_fullscreen_optimizations: bool,
    /// Windows XP SP3 compatibility mode.
    pub xp_mode: bool,
}

impl Compat {
    fn layers(&self) -> Vec<&'static str> {
        let mut v = Vec::new();
        if self.xp_mode {
            v.push("WINXPSP3");
        }
        if self.high_dpi {
            v.push("HIGHDPIAWARE");
        }
        if self.disable_fullscreen_optimizations {
            v.push("DISABLEDXMAXIMIZEDWINDOWEDMODE");
        }
        v
    }
}

impl Default for Compat {
    fn default() -> Self {
        Compat { high_dpi: true, disable_fullscreen_optimizations: true, xp_mode: false }
    }
}

#[derive(Debug, Clone)]
pub struct ModernizeOptions {
    /// Install folder, or the game's .exe.
    pub game: PathBuf,
    /// Main executable relative to the install folder; detected when None.
    pub exe: Option<PathBuf>,
    pub out: PathBuf,
    /// Translate Direct3D 8-11 to Vulkan with DXVK. None leaves the graphics API alone.
    pub dxvk: Option<DxvkSource>,
    /// Forced anisotropic filtering (0 = game default; otherwise 2, 4, 8 or 16). Needs DXVK.
    pub anisotropy: u32,
    /// Frame-rate cap (0 = none). Needs DXVK.
    pub max_fps: u32,
    pub large_address_aware: bool,
    pub compat: Compat,
    /// Also apply the compatibility settings to this user's registry (Windows only); the script is written either way.
    pub apply_compat: bool,
}

impl Default for ModernizeOptions {
    fn default() -> Self {
        ModernizeOptions {
            game: PathBuf::new(),
            exe: None,
            out: PathBuf::from("modernized"),
            dxvk: Some(DxvkSource::Download),
            anisotropy: 16,
            max_fps: 0,
            large_address_aware: true,
            compat: Compat::default(),
            apply_compat: false,
        }
    }
}

#[derive(Debug)]
pub struct ModernizeOutput {
    pub out: PathBuf,
    /// The modernized executable.
    pub exe: PathBuf,
    pub changes: Vec<String>,
    pub warnings: Vec<String>,
}

struct Reporter<'a> {
    sink: &'a mut dyn FnMut(Event),
}

impl Reporter<'_> {
    fn at(&mut self, p: f32, what: impl Into<String>) {
        (self.sink)(Event::Progress(p.clamp(0.0, 1.0), what.into()));
    }
    fn log(&mut self, s: impl Into<String>) {
        (self.sink)(Event::Log(s.into()));
    }
}

const REPORT: &str = "bifrost_modernize.txt";

fn copy_tree(src: &Path, dst: &Path, total: u64, done: &mut u64, r: &mut Reporter, lo: f32, hi: f32) -> Result<()> {
    fs::create_dir_all(dst)?;
    for e in fs::read_dir(src)?.flatten() {
        let (s, d) = (e.path(), dst.join(e.file_name()));
        if e.file_type()?.is_dir() {
            copy_tree(&s, &d, total, done, r, lo, hi)?;
        } else {
            *done += fs::copy(&s, &d).with_context(|| format!("copying {}", s.display()))?;
            r.at(lo + (hi - lo) * (*done as f32 / total.max(1) as f32), "Copying the game");
        }
    }
    Ok(())
}

fn tree_size(p: &Path) -> u64 {
    let mut files = Vec::new();
    walk(p, usize::MAX, &mut files);
    files.iter().filter_map(|f| f.metadata().ok()).map(|m| m.len()).sum()
}

/// Absolute, symlink-free form of `p`, which need not exist yet.
fn resolve(p: &Path) -> Result<PathBuf> {
    let abs = if p.is_absolute() { p.to_path_buf() } else { std::env::current_dir()?.join(p) };
    let mut base = abs.as_path();
    let mut rest = Vec::new();
    loop {
        if let Ok(c) = fs::canonicalize(base) {
            return Ok(rest.iter().rev().fold(c, |acc, n| acc.join(n)));
        }
        match (base.parent(), base.file_name()) {
            (Some(parent), Some(name)) => {
                rest.push(name.to_os_string());
                base = parent;
            }
            _ => bail!("can't resolve {}", p.display()),
        }
    }
}

fn win_path(p: &Path) -> String {
    p.components().map(|c| c.as_os_str().to_string_lossy()).collect::<Vec<_>>().join("\\")
}

pub fn run(opts: &ModernizeOptions, sink: &mut dyn FnMut(Event)) -> Result<ModernizeOutput> {
    let mut r = Reporter { sink };
    let mut changes = Vec::new();
    let mut warnings = Vec::new();

    r.at(0.0, "Scanning the game");
    let sc = scan(&opts.game)?;
    let exe_rel = match &opts.exe {
        Some(e) => {
            let e = e.strip_prefix(&sc.root).unwrap_or(e).to_path_buf();
            if !e.components().all(|c| matches!(c, Component::Normal(_))) || !sc.root.join(&e).is_file() {
                bail!("{} is not in {}", e.display(), sc.root.display());
            }
            e
        }
        None => sc.exes[sc.main.unwrap()].path.clone(),
    };
    let exe_data = fs::read(sc.root.join(&exe_rel))?;
    let info = parse_pe(&exe_data).with_context(|| format!("reading {}", exe_rel.display()))?;
    let apis = detect_apis(&sc.root, &exe_rel);
    r.log(format!("Game executable: {} ({})", exe_rel.display(), info.arch.label()));
    for (api, files) in &apis.apis {
        let names: Vec<_> = files.iter().map(|f| f.display().to_string()).collect();
        r.log(format!("  uses {} ({})", api.label(), names.join(", ")));
    }

    let root = fs::canonicalize(&sc.root)?;
    let out = resolve(&opts.out)?;
    if out.starts_with(&root) || root.starts_with(&out) {
        bail!("the output folder must be outside the game folder (and not contain it)");
    }
    // A previous output of this game is updated in place: only the executable and the files Bifrost writes are
    // touched, so saves and edited settings survive. A new output is built in a staging folder and moved into place.
    let rerun = out.join(REPORT).is_file() && fs::read(out.join(&exe_rel)).is_ok_and(|d| same_exe(&exe_data, &info, &d));
    if out.is_file() || !rerun && fs::read_dir(&out).is_ok_and(|mut d| d.next().is_some()) {
        if out.join(REPORT).is_file() {
            bail!(
                "{} is a Modernizer output of a different game or version (its {} doesn't match); choose a new output folder",
                out.display(),
                exe_rel.display()
            );
        }
        bail!("{} is not empty; choose an empty folder or a previous Modernizer output of this game", out.display());
    }

    // Fallible work that doesn't touch the output comes first.
    let wanted: BTreeSet<&str> = if opts.dxvk.is_some() { apis.apis.keys().flat_map(|a| a.dxvk_dlls().iter().copied()).collect() } else { BTreeSet::new() };
    if opts.dxvk.is_some() && wanted.is_empty() {
        let other: Vec<_> = apis.apis.keys().filter(|a| matches!(a, Api::DirectDraw | Api::OpenGl | Api::Glide)).map(|a| a.label()).collect();
        warnings.push(if other.is_empty() {
            "No Direct3D 8-11 use found, so DXVK was not installed".into()
        } else {
            format!("The game draws with {}, which the Modernizer can't translate yet; DXVK was not installed", other.join(" / "))
        });
    }
    let dlls = match &opts.dxvk {
        Some(src) if !wanted.is_empty() => Some(dxvk_dlls(src, info.arch, &mut r, 0.02, 0.2)?),
        _ => None,
    };
    if let Some(d) = &dlls {
        if let Some(missing) = wanted.iter().find(|n| !d.contains_key(**n)) {
            bail!("the DXVK release has no {missing}");
        }
    }

    let target = if rerun {
        r.log("Updating the previous Modernizer output in place (saves and edited settings are kept)");
        changes.push("Updated the existing modernized copy; files the player added or changed were kept".into());
        out.clone()
    } else {
        let name = out.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let stage = out.with_file_name(format!(".{name}.bifrost-staging"));
        if stage.exists() {
            fs::remove_dir_all(&stage)?;
        }
        let total = tree_size(&root);
        let mut done = 0;
        if let Err(e) = copy_tree(&root, &stage, total, &mut done, &mut r, 0.2, 0.7) {
            let _ = fs::remove_dir_all(&stage);
            return Err(e);
        }
        changes.push(format!("Copied the game ({:.1} MB); the original install is unchanged", total as f64 / 1e6));
        stage
    };

    let previous_exe = if rerun { previous_exe(&out) } else { None };
    let job = Job { opts, root: &root, target: &target, exe_rel: &exe_rel, exe_data: &exe_data, info: &info, rerun };
    if rerun {
        // Back up every file a re-run may touch so a failure leaves the previous output as it was.
        let backup: Vec<(PathBuf, Option<Vec<u8>>)> =
            job.touched(previous_exe.as_deref()).into_iter().map(|p| (p.clone(), fs::read(out.join(&p)).ok())).collect();
        if let Err(e) = job.apply(&apis, &wanted, dlls.as_ref(), previous_exe.as_deref(), &mut changes, &mut warnings, &mut r) {
            for (p, data) in backup {
                let _ = match data {
                    Some(d) => fs::write(out.join(&p), d),
                    None => fs::remove_file(out.join(&p)),
                };
            }
            return Err(e);
        }
    } else {
        let res = job.apply(&apis, &wanted, dlls.as_ref(), previous_exe.as_deref(), &mut changes, &mut warnings, &mut r);
        let res = res.and_then(|()| {
            if out.exists() {
                fs::remove_dir(&out)?; // empty, checked above
            }
            if let Some(parent) = out.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::rename(&target, &out).with_context(|| format!("creating {}", out.display()))
        });
        if let Err(e) = res {
            let _ = fs::remove_dir_all(&target);
            return Err(e);
        }
    }

    // Registry changes only once the output is in place.
    let layers = opts.compat.layers();
    if opts.apply_compat {
        if cfg!(windows) {
            let value = out.join(&exe_rel).display().to_string();
            let mut cmd = std::process::Command::new("reg");
            if layers.is_empty() {
                cmd.args(["delete", COMPAT_KEY, "/v", &value, "/f"]);
            } else {
                cmd.args(["add", COMPAT_KEY, "/v", &value, "/t", "REG_SZ", "/d", &format!("~ {}", layers.join(" ")), "/f"]);
            }
            let ok = cmd.stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).status().is_ok_and(|s| s.success());
            match (layers.is_empty(), ok) {
                (false, true) => changes.push("Applied the compatibility settings for this user".into()),
                (false, false) => warnings.push("Could not apply the compatibility settings; run bifrost_compat.bat".into()),
                (true, true) => changes.push("Cleared previously applied compatibility settings".into()),
                (true, false) => {} // nothing was applied before
            }
        } else {
            warnings.push("Compatibility settings can only be applied on Windows; run bifrost_compat.bat there".into());
        }
    }

    let mut report = format!("Bifrost Modernizer\n\n{EXE_LINE}{} ({})\n\nAPIs used:\n", exe_rel.display(), info.arch.label());
    for (api, files) in &apis.apis {
        let names: Vec<_> = files.iter().map(|f| f.display().to_string()).collect();
        report += &format!("  {} ({})\n", api.label(), names.join(", "));
    }
    report += "\nChanges:\n";
    for c in &changes {
        report += &format!("  - {c}\n");
    }
    if !warnings.is_empty() {
        report += "\nWarnings:\n";
        for w in &warnings {
            report += &format!("  - {w}\n");
        }
    }
    fs::write(out.join(REPORT), report)?;
    for c in &changes {
        r.log(format!("done: {c}"));
    }
    for w in &warnings {
        r.log(format!("warning: {w}"));
    }
    r.at(1.0, "Done");
    Ok(ModernizeOutput { exe: out.join(&exe_rel), out, changes, warnings })
}

const EXE_LINE: &str = "Game executable: ";
const COMPAT_KEY: &str = r"HKCU\Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers";
/// Files the Modernizer may write beside the game's executable.
const GENERATED_BESIDE_EXE: &[&str] = &["d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll", "DXVK_LICENSE.txt", "dxvk.conf"];

/// The executable named in a previous output's report, if it's a plain relative path.
fn previous_exe(out: &Path) -> Option<PathBuf> {
    let report = fs::read_to_string(out.join(REPORT)).ok()?;
    let line = report.lines().find_map(|l| l.strip_prefix(EXE_LINE))?;
    let p = PathBuf::from(line.rsplit_once(" (").map_or(line, |(p, _)| p));
    p.components().all(|c| matches!(c, Component::Normal(_))).then_some(p)
}

/// Whether `copy` is `orig`, allowing only the header fields the Modernizer patches to differ (so a folder is only
/// treated as an earlier output when it holds this game's own executable).
fn same_exe(orig: &[u8], info: &PeInfo, copy: &[u8]) -> bool {
    let patched = |i: usize| (info.characteristics_off..info.characteristics_off + 2).contains(&i) || (info.checksum_off..info.checksum_off + 4).contains(&i);
    orig.len() == copy.len() && orig.iter().zip(copy).enumerate().all(|(i, (a, b))| a == b || patched(i))
}

/// Characters that would let a file name escape a quoted argument in a .bat file.
fn batch_safe(s: &str) -> bool {
    !s.chars().any(|c| c.is_control() || "\"%^&|<>!".contains(c))
}

struct Job<'a> {
    opts: &'a ModernizeOptions,
    root: &'a Path,
    target: &'a Path,
    exe_rel: &'a Path,
    exe_data: &'a [u8],
    info: &'a PeInfo,
    rerun: bool,
}

impl Job<'_> {
    /// Every output file (relative) a re-run may write or remove.
    fn touched(&self, previous_exe: Option<&Path>) -> BTreeSet<PathBuf> {
        let exes: Vec<&Path> = [Some(self.exe_rel), previous_exe].into_iter().flatten().collect();
        // An executable is only reset when the game still has it; generated files beside it are always cleaned.
        let mut t: BTreeSet<PathBuf> = exes.iter().filter(|e| self.root.join(e).is_file()).map(|e| e.to_path_buf()).collect();
        for e in exes {
            let d = e.parent().map(Path::to_path_buf).unwrap_or_default();
            t.extend(GENERATED_BESIDE_EXE.iter().map(|n| d.join(n)));
        }
        t.extend(["bifrost_compat.bat", "bifrost_compat_undo.bat"].map(PathBuf::from));
        t
    }

    /// Puts a file Bifrost may have generated before back to the game's own version, or removes it.
    fn reset(&self, rel: &Path) -> Result<()> {
        let (src, dst) = (self.root.join(rel), self.target.join(rel));
        if src.is_file() {
            fs::copy(&src, &dst)?;
        } else if dst.is_file() {
            fs::remove_file(&dst)?;
        }
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    fn apply(
        &self,
        apis: &ApiReport,
        wanted: &BTreeSet<&str>,
        dlls: Option<&BTreeMap<String, Vec<u8>>>,
        previous_exe: Option<&Path>,
        changes: &mut Vec<String>,
        warnings: &mut Vec<String>,
        r: &mut Reporter,
    ) -> Result<()> {
        let (opts, info, exe_rel) = (self.opts, self.info, self.exe_rel);
        let exe_out = self.target.join(exe_rel);
        let exe_dir = exe_out.parent().map(Path::to_path_buf).unwrap_or_else(|| self.target.to_path_buf());

        if self.rerun {
            // Back to the game's own files (this also un-patches a previously selected executable).
            for p in self.touched(previous_exe) {
                self.reset(&p)?;
            }
        }

        if opts.large_address_aware {
            r.at(0.72, "Large Address Aware patch");
            if info.arch != Arch::X86 {
                r.log("Large Address Aware: not needed for 64-bit executables");
            } else {
                let mut d = self.exe_data.to_vec();
                if patch_large_address_aware(&mut d)? {
                    fs::write(&exe_out, &d)?;
                    changes.push(format!("Marked {} Large Address Aware (4 GB of memory instead of 2 GB)", exe_rel.display()));
                    if info.signed {
                        warnings.push("The executable was digitally signed; the patch invalidates that signature".into());
                    }
                } else {
                    r.log("Large Address Aware: already set");
                }
            }
        }

        let dxvk_installed = dlls.is_some();
        if let Some(dlls) = dlls {
            r.at(0.86, "Installing DXVK");
            for name in wanted {
                let data = dlls.get(*name).with_context(|| format!("the DXVK release has no {name}"))?;
                if apis.bundled_system_dlls.iter().any(|b| b == name) {
                    warnings.push(format!("Replaced the game's own {name} (an existing wrapper) with DXVK's"));
                }
                fs::write(exe_dir.join(name), data)?;
            }
            fs::write(exe_dir.join("DXVK_LICENSE.txt"), DXVK_LICENSE)?;
            let names: Vec<_> = wanted.iter().copied().collect();
            changes.push(format!("Installed DXVK {DXVK_VERSION} ({}): Direct3D now runs on Vulkan", names.join(", ")));
        }

        let mut conf = Vec::new();
        if opts.anisotropy > 0 {
            conf.push(format!("d3d9.samplerAnisotropy = {}", opts.anisotropy));
            conf.push(format!("d3d11.samplerAnisotropy = {}", opts.anisotropy));
        }
        if opts.max_fps > 0 {
            conf.push(format!("d3d9.maxFrameRate = {}", opts.max_fps));
            conf.push(format!("dxgi.maxFrameRate = {}", opts.max_fps));
        }
        if !conf.is_empty() {
            if dxvk_installed {
                let text = format!("# Written by the Bifrost Modernizer. Options: https://github.com/doitsujin/dxvk/blob/master/dxvk.conf\n{}\n", conf.join("\n"));
                fs::write(exe_dir.join("dxvk.conf"), text)?;
                if opts.anisotropy > 0 {
                    changes.push(format!("Forced {}x anisotropic filtering", opts.anisotropy));
                }
                if opts.max_fps > 0 {
                    changes.push(format!("Capped the frame rate at {} FPS", opts.max_fps));
                }
            } else {
                warnings.push("Anisotropic filtering and the frame-rate cap need DXVK, so they were skipped".into());
            }
        }

        let layers = opts.compat.layers();
        let exe_win = win_path(exe_rel);
        r.at(0.92, "Windows compatibility settings");
        if !batch_safe(&exe_win) {
            if !layers.is_empty() {
                warnings.push("The executable's path has characters that aren't safe in a .bat file, so bifrost_compat.bat was not written".into());
            }
            return Ok(());
        }
        if !layers.is_empty() {
            let apply = format!(
                "@echo off\r\nrem Written by the Bifrost Modernizer: Windows compatibility settings for the game (current user).\r\n\
                 reg add \"{COMPAT_KEY}\" /v \"%~dp0{exe_win}\" /t REG_SZ /d \"~ {}\" /f\r\n",
                layers.join(" ")
            );
            fs::write(self.target.join("bifrost_compat.bat"), apply)?;
            changes.push(format!("Wrote bifrost_compat.bat ({})", layers.join(", ")));
        }
        let undo = format!("@echo off\r\nreg delete \"{COMPAT_KEY}\" /v \"%~dp0{exe_win}\" /f\r\n");
        fs::write(self.target.join("bifrost_compat_undo.bat"), undo)?;
        Ok(())
    }
}
