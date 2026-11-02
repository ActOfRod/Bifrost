# Bifrost

Bifrost is a static recompiler for Nintendo Switch titles. You give it a title (NSP, XCI, plaintext NCA, an ExeFS directory, or loose NSO/NRO/ELF files), plus any updates and patches. It produces a self-contained C project that compiles into a native program: a Windows `.exe` through MinGW-w64 or clang, or a Linux binary.

```
bifrost build game.nsp --update update.nsp --patch mods/ -o out --compile windows
```

## Pipeline

1. **Intake** (`src/intake.rs`)
   - Reads PFS0 (NSP), HFS0 (XCI secure partition), NCA3/NCA2 headers and their sections, ExeFS PFS0 images, and `exefs/` directories.
   - Layering: updates are applied in order, and an update's ExeFS replaces the base ExeFS, matching how Horizon does it. A full RomFS from a later layer replaces the earlier one. `--romfs` overrides both.
   - Title name and title ID come from `main.npdm` (META/ACI0) and the NCA program ID.
2. **Patches**: IPS, IPS32 (Atmosphère exefs_patches, matched to a module by the build-ID file name) and `.pchtxt` (`@nsobid`, `@flag offset_shift`, `@enabled`/`@stop`).
3. **Link** (`src/link.rs`)
   - Modules are laid out in Horizon load order (`rtld`, `main`, `subsdk0..9`, `sdk`, others) starting at `0x08000000`, with each module aligned to 2 MiB.
   - Each module is rebased through its relocations.
   - Imports are resolved across modules. Any that stay unresolved get unique HLE sentinel addresses.
4. **Code discovery and lifting** (`src/lift/`)
   - Entry points come from module entries, init arrays, exported functions, code pointers anywhere in the module image, and `ADRP+ADD` address materializations.
   - `cfg.rs` recovers functions and blocks, and detects compiler jump tables.
   - `decode.rs` (integer, system, memory and atomics) and `simd.rs` (scalar FP, AdvSIMD, AES/SHA/CRC, structure loads and stores) turn each instruction into C.
5. **Code generation** (`src/codegen.rs`, `src/project.rs`)
   - Writes one C function per guest function, split across `lifted_NNN.c` units.
   - Writes a sorted dispatch table (`bf_dispatch.c`) and the image metadata (`bf_image.c`, `guest_image.bin`).
   - Writes the runtime sources plus a `Makefile`, `CMakeLists.txt` and `build.bat`.
6. **Runtime** (`runtime/`)
   - `bf_cpu.h`, `bf_fp.h` and `bf_crypto.h`: guest CPU state and the instruction helpers.
   - `bf_main.c`: loader, entry ABI, indirect-call dispatch, and logging of missing code.
   - `bf_kernel.c`: Horizon SVC HLE, covering memory/heap/QueryMemory, handles, threads on host threads, mutex/condvar/address arbitration, events, WaitSynchronization and GetInfo.
   - `bf_ipc.c`: HIPC/CMIF parsing, domains, and service stubs (sm, fsp-srv with RomFS reads, time, hid, vi, acc, applet, audio, and others).
   - `bf_platform.c`: the Win32 and POSIX layer.

If the program reaches code that static discovery missed, it writes the address to `bifrost_missing.txt` and stops. Rebuild with `--extra-entries bifrost_missing.txt` and repeat as needed.

## Keys and encryption

Bifrost does not handle console keys, title keys or firmware, and it never decrypts anything. NCAs must already be plaintext, decrypted with your own tools from hardware you own (for example `hactool --plaintext`). If an NCA is encrypted, Bifrost rejects it and explains why. An update RomFS stored as a BKTR patch must be merged with the base RomFS beforehand and passed as `--romfs`. The generated project is a local derivative of your input, so do not redistribute it.

## Status

- The CPU core is checked against Unicorn instruction by instruction (`tests/diff/diff.py`).
- A full program check (`tests/e2e/run.sh`) compiles a freestanding AArch64 guest. It covers recursion, switch tables, FP, auto-vectorized SIMD, indirect calls and atomics.
  - The guest is run through Bifrost four ways: as a loose ELF, an ExeFS PFS0, an NSP, and a Windows build under Wine.
  - Output and exit code must match `qemu-aarch64` exactly.
- Not done yet:
  - GPU: no NVN/nvdrv to Vulkan/D3D translation, so commercial games will not render.
  - Many services are still stubs that return success. Add real behavior as titles need it.
  - The legacy commands (`inspect`, `recompile`, `run`) still use the original interpreter/emitter path.

## Development

```
cargo test && cargo clippy --all-targets
python3 tests/diff/diff.py --count 2000 dpimm dpreg ldst fp simd   # needs unicorn, capstone, clang
tests/e2e/run.sh   # needs aarch64-linux-gnu-gcc, qemu-aarch64; optional mingw-w64 + wine
```
