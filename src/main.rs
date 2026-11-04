use std::path::PathBuf;
use std::process::ExitCode;

use anyhow::Context;
use clap::{Parser, Subcommand};

use bifrost::{self, Stop};

#[derive(Parser)]
#[command(
    name = "bifrost",
    about = "Lift an already-decrypted Nintendo Switch executable toward a native Windows program"
)]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Recompile a title (NSP/XCI/plaintext NCA/ExeFS dir/loose NSO, NRO, ELF) plus updates and
    /// patches into a native C project, optionally compiling it.
    Build {
        /// Base content
        #[arg(required = true)]
        inputs: Vec<PathBuf>,
        #[arg(short, long)]
        out: PathBuf,
        /// Update content, applied in order (its ExeFS replaces the base ExeFS)
        #[arg(long)]
        update: Vec<PathBuf>,
        /// IPS/IPS32/pchtxt patch files or directories of them
        #[arg(long)]
        patch: Vec<PathBuf>,
        /// Plaintext RomFS image or extracted romfs/ directory (overrides the one found in the content)
        #[arg(long)]
        romfs: Option<PathBuf>,
        /// Dumped add-on content: NSP/NCA with plaintext data NCAs, a RomFS image, or an extracted RomFS folder
        #[arg(long)]
        dlc: Vec<PathBuf>,
        /// Android SDK folder for --compile android (defaults to ANDROID_HOME)
        #[arg(long)]
        android_sdk: Option<PathBuf>,
        /// Renderer dependencies (scripts/build_android_deps.sh output) for NVN titles; defaults to BIFROST_ANDROID_DEPS
        #[arg(long)]
        android_deps: Option<PathBuf>,
        /// File with extra code entry points (one hex address per line, e.g. bifrost_missing.txt)
        #[arg(long)]
        extra_entries: Vec<PathBuf>,
        /// Compile after generation: none, native, windows (MinGW-w64) or android (NDK, produces an APK)
        #[arg(long, alias = "target", default_value = "none")]
        compile: String,
    },
    /// Modernize an older Windows game: writes an updated copy (DXVK, Large Address Aware, compatibility settings) and
    /// leaves the original install untouched.
    Modernize {
        /// Game install folder, or the game's .exe
        game: PathBuf,
        /// Output folder for the modernized copy (must be empty or a previous Modernizer output)
        #[arg(short, long, required_unless_present = "scan")]
        out: Option<PathBuf>,
        /// Only report the detected executable and APIs; write nothing
        #[arg(long)]
        scan: bool,
        /// Main executable, relative to the game folder (detected when omitted)
        #[arg(long)]
        exe: Option<PathBuf>,
        /// Use this DXVK release (.tar.gz or extracted folder) instead of downloading DXVK
        #[arg(long, conflicts_with = "no_dxvk")]
        dxvk: Option<PathBuf>,
        /// Leave Direct3D alone (no DXVK)
        #[arg(long)]
        no_dxvk: bool,
        /// Forced anisotropic filtering: 0 (game default), 2, 4, 8 or 16. Needs DXVK
        #[arg(long, default_value_t = 16, value_parser = parse_anisotropy)]
        anisotropy: u32,
        /// Frame-rate cap (0 = none). Needs DXVK
        #[arg(long, default_value_t = 0)]
        max_fps: u32,
        /// Don't apply the Large Address Aware patch
        #[arg(long)]
        no_laa: bool,
        /// Don't override high-DPI scaling
        #[arg(long)]
        no_high_dpi: bool,
        /// Keep Windows fullscreen optimizations
        #[arg(long)]
        keep_fullscreen_optimizations: bool,
        /// Run in Windows XP SP3 compatibility mode
        #[arg(long)]
        xp_mode: bool,
        /// Apply the compatibility settings to this user's registry now (Windows only)
        #[arg(long)]
        apply_compat: bool,
        /// Use this cnc-ddraw release (cnc-ddraw.zip or extracted folder) instead of downloading it
        #[arg(long, conflicts_with = "no_ddraw")]
        ddraw: Option<PathBuf>,
        /// Leave DirectDraw alone (no cnc-ddraw)
        #[arg(long)]
        no_ddraw: bool,
        /// Upscale DirectDraw games through cnc-ddraw or Direct3D 8–11 games through the Bifrost Vulkan layer
        #[arg(long)]
        fsr: bool,
        /// Directory containing the x86/ and x64/ Bifrost FSR Vulkan layer builds
        #[arg(long, value_name = "DIR")]
        fsr_layer: Option<PathBuf>,
    },
    /// Developer aid: print the C that each hex instruction word lifts to (used by tests/diff).
    #[command(hide = true)]
    LiftWords {
        #[arg(long, default_value = "0x10080000")]
        pc: String,
        words: Vec<String>,
    },
    /// Show the format, imports, and how much of the code lifts
    Inspect {
        file: PathBuf,
        /// Instructions to disassemble from the entry point
        #[arg(long, default_value_t = 16)]
        disasm: usize,
    },
    /// Write C, a runtime shim, and the guest image
    Recompile {
        file: PathBuf,
        #[arg(short, long)]
        out: PathBuf,
    },
    /// Interpret the lifted program
    Run {
        file: PathBuf,
        /// Guest entry address, hex. Defaults to the module entry
        #[arg(long)]
        entry: Option<String>,
        /// Register argument, hex. Repeat up to eight times
        #[arg(long = "arg")]
        args: Vec<String>,
        #[arg(long, default_value_t = 1_000_000)]
        max_steps: u64,
        /// Run DT_INIT and DT_INIT_ARRAY before the entry point
        #[arg(long)]
        boot: bool,
    },
    /// Package a Windows game as a portable Android launcher import
    Pack {
        /// Game install folder, or the game's .exe
        game: PathBuf,
        /// Main executable relative to the game folder (detected when omitted)
        #[arg(long)]
        exe: Option<PathBuf>,
        /// Output package (defaults to <game folder name>.bifrost)
        #[arg(short, long)]
        out: Option<PathBuf>,
        /// Display title (defaults to the game folder name)
        #[arg(long)]
        title: Option<String>,
        /// Optional PNG or JPEG cover image
        #[arg(long)]
        cover: Option<PathBuf>,
        /// Replace an existing output package
        #[arg(long)]
        force: bool,
    },
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(err) => {
            eprintln!("bifrost: {err:#}");
            ExitCode::from(1)
        }
    }
}

