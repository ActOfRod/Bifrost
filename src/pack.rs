use anyhow::{bail, Context, Result};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};
use zip::write::FileOptions;
use zip::{CompressionMethod, ZipWriter};

use crate::modernize::{self, Api, Arch};
use crate::pipeline::Event;

pub struct PackOptions {
    pub game: PathBuf,
    pub exe: Option<PathBuf>,
    pub out: PathBuf,
    pub title: Option<String>,
    pub cover: Option<PathBuf>,
}

fn absolute(p: &Path) -> Result<PathBuf> {
    let p = if p.is_absolute() { p.to_path_buf() } else { std::env::current_dir()?.join(p) };
    let mut base = p.as_path();
    let mut rest = Vec::new();
    loop {
        if let Ok(canonical) = fs::canonicalize(base) {
            return Ok(rest.iter().rev().fold(canonical, |path, name| path.join(name)));
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

fn uses_link(root: &Path, rel: &Path) -> bool {
    let mut path = root.to_path_buf();
    rel.components().any(|component| {
        path.push(component);
        fs::symlink_metadata(&path).is_ok_and(|metadata| metadata.file_type().is_symlink() || modernize::is_reparse_point(&path))
    })
}

fn collect_files(root: &Path, dir: &Path, out: &mut Vec<PathBuf>, skipped_links: &mut usize) -> Result<()> {
    let mut entries = fs::read_dir(dir)?.collect::<std::io::Result<Vec<_>>>()?;
    entries.sort_by_key(|entry| entry.file_name());
    for entry in entries {
        let path = entry.path();
        let kind = entry.file_type()?;
        if kind.is_symlink() || modernize::is_reparse_point(&path) {
            *skipped_links += 1;
        } else if kind.is_dir() {
            collect_files(root, &path, out, skipped_links)?;
        } else if kind.is_file() && !uses_link(root, path.strip_prefix(root)?) {
            out.push(path);
        }
    }
    Ok(())
}

fn relative_name(root: &Path, path: &Path) -> Result<String> {
    let relative = path.strip_prefix(root)?;
    let parts = relative.components().map(|component| match component {
        Component::Normal(name) => Ok(name.to_string_lossy().into_owned()),
        _ => bail!("invalid game path {}", relative.display()),
    }).collect::<Result<Vec<_>>>()?;
    Ok(parts.join("/"))
}

fn json_string(value: &str) -> String {
    let mut result = String::with_capacity(value.len() + 2);
    result.push('"');
    for ch in value.chars() {
        match ch {
            '"' => result.push_str("\\\""),
            '\\' => result.push_str("\\\\"),
            '\n' => result.push_str("\\n"),
            '\r' => result.push_str("\\r"),
            '\t' => result.push_str("\\t"),
            c if c < ' ' => result.push_str(&format!("\\u{:04x}", c as u32)),
            c => result.push(c),
        }
    }
    result.push('"');
    result
}

fn partial_path(out: &Path) -> Result<PathBuf> {
    let mut name = out.file_name().context("output needs a file name")?.to_os_string();
    name.push(".partial");
    Ok(out.with_file_name(name))
}

pub fn run(opts: &PackOptions, sink: &mut dyn FnMut(Event)) -> Result<PathBuf> {
    let scan = modernize::scan(&opts.game)?;
    let root = fs::canonicalize(&scan.root)?;
    let exe = match &opts.exe {
        Some(exe) => exe.strip_prefix(&scan.root).unwrap_or(exe).to_path_buf(),
        None => scan.exes[scan.main.context("no main executable found")?].path.clone(),
    };
    if !exe.components().all(|component| matches!(component, Component::Normal(_)))
        || !root.join(&exe).is_file()
        || uses_link(&root, &exe)
    {
        bail!("{} is not a regular executable inside {}", exe.display(), root.display());
    }
    let exe_data = fs::read(root.join(&exe)).with_context(|| format!("reading {}", exe.display()))?;
    let info = modernize::parse_pe(&exe_data).with_context(|| format!("reading {}", exe.display()))?;
    let apis = modernize::detect_apis(&root, &exe);
    let output = absolute(&opts.out)?;
    if output.starts_with(&root) {
        bail!("the output package must be outside the game folder");
    }
    if output.exists() {
        bail!("{} already exists", output.display());
    }
    let partial = partial_path(&output)?;
    if partial.exists() {
        bail!("{} already exists", partial.display());
    }
    let cover = if let Some(cover) = &opts.cover {
        let extension = cover.extension().and_then(|extension| extension.to_str()).unwrap_or("").to_ascii_lowercase();
        let archive_name = match extension.as_str() {
            "png" => "cover.png",
            "jpg" | "jpeg" => "cover.jpg",
            _ => bail!("cover image must be PNG or JPEG"),
        };
        Some((cover, archive_name))
    } else {
        None
    };
    let title = opts.title.clone().or_else(|| root.file_name().map(|name| name.to_string_lossy().into_owned()))
        .unwrap_or_else(|| "Game".into());
    let exe_name = relative_name(&root, &root.join(&exe))?;
    let arch = match info.arch { Arch::X86 => "x86", Arch::X64 => "x64" };
    let api_values = apis.apis.keys().map(|api: &Api| json_string(api.label())).collect::<Vec<_>>().join(",");
    let manifest = format!(
        "{{\"format\":1,\"title\":{},\"exe\":{},\"arch\":{},\"apis\":[{}],\"created_by\":{}}}",
        json_string(&title),
        json_string(&exe_name),
        json_string(arch),
        api_values,
        json_string(&format!("bifrost {}", env!("CARGO_PKG_VERSION")))
    );

    let mut files = Vec::new();
    let mut skipped_links = 0;
    collect_files(&root, &root, &mut files, &mut skipped_links)?;
    let total = files.iter().filter_map(|path| fs::metadata(path).ok()).map(|metadata| metadata.len()).sum::<u64>()
        + cover.map_or(0, |(path, _)| fs::metadata(path).map(|metadata| metadata.len()).unwrap_or(0));
    if let Some(parent) = output.parent() {
        fs::create_dir_all(parent)?;
    }
    let file = OpenOptions::new().write(true).create_new(true).open(&partial)
        .with_context(|| format!("creating {}", partial.display()))?;
    let result = (|| -> Result<()> {
        let mut zip = ZipWriter::new(file);
        let options = FileOptions::default().compression_method(CompressionMethod::Stored).large_file(true);
        zip.start_file("bifrost.json", options)?;
        zip.write_all(manifest.as_bytes())?;
        let mut copied = 0u64;
        let mut buffer = [0u8; 64 * 1024];
        for path in files {
            let relative = relative_name(&root, &path)?;
            zip.start_file(format!("game/{relative}"), options)?;
            let mut input = File::open(&path).with_context(|| format!("reading {}", path.display()))?;
            loop {
                let count = input.read(&mut buffer)?;
                if count == 0 { break; }
                zip.write_all(&buffer[..count])?;
                copied += count as u64;
                sink(Event::Progress(0.1 + 0.85 * copied as f32 / total.max(1) as f32, "Packing game files".into()));
            }
        }
        if let Some((cover, archive_name)) = cover {
            zip.start_file(archive_name, options)?;
            let mut input = File::open(cover).with_context(|| format!("reading cover {}", cover.display()))?;
            loop {
                let count = input.read(&mut buffer)?;
                if count == 0 { break; }
                zip.write_all(&buffer[..count])?;
                copied += count as u64;
                sink(Event::Progress(0.1 + 0.85 * copied as f32 / total.max(1) as f32, "Packing cover image".into()));
            }
        }
        zip.finish()?.sync_all()?;
        fs::rename(&partial, &output).with_context(|| format!("moving package to {}", output.display()))?;
        sink(Event::Progress(1.0, "Package complete".into()));
        sink(Event::Log(format!("Created {}", output.display())));
        if skipped_links > 0 {
            sink(Event::Log(format!("Skipped {skipped_links} symlink or junction(s)")));
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&partial);
    }
    result?;
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    use zip::ZipArchive;

    static NEXT_TEST_DIR: AtomicU64 = AtomicU64::new(0);

    fn pe32() -> Vec<u8> {
        let mut image = vec![0u8; 0x200];
        image[..2].copy_from_slice(b"MZ");
        image[0x3c..0x40].copy_from_slice(&0x80u32.to_le_bytes());
        image[0x80..0x84].copy_from_slice(b"PE\0\0");
        image[0x84..0x86].copy_from_slice(&0x14cu16.to_le_bytes());
        image[0x86..0x88].copy_from_slice(&0u16.to_le_bytes());
        image[0x94..0x96].copy_from_slice(&0xe0u16.to_le_bytes());
        image[0x98..0x9a].copy_from_slice(&0x10bu16.to_le_bytes());
        image
    }

    fn test_dir() -> PathBuf {
        let root = std::env::temp_dir().join(format!(
            "bifrost-pack-{}-{}",
            std::process::id(),
            NEXT_TEST_DIR.fetch_add(1, Ordering::Relaxed),
        ));
        fs::create_dir_all(root.join("game")).unwrap();
        root
    }

    #[test]
    fn package_contains_manifest_game_and_skips_symlinks() {
        let temp = test_dir();
        let game = temp.join("game");
        assert!(modernize::parse_pe(&pe32()).is_ok());
        fs::write(game.join("Game.exe"), pe32()).unwrap();
        fs::write(game.join("data.bin"), b"data").unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(temp.join("outside"), game.join("linked")).unwrap();
        let output = temp.join("game.bifrost");
        let result = run(&PackOptions {
            game: game.clone(), exe: None, out: output.clone(), title: Some("A \"Game\"".into()), cover: None,
        }, &mut |_| {}).unwrap();
        assert_eq!(result, output);
        let mut zip = ZipArchive::new(File::open(&output).unwrap()).unwrap();
        let mut manifest = String::new();
        zip.by_name("bifrost.json").unwrap().read_to_string(&mut manifest).unwrap();
        assert!(manifest.contains("\"format\":1"));
        assert!(manifest.contains("\"title\":\"A \\\"Game\\\"\""));
        assert!(manifest.contains("\"exe\":\"Game.exe\""));
        assert!(manifest.contains("\"arch\":\"x86\""));
        assert!(zip.by_name("game/Game.exe").is_ok());
        assert!(zip.by_name("game/data.bin").is_ok());
        assert!(zip.by_name("game/linked").is_err());
        let _ = fs::remove_dir_all(temp);
    }

    #[test]
    fn package_refuses_output_inside_game() {
        let temp = test_dir();
        fs::write(temp.join("game/Game.exe"), pe32()).unwrap();
        let result = run(&PackOptions {
            game: temp.join("game"), exe: None, out: temp.join("game/out.bifrost"), title: None, cover: None,
        }, &mut |_| {});
        assert!(result.is_err());
        let _ = fs::remove_dir_all(temp);
    }
}
