//! Writes a self-contained C project (generated code + runtime + guest image) for a linked program.

use std::fmt::Write as _;
use std::fs;
use std::io;
use std::path::Path;

use crate::codegen;
use crate::lift::cfg::Lifted;
use crate::link::Program;

pub const RUNTIME_FILES: &[(&str, &str)] = &[
    ("bf_cpu.h", include_str!("../runtime/bf_cpu.h")),
    ("bf_fp.h", include_str!("../runtime/bf_fp.h")),
    ("bf_crypto.h", include_str!("../runtime/bf_crypto.h")),
    ("bf_runtime.h", include_str!("../runtime/bf_runtime.h")),
    ("bf_main.c", include_str!("../runtime/bf_main.c")),
    ("bf_kernel.c", include_str!("../runtime/bf_kernel.c")),
    ("bf_ipc.c", include_str!("../runtime/bf_ipc.c")),
    ("bf_platform.c", include_str!("../runtime/bf_platform.c")),
];

#[derive(Debug, Default)]
pub struct BuildReport {
    pub functions: usize,
    pub instructions: usize,
    pub unsupported: usize,
    pub jump_tables: usize,
    pub units: usize,
    pub imports: usize,
    pub resolved_imports: usize,
    pub segments: usize,
    pub image_bytes: usize,
}

pub struct ProjectInfo<'a> {
    pub title: &'a str,
    pub title_id: u64,
    pub exe_name: &'a str,
}

fn c_str(s: &str) -> String {
    let mut out = String::from("\"");
    for ch in s.chars() {
        match ch {
            '"' | '\\' => {
                out.push('\\');
                out.push(ch);
            }
            c if c.is_ascii_graphic() || c == ' ' => out.push(c),
            c => {
                let mut buf = [0u8; 4];
                for b in c.encode_utf8(&mut buf).bytes() {
                    let _ = write!(out, "\\{b:03o}");
                }
            }
        }
    }
    out.push('"');
    out
}

pub fn write_project(program: &Program, lifted: &Lifted, info: &ProjectInfo, out: &Path) -> io::Result<BuildReport> {
    fs::create_dir_all(out)?;
    for (name, text) in RUNTIME_FILES {
        fs::write(out.join(name), text)?;
    }
    let gen = codegen::generate(lifted);
    fs::write(out.join("bf_funcs.h"), &gen.header)?;
    for (i, unit) in gen.units.iter().enumerate() {
        fs::write(out.join(format!("lifted_{i:03}.c")), unit)?;
    }
    fs::write(out.join("bf_dispatch.c"), &gen.dispatch)?;

    let segments = program.segments();
    let mut blob = Vec::new();
    let mut image = String::from("#include \"bf_runtime.h\"\n\nconst BfSegment bf_segments[] = {\n");
    for s in &segments {
        let off = blob.len();
        blob.extend_from_slice(&s.bytes);
        let _ = writeln!(
            image,
            "    {{0x{:x}ull, 0x{:x}ull, {off}ull, {}ull, {}, {}, {}}},",
            s.va,
            s.size,
            s.bytes.len(),
            s.perm,
            s.mem_type,
            c_str(&s.module)
        );
    }
    if segments.is_empty() {
        image.push_str("    {0, 0, 0, 0, 0, 0, \"\"},\n");
    }
    let _ = writeln!(image, "}};\nconst size_t bf_segment_count = {};", segments.len());
    let _ = writeln!(image, "const uint64_t bf_entry_point = 0x{:x}ull;", program.entry);
    let _ = writeln!(image, "const int bf_entry_is_nro = {};", program.is_nro as i32);
    let _ = writeln!(image, "const char bf_title_name[] = {};", c_str(info.title));
    let _ = writeln!(image, "const uint64_t bf_title_id = 0x{:016x}ull;", info.title_id);
    image.push_str("const BfImport bf_imports[] = {\n");
    for (sentinel, name) in &program.imports {
        let _ = writeln!(image, "    {{0x{sentinel:x}ull, {}}},", c_str(name));
    }
    if program.imports.is_empty() {
        image.push_str("    {0, \"\"},\n");
    }
    let _ = writeln!(image, "}};\nconst size_t bf_import_count = {};", program.imports.len());
    fs::write(out.join("bf_image.c"), image)?;
    fs::write(out.join("guest_image.bin"), &blob)?;

    let exe = info.exe_name;
    fs::write(
        out.join("CMakeLists.txt"),
        format!(
            "cmake_minimum_required(VERSION 3.16)\nproject({exe} C)\nfile(GLOB BF_SOURCES ${{CMAKE_CURRENT_SOURCE_DIR}}/*.c)\n\
             add_executable({exe} ${{BF_SOURCES}})\nset_property(TARGET {exe} PROPERTY C_STANDARD 11)\n\
             target_compile_options({exe} PRIVATE -std=gnu11 -fno-strict-aliasing -Wno-unused-label -Wno-unused-variable)\n\
             if(NOT WIN32)\n  find_package(Threads REQUIRED)\n  target_link_libraries({exe} Threads::Threads m)\nendif()\n\
             add_custom_command(TARGET {exe} POST_BUILD COMMAND ${{CMAKE_COMMAND}} -E copy_if_different \
             ${{CMAKE_CURRENT_SOURCE_DIR}}/guest_image.bin $<TARGET_FILE_DIR:{exe}>)\n"
        ),
    )?;
    fs::write(
        out.join("Makefile"),
        format!(
            "CC ?= cc\nCFLAGS ?= -O2\nBF_FLAGS = -std=gnu11 -fno-strict-aliasing -w\nSRC = $(wildcard *.c)\n\n\
             {exe}: $(SRC) $(wildcard *.h)\n\t$(CC) $(CFLAGS) $(BF_FLAGS) -o $@ $(SRC) -lpthread -lm\n\n\
             windows: $(SRC) $(wildcard *.h)\n\tx86_64-w64-mingw32-gcc $(CFLAGS) $(BF_FLAGS) -static -o {exe}.exe $(SRC)\n\n\
             .PHONY: windows\n"
        ),
    )?;
    fs::write(
        out.join("build.bat"),
        format!(
            "@echo off\r\nrem Requires clang (LLVM for Windows) or MinGW-w64 gcc on PATH.\r\n\
             where clang >nul 2>nul && (clang -O2 -std=gnu11 -fno-strict-aliasing -w -o {exe}.exe *.c & goto :eof)\r\n\
             gcc -O2 -std=gnu11 -fno-strict-aliasing -w -static -o {exe}.exe *.c\r\n"
        ),
    )?;
    Ok(BuildReport {
        functions: lifted.functions.len(),
        instructions: lifted.insns.len(),
        unsupported: lifted.stats.unsupported,
        jump_tables: lifted.stats.jump_tables,
        units: gen.units.len(),
        imports: program.imports.len(),
        resolved_imports: program.resolved_imports,
        segments: segments.len(),
        image_bytes: blob.len(),
    })
}