fn run() -> anyhow::Result<()> {
    match Cli::parse().cmd {
        Cmd::LiftWords { pc, words } => lift_words(&pc, &words),
        Cmd::Build {
            inputs,
            out,
            update,
            patch,
            romfs,
            dlc,
            android_sdk,
            android_deps,
            extra_entries,
            compile,
        } => {
            let opts = bifrost::pipeline::BuildOptions {
                inputs,
                updates: update,
                patches: patch,
                dlc,
                romfs,
                extra_entries,
                out,
                target: compile.parse()?,
                android_sdk,
                android_deps,
                ..Default::default()
            };
            build(&opts)
        }
        Cmd::Modernize {
            game,
            out,
            scan,
            exe,
            dxvk,
            no_dxvk,
            anisotropy,
            max_fps,
            no_laa,
            no_high_dpi,
            keep_fullscreen_optimizations,
            xp_mode,
            apply_compat,
            ddraw,
            no_ddraw,
            fsr,
            fsr_layer,
        } => {
            use bifrost::modernize::{Compat, DxvkSource, ModernizeOptions, Upscale};
            if scan {
                return modernize_scan(&game);
            }
            let opts = ModernizeOptions {
                game,
                exe,
                out: out.expect("clap requires --out"),
                dxvk: match (no_dxvk, dxvk) {
                    (true, _) => None,
                    (false, Some(p)) => Some(DxvkSource::Local(p)),
                    (false, None) => Some(DxvkSource::Download),
                },
                anisotropy,
                max_fps,
                large_address_aware: !no_laa,
                compat: Compat {
                    high_dpi: !no_high_dpi,
                    disable_fullscreen_optimizations: !keep_fullscreen_optimizations,
                    xp_mode,
                },
                apply_compat,
                ddraw: match (no_ddraw, ddraw) {
                    (true, _) => None,
                    (false, Some(p)) => Some(DxvkSource::Local(p)),
                    (false, None) => Some(DxvkSource::Download),
                },
                upscale: if fsr { Upscale::Fsr1 } else { Upscale::Off },
                fsr_layer,
            };
            modernize(&opts)
        }
        Cmd::Inspect { file, disasm } => inspect(&file, disasm),
        Cmd::Recompile { file, out } => recompile(&file, &out),
        Cmd::Run {
            file,
            entry,
            args,
            max_steps,
            boot,
        } => run_module(&file, entry.as_deref(), &args, max_steps, boot),
        Cmd::Pack { game, exe, out, title, cover, force } => {
            let default_name = if game.is_file() {
                game.parent().and_then(std::path::Path::file_name).or_else(|| game.file_stem())
            } else {
                game.file_name()
            }.unwrap_or_default().to_string_lossy();
            let out = out.unwrap_or_else(|| PathBuf::from(format!("{default_name}.bifrost")));
            let path = bifrost::pack::run(&bifrost::pack::PackOptions {
                game,
                exe,
                out,
                title,
                cover,
                replace: force,
            }, &mut |event| {
                match event {
                    bifrost::pipeline::Event::Progress(_, step) => eprintln!("[bifrost] {step}"),
                    bifrost::pipeline::Event::Log(line) => println!("{line}"),
                }
            })?;
            println!("wrote {}", path.display());
            Ok(())
        }
    }
}

