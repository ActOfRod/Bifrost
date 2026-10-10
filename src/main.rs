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
