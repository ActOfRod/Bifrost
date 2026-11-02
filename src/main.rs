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
        /// Plaintext RomFS image to ship with the program (overrides the one found in the content)
        #[arg(long)]
        romfs: Option<PathBuf>,
        /// File with extra code entry points (one hex address per line, e.g. bifrost_missing.txt)
        #[arg(long)]
        extra_entries: Vec<PathBuf>,
        /// Compile after generation: none, native, or windows (MinGW-w64 cross compiler)
        #[arg(long, default_value = "none")]
        compile: String,
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
        Cmd::Build { inputs, out, update, patch, romfs, extra_entries, compile } => {
            build(&inputs, &out, &update, &patch, romfs, &extra_entries, &compile)
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
    println!(
        "wrote {} files to {}",
        report.files.len(),
        out.display()
    );
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
    let text = text.strip_prefix("0x").or_else(|| text.strip_prefix("0X")).unwrap_or(text);
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

fn build(
    inputs: &[PathBuf],
    out: &std::path::Path,
    updates: &[PathBuf],
    patches: &[PathBuf],
    romfs: Option<PathBuf>,
    extra_entries: &[PathBuf],
    compile: &str,
) -> anyhow::Result<()> {
    use bifrost::intake;
    use bifrost::lift::cfg::{lift_program, CodeSource};
    let content = intake::gather(inputs, updates)?;
    for note in &content.notes {
        println!("note: {note}");
    }
    let (mut modules, notes) = intake::load_modules(&content)?;
    for note in notes {
        println!("note: {note}");
    }
    for p in intake::expand_patch_paths(patches)? {
        println!("{}", intake::apply_patch(&mut modules, &p)?);
    }
    let program = bifrost::link::link(modules);
    for m in &program.modules {
        println!("module {:<10} base {:#x} entry {:#x} text {:#x} bytes", m.name, m.image_base, m.entry, m.text.size);
    }
    let mut entries = program.entry_points();
    for file in extra_entries {
        let text = std::fs::read_to_string(file).with_context(|| format!("read {}", file.display()))?;
        for line in text.lines().map(str::trim).filter(|l| !l.is_empty()) {
            let va = parse_hex(line)?;
            if program.is_code(va) {
                entries.insert(va);
            }
        }
    }
    let lifted = lift_program(&program, &entries);
    let title = content.title.clone().unwrap_or_else(|| {
        inputs[0].file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| "guest".into())
    });
    let exe: String = title.chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '_' }).collect();
    let exe = if exe.is_empty() { "guest".to_string() } else { exe };
    let info = bifrost::project::ProjectInfo { title: &title, title_id: content.title_id, exe_name: &exe };
    let report = bifrost::project::write_project(&program, &lifted, &info, out)?;
    let romfs_ref = match romfs {
        Some(path) => Some(intake::RomfsRef { size: std::fs::metadata(&path)?.len(), path, offset: 0 }),
        None => content.romfs.clone(),
    };
    if let Some(r) = &romfs_ref {
        intake::copy_romfs(r, &out.join("romfs.bin"))?;
    }
    println!(
        "{title}: {} functions, {} instructions ({} unsupported), {} jump tables, {} imports resolved, {} unresolved, {} C units",
        report.functions, report.instructions, report.unsupported, report.jump_tables, report.resolved_imports, report.imports, report.units
    );
    for (va, word) in lifted.stats.examples.iter().take(8) {
        println!("  unsupported {word:08x} at {va:#x}");
    }
    println!("wrote {}{}", out.display(), if romfs_ref.is_some() { " (with romfs.bin)" } else { "" });
    let (cc, exe_file, extra): (&str, String, &[&str]) = match compile {
        "none" => return Ok(()),
        "native" => ("cc", exe.clone(), &["-lpthread", "-lm"]),
        "windows" => ("x86_64-w64-mingw32-gcc", format!("{exe}.exe"), &["-static"]),
        other => anyhow::bail!("--compile must be none, native or windows (got {other})"),
    };
    let cc = std::env::var("CC").ok().filter(|_| compile == "native").unwrap_or_else(|| cc.to_string());
    let mut sources: Vec<_> = std::fs::read_dir(out)?
        .filter_map(|e| e.ok().map(|e| e.file_name().to_string_lossy().into_owned()))
        .filter(|n| n.ends_with(".c"))
        .collect();
    sources.sort();
    let status = std::process::Command::new(&cc)
        .current_dir(out)
        .args(["-O2", "-std=gnu11", "-fno-strict-aliasing", "-w", "-o", &exe_file])
        .args(&sources)
        .args(extra)
        .status()
        .with_context(|| format!("run {cc}"))?;
    anyhow::ensure!(status.success(), "{cc} failed");
    println!("built {}", out.join(&exe_file).display());
    Ok(())
}