fn inspect(file: &std::path::Path, disasm: usize) -> anyhow::Result<()> {
    let module = bifrost::load_path(file).with_context(|| format!("loading {}", file.display()))?;
    let lifted = bifrost::lift(&module);
    print!("{}", bifrost::report_text(&module, &lifted));
    if !module.imports.is_empty() {
        println!("import substitution:");
        for import in module.imports.iter().take(32) {
            println!("  {}", import.name);
        }
        if module.imports.len() > 32 {
            println!("  ... {} more", module.imports.len() - 32);
        }
    }
    if disasm > 0 {
        match bifrost::disassemble(&module, module.entry, disasm) {
            Ok(lines) => {
                println!("entry disassembly:");
                for line in lines {
                    println!("  {line}");
                }
            }
            Err(err) => eprintln!("disassembly skipped: {err}"),
        }
    }
    if lifted.stats.unsupported > 0 {
        println!("unsupported examples:");
        for (addr, word) in &lifted.stats.examples {
            println!("  {addr:#x}: {word:#010x}");
        }
    }
    Ok(())
}

fn recompile(file: &std::path::Path, out: &std::path::Path) -> anyhow::Result<()> {
    let module = bifrost::load_path(file).with_context(|| format!("loading {}", file.display()))?;
    let lifted = bifrost::lift(&module);
    let report = bifrost::emit(&module, &lifted, out)?;
    println!("wrote {} files to {}", report.files.len(), out.display());
    println!(
        "lifted {} instructions, {} unsupported, {} imports",
        report.lifted, report.unsupported, report.imports
    );
    println!("compile with: clang -O2 -std=c11 -o bifrost_guest.exe main.c lifted.c bifrost_rt.c");
    println!("this output is a local derivative of the input executable");
    Ok(())
}

fn run_module(
    file: &std::path::Path,
    entry: Option<&str>,
    args: &[String],
    max_steps: u64,
    boot: bool,
) -> anyhow::Result<()> {
    let mut module =
        bifrost::load_path(file).with_context(|| format!("loading {}", file.display()))?;
    let entry = match entry {
        Some(text) => parse_hex(text)?,
        None => module.entry,
    };
    let args = args
        .iter()
        .map(|arg| parse_hex(arg))
        .collect::<anyhow::Result<Vec<_>>>()?;
    let lifted = bifrost::lift(&module);
    let result = bifrost::execute(&mut module, &lifted, entry, &args, max_steps, boot);
    println!("stop: {}", result.stop);
    println!("x0: {:#x}", result.x0);
    println!("steps: {}", result.steps);
    for name in &result.imports {
        println!("import: {name}");
    }
    for line in &result.debug {
        println!("debug: {line}");
    }
    if matches!(
        result.stop,
        Stop::Return | Stop::Exit | Stop::Break | Stop::StepLimit
    ) {
        Ok(())
    } else {
        anyhow::bail!("{}", result.stop)
    }
}

