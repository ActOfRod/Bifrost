//! The end-to-end build used by both the CLI and the GUI: intake → patches → link → lift → generated
//! project → RomFS/DLC → compile (native, Windows, or an Android APK), reporting progress as it goes.
use anyhow::{bail, Context, Result};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Mutex};

use crate::android::{self, AndroidSdk};
use crate::renderer::{self, RendererDeps, RendererPlatform};
use crate::intake::{self, RomfsRef, RomfsSource};
use crate::lift::cfg::{lift_program, CodeSource};
use crate::project::{self, BuildReport, ProjectInfo};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputSystem {
    Switch,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Target {
    /// Generate the project only.
    None,
    /// Compile for the machine running Bifrost.
    Native,
    Windows,
    Android,
}

impl std::str::FromStr for Target {
    type Err = anyhow::Error;
    fn from_str(s: &str) -> Result<Self> {
        Ok(match s.to_ascii_lowercase().as_str() {
            "none" => Target::None,
            "native" => Target::Native,
            "windows" => Target::Windows,
            "android" => Target::Android,
            other => bail!("target must be none, native, windows or android (got {other})"),
        })
    }
}

#[derive(Debug, Clone)]
pub struct BuildOptions {
    pub system: InputSystem,
    pub inputs: Vec<PathBuf>,
    pub updates: Vec<PathBuf>,
    pub patches: Vec<PathBuf>,
    pub dlc: Vec<PathBuf>,
    pub romfs: Option<PathBuf>,
    pub extra_entries: Vec<PathBuf>,
    pub out: PathBuf,
    pub target: Target,
    /// Android SDK folder; falls back to `ANDROID_HOME` / `ANDROID_SDK_ROOT`.
    pub android_sdk: Option<PathBuf>,
    /// NVN → Vulkan renderer dependencies (scripts/build_android_deps.sh output).
    pub android_deps: Option<PathBuf>,
    /// NVN → Vulkan renderer dependencies (scripts/build_windows_deps.sh output).
    pub windows_deps: Option<PathBuf>,
    /// RomFS images above this size are left beside the APK instead of being embedded.
    pub android_embed_limit: u64,
}

impl Default for BuildOptions {
    fn default() -> Self {
        BuildOptions {
            system: InputSystem::Switch,
            inputs: Vec::new(),
            updates: Vec::new(),
            patches: Vec::new(),
            dlc: Vec::new(),
            romfs: None,
            extra_entries: Vec::new(),
            out: PathBuf::from("out"),
            target: Target::None,
            android_sdk: None,
            android_deps: None,
            windows_deps: None,
            android_embed_limit: 1 << 30,
        }
    }
}

#[derive(Debug, Clone)]
pub enum Event {
    /// Overall progress in 0..=1 and the current step.
    Progress(f32, String),
    Log(String),
}

#[derive(Debug)]
pub struct BuildOutput {
    pub report: BuildReport,
    pub title: String,
    pub project: PathBuf,
    /// The executable or APK, when a compile target was chosen.
    pub artifact: Option<PathBuf>,
    pub notes: Vec<String>,
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

fn parse_hex(text: &str) -> Result<u64> {
    let t = text.trim_start_matches("0x").trim_start_matches("0X");
    u64::from_str_radix(t, 16).with_context(|| format!("bad hex address {text}"))
}

/// Copy or pack a RomFS into `dst`, reporting byte progress mapped onto `lo..hi`.
fn materialise_romfs(
    src: &RomfsSource,
    dst: &Path,
    r: &mut Reporter,
    lo: f32,
    hi: f32,
    label: &str,
) -> Result<u64> {
    match src {
        RomfsSource::Image(img) => {
            use std::io::{Read, Seek, SeekFrom, Write};
            let mut f = std::fs::File::open(&img.path)
                .with_context(|| format!("open {}", img.path.display()))?;
            f.seek(SeekFrom::Start(img.offset))?;
            let mut out = std::io::BufWriter::new(std::fs::File::create(dst)?);
            let mut buf = vec![0u8; 4 << 20];
            let mut done = 0u64;
            while done < img.size {
                let want = (img.size - done).min(buf.len() as u64) as usize;
                let n = f.read(&mut buf[..want])?;
                if n == 0 {
                    bail!("{} is truncated", img.path.display());
                }
                out.write_all(&buf[..n])?;
                done += n as u64;
                r.at(
                    lo + (hi - lo) * done as f32 / img.size.max(1) as f32,
                    format!("Copying {label}"),
                );
            }
            out.flush()?;
            Ok(img.size)
        }
        RomfsSource::Dir(dir) => {
            let label = format!("Packing {label} from {}", dir.display());
            crate::romfs::build_from_dir(dir, dst, &mut |done, total| {
                r.at(
                    lo + (hi - lo) * done as f32 / total.max(1) as f32,
                    label.clone(),
                );
            })
        }
    }
}

/// Compile every `.c` in `sources` to an object in `obj_dir` in parallel, then link `output`.
#[allow(clippy::too_many_arguments)]
fn compile_and_link(
    cc: &Path,
    cwd: &Path,
    sources: &[PathBuf],
    cflags: &[String],
    ldflags: &[String],
    obj_dir: &Path,
    output: &Path,
    r: &mut Reporter,
    lo: f32,
    hi: f32,
    label: &str,
) -> Result<()> {
    std::fs::create_dir_all(obj_dir)?;
    let jobs = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4);
    let next = Arc::new(AtomicUsize::new(0));
    let failure: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
    let (tx, rx) = mpsc::channel::<()>();
    let objs: Vec<PathBuf> = sources
        .iter()
        .enumerate()
        .map(|(i, s)| {
            obj_dir.join(format!(
                "{i:04}_{}.o",
                s.file_stem().unwrap().to_string_lossy()
            ))
        })
        .collect();
    std::thread::scope(|scope| {
        for _ in 0..jobs.min(sources.len().max(1)) {
            let (next, failure, tx) = (next.clone(), failure.clone(), tx.clone());
            let objs = &objs;
            scope.spawn(move || loop {
                let i = next.fetch_add(1, Ordering::SeqCst);
                if i >= sources.len() || failure.lock().unwrap().is_some() {
                    break;
                }
                let out = Command::new(cc)
                    .current_dir(cwd)
                    .args(["-c", "-O2", "-std=gnu11", "-fno-strict-aliasing", "-w"])
                    .args(cflags)
                    .arg(&sources[i])
                    .arg("-o")
                    .arg(&objs[i])
                    .output();
                match out {
                    Ok(o) if o.status.success() => {}
                    Ok(o) => {
                        *failure.lock().unwrap() = Some(format!(
                            "{} failed on {}: {}",
                            cc.display(),
                            sources[i].display(),
                            String::from_utf8_lossy(&o.stderr)
                        ))
                    }
                    Err(e) => {
                        *failure.lock().unwrap() = Some(format!("cannot run {}: {e}", cc.display()))
                    }
                }
                let _ = tx.send(());
            });
        }
        drop(tx);
        let mut done = 0;
        while rx.recv().is_ok() {
            done += 1;
            r.at(
                lo + (hi - lo) * 0.9 * done as f32 / sources.len() as f32,
                format!("{label}: compiled {done}/{}", sources.len()),
            );
        }
    });
    if let Some(e) = failure.lock().unwrap().take() {
        bail!(e);
    }
    r.at(lo + (hi - lo) * 0.9, format!("{label}: linking"));
    let out = Command::new(cc)
        .current_dir(cwd)
        .args(&objs)
        .arg("-o")
        .arg(output)
        .args(ldflags)
        .output()
        .with_context(|| format!("run {}", cc.display()))?;
    if !out.status.success() {
        bail!(
            "linking {} failed: {}",
            output.display(),
            String::from_utf8_lossy(&out.stderr)
        );
    }
    Ok(())
}

pub fn run(opts: &BuildOptions, sink: &mut dyn FnMut(Event)) -> Result<BuildOutput> {
    let mut r = Reporter { sink };
    let InputSystem::Switch = opts.system;
    anyhow::ensure!(!opts.inputs.is_empty(), "no base game selected");
    std::fs::create_dir_all(&opts.out).with_context(|| format!("create {}", opts.out.display()))?;
    let out = &opts.out.canonicalize()?;

    // Validate the toolchain before the long steps.
    let sdk = match opts.target {
        Target::Android => Some(AndroidSdk::locate(opts.android_sdk.as_deref())?),
        _ => None,
    };

    r.at(0.01, "Reading game content");
    let content = intake::gather(&opts.inputs, &opts.updates)?;
    let mut notes = content.notes.clone();
    if content.exefs.is_empty() {
        if content.romfs.is_some() || content.romfs_dir.is_some() {
            bail!(
                "the input only contains a RomFS (game assets, no code). Add the game's ExeFS (main, subsdk*, sdk, main.npdm), \
                 a decrypted NSP, or a folder containing exefs/ and romfs/"
            );
        }
        bail!("no executables found in the input");
    }
    r.at(0.04, "Loading executables");
    let (mut modules, load_notes) = intake::load_modules(&content)?;
    notes.extend(load_notes);
    let patches = intake::expand_patch_paths(&opts.patches)?;
    for (i, p) in patches.iter().enumerate() {
        r.at(
            0.06 + 0.02 * i as f32 / patches.len() as f32,
            format!("Applying {}", p.display()),
        );
        notes.push(intake::apply_patch(&mut modules, p)?);
    }
    r.at(0.08, "Linking modules");
    let program = crate::link::link(modules);
    for m in &program.modules {
        r.log(format!(
            "module {:<10} base {:#x} entry {:#x} text {:#x} bytes",
            m.name, m.image_base, m.entry, m.text.size
        ));
    }
    let mut entries: BTreeSet<u64> = program.entry_points();
    for file in &opts.extra_entries {
        let text =
            std::fs::read_to_string(file).with_context(|| format!("read {}", file.display()))?;
        for line in text.lines().map(str::trim).filter(|l| !l.is_empty()) {
            let va = parse_hex(line)?;
            if program.is_code(va) {
                entries.insert(va);
            }
        }
    }

    r.at(0.10, "Lifting AArch64 code");
    let lifted = lift_program(&program, &entries);
    r.at(0.35, format!("Lifted {} functions", lifted.functions.len()));
    let title = content.title.clone().unwrap_or_else(|| {
        opts.inputs[0]
            .file_stem()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_else(|| "guest".into())
    });
    let exe: String = title
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .collect();
    let exe = if exe.is_empty() {
        "guest".to_string()
    } else {
        exe
    };

    r.at(0.36, "Generating C");
    let info = ProjectInfo {
        title: &title,
        title_id: content.title_id,
        exe_name: &exe,
    };
    let report = project::write_project(&program, &lifted, &info, out)?;
    for (va, word) in lifted.stats.examples.iter().take(8) {
        r.log(format!("unsupported {word:08x} at {va:#x}"));
    }

    let romfs_dst = out.join("romfs.bin");
    let same_file = |p: &Path| p.canonicalize().is_ok_and(|p| p == romfs_dst);
    let in_place = opts.romfs.as_deref().is_some_and(same_file)
        || (opts.romfs.is_none()
            && content
                .romfs
                .as_ref()
                .is_some_and(|r| r.offset == 0 && same_file(&r.path)));
    if !in_place {
        let _ = std::fs::remove_file(&romfs_dst);
    }
    let romfs = match &opts.romfs {
        _ if in_place => None,
        Some(p) if p.is_dir() => Some(RomfsSource::Dir(p.clone())),
        Some(p) => Some(RomfsSource::Image(RomfsRef {
            size: std::fs::metadata(p)?.len(),
            path: p.clone(),
            offset: 0,
        })),
        None => content
            .romfs
            .clone()
            .map(RomfsSource::Image)
            .or_else(|| content.romfs_dir.clone().map(RomfsSource::Dir)),
    };
    let romfs_size = match &romfs {
        Some(src) => materialise_romfs(src, &romfs_dst, &mut r, 0.45, 0.62, "RomFS")?,
        None if in_place => std::fs::metadata(&romfs_dst)?.len(),
        None => {
            notes.push("no RomFS supplied; the game will not find its assets".into());
            0
        }
    };

    let dlc_dir = out.join("dlc");
    let _ = std::fs::remove_dir_all(&dlc_dir);
    if !opts.dlc.is_empty() {
        std::fs::create_dir_all(&dlc_dir)?;
        let n = opts.dlc.len() as f32;
        for (i, p) in opts.dlc.iter().enumerate() {
            let src = intake::dlc_romfs(p)?;
            let (lo, hi) = (0.62 + 0.06 * i as f32 / n, 0.62 + 0.06 * (i + 1) as f32 / n);
            materialise_romfs(
                &src,
                &dlc_dir.join(format!("{}.romfs", i + 1)),
                &mut r,
                lo,
                hi,
                &format!("DLC {}", i + 1),
            )?;
            r.log(format!("DLC {} ← {}", i + 1, p.display()));
        }
    }
    r.at(0.68, "Project written");

    let mut sources: Vec<PathBuf> = std::fs::read_dir(out)?
        .filter_map(|e| e.ok().map(|e| PathBuf::from(e.file_name())))
        .filter(|n| n.extension().is_some_and(|x| x == "c"))
        .collect();
    sources.sort();
    let artifact = match opts.target {
        Target::None => None,
        Target::Native => {
            let (cc, file, ldflags): (String, String, Vec<String>) =
                {
                    let cc = std::env::var("CC").unwrap_or_else(|_| "cc".into());
                    let file = if cfg!(windows) { format!("{exe}.exe") } else { exe.clone() };
                    let ld = if cfg!(windows) { vec![] } else { vec!["-lpthread".into(), "-lm".into()] };
                    (cc, file, ld)
                };
            let output = out.join(&file);
            compile_and_link(Path::new(&cc), out, &sources, &[], &ldflags, &out.join("obj"), &output,
                &mut r, 0.68, 0.99, "Compiling")?;
            Some(output)
        }
        Target::Windows => {
            let nvn = program.imports.iter().any(|(_, n)| renderer::NVN_IMPORTS.contains(&n.as_str()));
            let deps = if nvn {
                RendererDeps::locate(RendererPlatform::Windows, opts.windows_deps.as_deref())
            } else {
                None
            };
            if nvn && deps.is_none() {
                notes.push(
                    "title draws through NVN but the renderer dependencies were not found (run scripts/build_windows_deps.sh, \
                     then pass --windows-deps); this executable has no renderer".into(),
                );
            }
                let output = out.join(format!("{exe}.exe"));
            if let Some(deps) = deps {
                sources.extend(renderer::write_renderer_sources(out, &deps, RendererPlatform::Windows)?);
                let cc = renderer::mingw_posix_tool("x86_64-w64-mingw32-gcc")?;
                let cxx = renderer::mingw_posix_tool("x86_64-w64-mingw32-g++")?;
                let vma = out.join("obj/windows/vma.o");
                let imgui = out.join("obj/windows/imgui_glue.o");
                renderer::compile_windows_cpp(
                    Path::new("las/nvn/vulkan/vma.cpp"), &cxx, &deps, out, &vma,
                )?;
                renderer::compile_windows_cpp(
                    Path::new("las/nvn/vulkan/imgui_glue.cpp"), &cxx, &deps, out, &imgui,
                )?;
                let mut cflags = renderer::defines(&deps, out, RendererPlatform::Windows);
                cflags.extend(["-DBF_NO_MAIN".into(), "-fno-strict-aliasing".into()]);
                let mut ldflags = vec![
                    "-static-libstdc++".into(),
                    "-static-libgcc".into(),
                    vma.display().to_string(),
                    imgui.display().to_string(),
                ];
                for (i, source) in [
                    "imgui.cpp",
                    "imgui_draw.cpp",
                    "imgui_tables.cpp",
                    "imgui_widgets.cpp",
                    "backends/imgui_impl_vulkan.cpp",
                ]
                .iter()
                .enumerate()
                {
                    let obj = out.join(format!("obj/windows/imgui_{i}.o"));
                    renderer::compile_windows_cpp(
                        &deps.root.join("include/imgui").join(source),
                        &cxx,
                        &deps,
                        out,
                        &obj,
                    )?;
                    ldflags.push(obj.display().to_string());
                }
                ldflags.extend([
                    format!("-L{}", deps.root.join("lib").display()),
                    "-lSDL3".into(),
                    "-lshaderc_shared".into(),
                    "-lvulkan-1".into(),
                    "-Wl,-Bstatic".into(),
                    "-lstdc++".into(),
                    "-lwinpthread".into(),
                    "-Wl,-Bdynamic".into(),
                ]);
                compile_and_link(
                    &cc, out, &sources, &cflags, &ldflags, &out.join("obj/windows"), &output,
                    &mut r, 0.68, 0.99, "Compiling Windows NVN renderer",
                )?;
                for entry in std::fs::read_dir(deps.root.join("lib"))? {
                    let source = entry?.path();
                    if source.extension().and_then(|s| s.to_str()).is_some_and(|s| s.eq_ignore_ascii_case("dll")) {
                        let name = source.file_name().unwrap();
                        std::fs::copy(&source, out.join(name))
                            .with_context(|| format!("copy renderer runtime {}", name.to_string_lossy()))?;
                    }
                }
            } else {
                let cc = if cfg!(windows) { "gcc" } else { "x86_64-w64-mingw32-gcc" };
                compile_and_link(
                    Path::new(cc), out, &sources, &[], &["-static".into()], &out.join("obj"),
                    &output, &mut r, 0.68, 0.99, "Compiling",
                )?;
            }
            Some(output)
        }
        Target::Android => {
            let sdk = sdk.expect("located above");
            let package = android::package_name(content.title_id, &exe);
            let embed = romfs_size > 0 && romfs_size <= opts.android_embed_limit;
            if romfs_size > 0 && !embed {
                notes.push(format!(
                    "RomFS is {} MiB, too large to embed: copy romfs.bin to /sdcard/Android/data/{package}/files/ on the device",
                    romfs_size >> 20
                ));
            }
            let nvn = program
                .imports
                .iter()
                .any(|(_, n)| renderer::NVN_IMPORTS.contains(&n.as_str()));
            let deps = if nvn {
                RendererDeps::locate(RendererPlatform::Android, opts.android_deps.as_deref())
            } else {
                None
            };
            if nvn && deps.is_none() {
                notes.push(
                    "title draws through NVN but the renderer dependencies were not found (run scripts/build_android_deps.sh, \
                     then pass --android-deps); this APK has no renderer"
                        .into(),
                );
            }
            if let Some(deps) = deps {
                android::write_sources(out, &package, &title)?;
                std::fs::write(
                    out.join("android").join("AndroidManifest.xml"),
                    android::renderer_manifest(&package, &title),
                )?;
                let clang = sdk.clang()?;
                let mut all = sources.clone();
                all.extend(renderer::write_renderer_sources(out, &deps, RendererPlatform::Android)?);
                r.at(0.68, "Compiling the renderer's C++ part");
                let vma = android::compile_vma(&sdk, &deps, out)?;
                let mut cflags = android::renderer_compile_args(&deps, out);
                cflags.extend(["-x".to_string(), "c".to_string()]);
                let abi = android::RENDERER_ABI.0;
                let so = out.join("android").join("lib").join(abi).join("libmain.so");
                std::fs::create_dir_all(so.parent().unwrap())?;
                compile_and_link(
                    &clang,
                    out,
                    &all,
                    &cflags,
                    &android::renderer_link_args(&deps, &vma),
                    &out.join("obj").join(abi),
                    &so,
                    &mut r,
                    0.69,
                    0.93,
                    &format!("Compiling {abi} with the Vulkan renderer"),
                )?;
                r.at(0.94, "Compiling the Java glue");
                let dex = android::build_dex(&sdk, &deps, out)?;
                r.at(0.96, "Packaging APK");
                let extras = android::ApkExtras {
                    lib_name: "libmain.so".into(),
                    shared_libs: deps.shared_libs(&sdk, out)?,
                    dex: Some(dex),
                };
                Some(android::package(
                    out,
                    &sdk,
                    &exe,
                    &[(abi.to_string(), so)],
                    embed,
                    &extras,
                )?)
            } else {
                android::write_sources(
                    out,
                    &android::package_name(content.title_id, &exe),
                    &title,
                )?;
                let clang = sdk.clang()?;
                let glue = sdk.glue_dir().join("android_native_app_glue.c");
                let mut all = sources.clone();
                all.push(PathBuf::from("bf_android.c.in"));
                all.push(glue);
                let mut libs = Vec::new();
                let n = android::ABIS.len() as f32;
                for (i, (abi, triple)) in android::ABIS.iter().enumerate() {
                    let (lo, hi) = (0.68 + 0.27 * i as f32 / n, 0.68 + 0.27 * (i + 1) as f32 / n);
                    let mut cflags = android::compile_args(&sdk, triple);
                    cflags.extend(["-x".to_string(), "c".to_string()]);
                    let so = out
                        .join("android")
                        .join("lib")
                        .join(abi)
                        .join("libbifrost.so");
                    std::fs::create_dir_all(so.parent().unwrap())?;
                    compile_and_link(
                        &clang,
                        out,
                        &all,
                        &cflags,
                        &android::link_args(triple),
                        &out.join("obj").join(abi),
                        &so,
                        &mut r,
                        lo,
                        hi,
                        &format!("Compiling {abi}"),
                    )?;
                    libs.push((abi.to_string(), so));
                }
                r.at(0.96, "Packaging APK");
                Some(android::package(
                    out,
                    &sdk,
                    &exe,
                    &libs,
                    embed,
                    &Default::default(),
                )?)
            }
        }
    };
    for n in &notes {
        r.log(format!("note: {n}"));
    }
    r.at(1.0, "Done");
    Ok(BuildOutput {
        report,
        title,
        project: out.clone(),
        artifact,
        notes,
    })
}
