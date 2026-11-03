//! Build a Horizon RomFS image from an extracted directory tree.
use anyhow::{Context, Result};
use std::fs::{self, File};
use std::io::{self, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

const EMPTY: u32 = 0xFFFF_FFFF;
const HEADER_SIZE: u64 = 0x50;
const DATA_OFFSET: u64 = 0x200;

pub fn path_hash(parent: u32, name: &[u8]) -> u32 {
    let mut h = parent ^ 123_456_789;
    for &b in name {
        h = h.rotate_right(5) ^ b as u32;
    }
    h
}

pub fn hash_table_len(entries: usize) -> usize {
    if entries < 3 {
        return 3;
    }
    if entries < 19 {
        return entries | 1;
    }
    let mut n = entries;
    while [2, 3, 5, 7, 11, 13, 17].iter().any(|p| n.is_multiple_of(*p)) {
        n += 1;
    }
    n
}

fn pad4(n: usize) -> usize {
    (n + 3) & !3
}

struct Dir {
    name: Vec<u8>,
    parent: usize,
    dirs: Vec<usize>,
    files: Vec<usize>,
    off: u32,
}

struct FileEnt {
    name: Vec<u8>,
    parent: usize,
    path: PathBuf,
    size: u64,
    data: u64,
    off: u32,
}

/// Write `root` as a RomFS image to `out`; `progress(done, total)` is called as file data is copied.
/// Returns the image size.
pub fn build_from_dir(root: &Path, out: &Path, progress: &mut dyn FnMut(u64, u64)) -> Result<u64> {
    let mut dirs = vec![Dir { name: Vec::new(), parent: 0, dirs: Vec::new(), files: Vec::new(), off: 0 }];
    let mut files: Vec<FileEnt> = Vec::new();
    let mut stack = vec![(0usize, root.to_path_buf())];
    while let Some((di, path)) = stack.pop() {
        let mut entries: Vec<_> = fs::read_dir(&path)
            .with_context(|| format!("read {}", path.display()))?
            .collect::<io::Result<Vec<_>>>()?;
        entries.sort_by_key(|e| e.file_name());
        for e in entries {
            let name = e.file_name().to_string_lossy().into_owned().into_bytes();
            let ty = e.file_type()?;
            if ty.is_dir() {
                let idx = dirs.len();
                dirs.push(Dir { name, parent: di, dirs: Vec::new(), files: Vec::new(), off: 0 });
                dirs[di].dirs.push(idx);
                stack.push((idx, e.path()));
            } else if ty.is_file() {
                let idx = files.len();
                files.push(FileEnt { name, parent: di, path: e.path(), size: e.metadata()?.len(), data: 0, off: 0 });
                dirs[di].files.push(idx);
            }
        }
    }

    let mut off = 0u32;
    for d in &mut dirs {
        d.off = off;
        off += (0x18 + pad4(d.name.len())) as u32;
    }
    let dir_meta_len = off as usize;
    let mut off = 0u32;
    let mut data = 0u64;
    for f in &mut files {
        f.off = off;
        off += (0x20 + pad4(f.name.len())) as u32;
        data = (data + 0xF) & !0xF;
        f.data = data;
        data += f.size;
    }
    let file_meta_len = off as usize;

    let dir_buckets = hash_table_len(dirs.len());
    let file_buckets = hash_table_len(files.len());
    let mut dir_hash = vec![EMPTY; dir_buckets];
    let mut file_hash = vec![EMPTY; file_buckets];
    let mut dir_meta = vec![0u8; dir_meta_len];
    let mut file_meta = vec![0u8; file_meta_len];
    let put = |buf: &mut Vec<u8>, at: usize, v: u32| buf[at..at + 4].copy_from_slice(&v.to_le_bytes());

    for (i, d) in dirs.iter().enumerate() {
        let parent_off = dirs[d.parent].off;
        let sibling = if i == 0 {
            EMPTY
        } else {
            let sibs = &dirs[d.parent].dirs;
            let pos = sibs.iter().position(|&s| s == i).unwrap();
            sibs.get(pos + 1).map_or(EMPTY, |&s| dirs[s].off)
        };
        let bucket = path_hash(parent_off, &d.name) as usize % dir_buckets;
        let at = d.off as usize;
        put(&mut dir_meta, at, parent_off);
        put(&mut dir_meta, at + 4, sibling);
        put(&mut dir_meta, at + 8, d.dirs.first().map_or(EMPTY, |&c| dirs[c].off));
        put(&mut dir_meta, at + 12, d.files.first().map_or(EMPTY, |&c| files[c].off));
        put(&mut dir_meta, at + 16, dir_hash[bucket]);
        put(&mut dir_meta, at + 20, d.name.len() as u32);
        dir_meta[at + 24..at + 24 + d.name.len()].copy_from_slice(&d.name);
        dir_hash[bucket] = d.off;
    }
    for f in &files {
        let parent = &dirs[f.parent];
        let pos = parent.files.iter().position(|&s| files[s].off == f.off).unwrap();
        let sibling = parent.files.get(pos + 1).map_or(EMPTY, |&s| files[s].off);
        let bucket = path_hash(parent.off, &f.name) as usize % file_buckets;
        let at = f.off as usize;
        put(&mut file_meta, at, parent.off);
        put(&mut file_meta, at + 4, sibling);
        file_meta[at + 8..at + 16].copy_from_slice(&f.data.to_le_bytes());
        file_meta[at + 16..at + 24].copy_from_slice(&f.size.to_le_bytes());
        put(&mut file_meta, at + 24, file_hash[bucket]);
        put(&mut file_meta, at + 28, f.name.len() as u32);
        file_meta[at + 32..at + 32 + f.name.len()].copy_from_slice(&f.name);
        file_hash[bucket] = f.off;
    }

    let dir_hash_off = (DATA_OFFSET + data + 3) & !3;
    let dir_meta_off = dir_hash_off + dir_buckets as u64 * 4;
    let file_hash_off = dir_meta_off + dir_meta_len as u64;
    let file_meta_off = file_hash_off + file_buckets as u64 * 4;
    let end = file_meta_off + file_meta_len as u64;
    let header = [
        HEADER_SIZE,
        dir_hash_off,
        dir_buckets as u64 * 4,
        dir_meta_off,
        dir_meta_len as u64,
        file_hash_off,
        file_buckets as u64 * 4,
        file_meta_off,
        file_meta_len as u64,
        DATA_OFFSET,
    ];

    let mut w = BufWriter::with_capacity(1 << 20, File::create(out).with_context(|| format!("create {}", out.display()))?);
    for v in header {
        w.write_all(&v.to_le_bytes())?;
    }
    w.write_all(&vec![0u8; (DATA_OFFSET - HEADER_SIZE) as usize])?;
    let mut pos = 0u64;
    let mut buf = vec![0u8; 1 << 20];
    for f in &files {
        w.write_all(&vec![0u8; (f.data - pos) as usize])?;
        let mut src = File::open(&f.path).with_context(|| format!("open {}", f.path.display()))?;
        let mut left = f.size;
        while left > 0 {
            let want = left.min(buf.len() as u64) as usize;
            let n = src.read(&mut buf[..want])?;
            anyhow::ensure!(n > 0, "{} shrank while packing", f.path.display());
            w.write_all(&buf[..n])?;
            left -= n as u64;
            progress(f.data + f.size - left, data);
        }
        pos = f.data + f.size;
    }
    w.write_all(&vec![0u8; (dir_hash_off - DATA_OFFSET - pos) as usize])?;
    for v in dir_hash {
        w.write_all(&v.to_le_bytes())?;
    }
    w.write_all(&dir_meta)?;
    for v in file_hash {
        w.write_all(&v.to_le_bytes())?;
    }
    w.write_all(&file_meta)?;
    w.flush()?;
    progress(data, data);
    Ok(end)
}

/// Look a file up by `/`-separated path in a RomFS image, returning its bytes.
pub fn read_file(image: &Path, path: &str) -> Result<Option<Vec<u8>>> {
    let mut f = File::open(image)?;
    let mut hdr = [0u8; 0x50];
    f.read_exact(&mut hdr)?;
    let h = |i: usize| u64::from_le_bytes(hdr[i * 8..i * 8 + 8].try_into().unwrap());
    let mut table = |off: u64, len: u64| -> Result<Vec<u8>> {
        let mut v = vec![0u8; len as usize];
        f.seek(SeekFrom::Start(off))?;
        f.read_exact(&mut v)?;
        Ok(v)
    };
    let (dh, dm, fh, fm) = (table(h(1), h(2))?, table(h(3), h(4))?, table(h(5), h(6))?, table(h(7), h(8))?);
    let u32at = |b: &[u8], at: usize| u32::from_le_bytes(b[at..at + 4].try_into().unwrap());
    let parts: Vec<&str> = path.split('/').filter(|p| !p.is_empty()).collect();
    let Some((leaf, dirs)) = parts.split_last() else { return Ok(None) };
    let mut cur = 0u32;
    for name in dirs {
        let mut e = u32at(&dh, (path_hash(cur, name.as_bytes()) as usize % (dh.len() / 4)) * 4);
        loop {
            if e == EMPTY {
                return Ok(None);
            }
            let at = e as usize;
            let len = u32at(&dm, at + 20) as usize;
            if u32at(&dm, at) == cur && &dm[at + 24..at + 24 + len] == name.as_bytes() {
                cur = e;
                break;
            }
            e = u32at(&dm, at + 16);
        }
    }
    let mut e = u32at(&fh, (path_hash(cur, leaf.as_bytes()) as usize % (fh.len() / 4)) * 4);
    while e != EMPTY {
        let at = e as usize;
        let len = u32at(&fm, at + 28) as usize;
        if u32at(&fm, at) == cur && &fm[at + 32..at + 32 + len] == leaf.as_bytes() {
            let data = u64::from_le_bytes(fm[at + 8..at + 16].try_into().unwrap());
            let size = u64::from_le_bytes(fm[at + 16..at + 24].try_into().unwrap());
            return table(h(9) + data, size).map(Some);
        }
        e = u32at(&fm, at + 24);
    }
    Ok(None)
}