fn parse_hex(text: &str) -> anyhow::Result<u64> {
    let text = text
        .strip_prefix("0x")
        .or_else(|| text.strip_prefix("0X"))
        .unwrap_or(text);
    u64::from_str_radix(text, 16).with_context(|| format!("expected hex, found {text}"))
}

fn lift_words(pc: &str, words: &[String]) -> anyhow::Result<()> {
    use bifrost::lift::decode::{decode, Op};
    let pc = parse_hex(pc)?;
    for w in words {
        let word = u32::from_str_radix(w.trim_start_matches("0x"), 16)?;
        let text = match decode(word, pc) {
            Op::C(s) => format!("C\t{s}"),
            other => format!("X\t{other:?}"),
        };
        println!("{word:08x}\t{text}");
    }
    Ok(())
}

fn build(opts: &bifrost::pipeline::BuildOptions) -> anyhow::Result<()> {
    use bifrost::pipeline::Event;
    let mut last = String::new();
    let result = bifrost::pipeline::run(opts, &mut |ev| match ev {
        Event::Progress(_, step)
            if step != last
                && !step.contains("compiled")
                && !step.starts_with("Copying")
                && !step.starts_with("Packing") =>
        {
            eprintln!("[bifrost] {step}");
            last = step;
        }
        Event::Progress(..) => {}
        Event::Log(line) => println!("{line}"),
    })?;
    let r = &result.report;
    println!(
        "{}: {} functions, {} instructions ({} unsupported), {} jump tables, {} imports resolved, {} unresolved, {} C units",
        result.title, r.functions, r.instructions, r.unsupported, r.jump_tables, r.resolved_imports, r.imports, r.units
    );
    println!("wrote {}", result.project.display());
    if let Some(a) = &result.artifact {
        println!("built {}", a.display());
    }
    Ok(())
}

fn modernize_scan(game: &std::path::Path) -> anyhow::Result<()> {
    use bifrost::modernize::{detect_apis, scan};
    let sc = scan(game)?;
    for (i, e) in sc.exes.iter().enumerate() {
        let mark = if Some(i) == sc.main { "*" } else { " " };
        let laa = if e.info.large_address_aware { ", large address aware" } else { "" };
        println!("{mark} {} ({}{laa})", e.path.display(), e.info.arch.label());
    }
    let main = &sc.exes[sc.main.unwrap()];
    let apis = detect_apis(&sc.root, &main.path);
    println!("APIs used by {}:", main.path.display());
    for (api, files) in &apis.apis {
        let names: Vec<_> = files.iter().map(|f| f.display().to_string()).collect();
        println!("  {} ({})", api.label(), names.join(", "));
    }
    Ok(())
}

fn modernize(opts: &bifrost::modernize::ModernizeOptions) -> anyhow::Result<()> {
    use bifrost::pipeline::Event;
    let mut last = String::new();
    let result = bifrost::modernize::run(opts, &mut |ev| match ev {
        Event::Progress(_, step) if step != last => {
            eprintln!("[bifrost] {step}");
            last = step;
        }
        Event::Progress(..) => {}
        Event::Log(line) => println!("{line}"),
    })?;
    println!("wrote {}", result.out.display());
    println!("run {}", result.exe.display());
    Ok(())
}

fn parse_anisotropy(s: &str) -> Result<u32, String> {
    match s.parse() {
        Ok(n @ (0 | 2 | 4 | 8 | 16)) => Ok(n),
        _ => Err("expected 0, 2, 4, 8 or 16".into()),
    }
}
