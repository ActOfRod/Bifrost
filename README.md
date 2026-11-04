# Bifrost

Bifrost is a universal recompilation tool. You give it a game for one platform, plus any updates, patches and DLC, and it rebuilds that game as a native program for another platform. It recompiles the code ahead of time instead of emulating it.

Bifrost has four targets. Switch → Windows, Switch → Android and the Modernizer are in progress; Windows → Android is planned.

| Target | What it does | Progress |
|---|---|---|
| **Switch → Windows** | Recompiles a Switch title into a Windows `.exe` | `█████▌░░░░` 5.5 / 10 stages |
| **Switch → Android** | Recompiles a Switch title into an arm64 APK with a Vulkan renderer | `█████░░░░░` 5.5 / 11 stages |
| **Windows → Android** | Recompiles an x86/x64 Windows game into an arm64 APK | `░░░░░░░░░░` 0 / 8 stages |
| **Modernizer** | Updates an old Windows game (XP / Windows 7 era) to run better on current Windows and hardware | `███░░░░░░░` 2 / 7 stages |

The stage counts come from the checklists below: ✅ counts as 1, 🟡 (partly done) counts as ½ and ⬜ counts as 0. "Validated on a real title" is its own stage, and no target has reached it yet. All testing so far has used small test programs written for Bifrost.

## Conversion progress

### Switch → Windows

| Stage | Status |
|---|---|
| Intake: NSP, XCI, plaintext NCA, ExeFS, loose NSO/NRO/ELF | ✅ |
| Updates, patches (IPS, IPS32, pchtxt), RomFS and DLC layering | ✅ |
| Multi-module linking (`rtld`, `main`, `subsdk*`, `sdk`) | ✅ |
| AArch64 → C lifting: integer, FP, SIMD, atomics, crypto | ✅ |
| C project generation and compilation (MinGW-w64 or clang) | ✅ |
| Horizon kernel and services | 🟡 The kernel is mostly done; many services are stubs that return success |
| Graphics: NVN → Vulkan on Windows | ⬜ The renderer only builds for Android so far |
| Audio output | ⬜ |
| Controller input | ⬜ |
| Validated on a real title | ⬜ |

### Switch → Android

| Stage | Status |
|---|---|
| Intake, updates, patches, RomFS and DLC (same as above) | ✅ |
| Linking and AArch64 → C lifting (same as above) | ✅ |
| NDK build and APK packaging and signing | ✅ |
| Android host: SDL3 activity, asset unpacking, logcat output | ✅ |
| Horizon kernel and services | 🟡 Same as above |
| Graphics: NVN → Vulkan renderer (vendored from LASRecompAndroid) | 🟡 Builds into the APK and starts Vulkan and the swapchain; drawing hasn't been tested on real game output |
| Maxwell shader → SPIR-V translation | 🟡 Vendored and compiled in; not yet run on real shaders |
| Touch and gamepad input | ⬜ |
| Audio output | ⬜ |
| Per-title integration (first target: *The Legend of Zelda: Link's Awakening*) | ⬜ Needs the game's decrypted ExeFS and RomFS |
| Validated on a real title | ⬜ |

### Windows → Android

Planned. Nothing is built yet.

| Stage | Status |
|---|---|
| PE (`.exe` / `.dll`) intake, including the game's own DLLs | ⬜ |
| x86 / x64 → C lifting | ⬜ |
| Win32 API layer (files, threads, windows, registry) | ⬜ |
| Direct3D → Vulkan translation | ⬜ |
| DirectInput / XInput → touch and gamepad | ⬜ |
| DirectSound / XAudio → Android audio | ⬜ |
| APK packaging (reuses the Switch → Android packager) | ⬜ |
| Validated on a real title | ⬜ |

### Modernizer

First version built: pick **Modernize** in the desktop app, or run `bifrost modernize`. It has only been tested on small test games built with MinGW, not on a real title yet.

The Modernizer takes an older Windows game (Windows XP / Windows 7 era) and rebuilds or wraps its files so that it runs properly on current Windows. It can also add newer technology the game never shipped with, such as upscaling. The reference title is ***The Hobbit* (2003)**.

| Stage | Status |
|---|---|
| Intake: the game's install folder (`.exe`, DLLs, data files) and which graphics, audio and input APIs it imports | ✅ main `.exe` detected automatically (installers and uninstallers skipped); imports read from the `.exe` and the DLLs beside it |
| Compatibility fixes: modern resolutions and widescreen, high-DPI, frame-rate caps, timing bugs on fast CPUs | 🟡 high-DPI override, fullscreen optimizations off, XP SP3 mode, frame-rate cap, Large Address Aware patch; no widescreen or timing fixes yet |
| Graphics: older Direct3D versions translated to a modern API (Vulkan or D3D11/12) | 🟡 Direct3D 8, 9, 10 and 11 → Vulkan through DXVK 3.1.1, with forced anisotropic filtering; DirectDraw, OpenGL and Glide not yet |
| Upscaling: spatial upscalers (FSR 1, NVIDIA Image Scaling) | ⬜ |
| Upscaling: NVIDIA DLSS | ⬜ |
| Input and audio: XInput controller support, a replacement for legacy DirectSound/EAX audio | ⬜ |
| Validated on *The Hobbit* (2003) | ⬜ |

The original install is never changed. The Modernizer copies the game to an output folder and changes only the copy:
- **Large Address Aware:** sets the flag on a 32-bit `.exe` so it can use 4 GB of memory instead of 2 GB (the PE checksum is updated too).
- **DXVK:** the matching 32- or 64-bit DLLs go next to the `.exe`, with DXVK's license. The release is downloaded once from GitHub, checked against its SHA-256 and cached; `--dxvk` uses a local copy instead.
- **`dxvk.conf`:** anisotropic filtering and the frame-rate cap.
- **`bifrost_compat.bat`:** writes the Windows compatibility settings for the copied `.exe` (`bifrost_compat_undo.bat` removes them). The app can also apply them directly on Windows.
- **`bifrost_modernize.txt`:** a report of what was detected and changed.

In the app, FSR 1 and DLSS are shown but can't be selected yet.

**About DLSS.** Copying NVIDIA's DLSS DLL (`nvngx_dlss.dll`) into the game folder does nothing on its own. The game has to call DLSS every frame and pass it a depth buffer, per-pixel motion vectors and camera jitter. A 2003 game doesn't produce motion vectors, so the Modernizer's graphics layer would have to generate them before DLSS can work. Spatial upscalers (FSR 1, NIS) only need the final image, so they will come first. The DLSS DLL comes from NVIDIA's DLSS SDK under NVIDIA's license, and DLSS needs an NVIDIA RTX GPU.

## Using Bifrost

### Download

Each GitHub Release (tags `v*`) has a Windows zip with `bifrost-gui.exe` (the desktop app) and `bifrost.exe` (the command line), built by `.github/workflows/release.yml`. Every pull request's **Release** run uploads the same zip as a workflow artifact. Recomp's compile step still needs a C toolchain (MinGW-w64 or clang, or the Android SDK/NDK) installed.

### Desktop app

```
cargo run --release -p bifrost-gui
```

First choose the task: **Recomp** or **Modernize**.

**Recomp**
1. Pick the input system (Switch for now) and the output system (Windows or Android).
2. Choose the base game. You can also add updates, patches and a RomFS.
3. Turn on the DLC toggle to attach dumped DLC.
4. Press **Begin Recomp**. A progress bar and a log follow the build from intake through linking, lifting, code generation and compiling or packaging.

**Modernize**
1. Choose the game's install folder or its `.exe`. The app shows the detected game executable (you can pick another) and the APIs it uses.
2. Choose the options: DXVK, anisotropic filtering, frame-rate cap, Large Address Aware and the Windows compatibility settings.
3. Choose an empty output folder and press **Begin Modernization**.

### Command line

```
bifrost build game.nsp --update update.nsp --patch mods/ --dlc dlc/ -o out --compile windows
bifrost build game.nsp --romfs romfs/ -o out --compile android --android-sdk ~/android-sdk --android-deps ~/.bifrost/android-deps/out
```

```
bifrost modernize "C:\Games\The Hobbit" --scan
bifrost modernize "C:\Games\The Hobbit" -o "C:\Games\The Hobbit (Modernized)" --max-fps 60
```

- `modernize` options: `--exe`, `--no-dxvk`, `--dxvk <release.tar.gz|folder>`, `--anisotropy 0|2|4|8|16`, `--max-fps`, `--no-laa`, `--no-high-dpi`, `--keep-fullscreen-optimizations`, `--xp-mode`, `--apply-compat`.
- `--compile` takes `none`, `native`, `windows` (MinGW-w64) or `android` (NDK; produces a signed APK).
- Titles that draw through NVN get the Vulkan renderer when `--android-deps` (or `BIFROST_ANDROID_DEPS`) points at the output of `scripts/build_android_deps.sh`. That output includes SDL3, shaderc, the Vulkan headers, VMA and volk.
- `inspect`, `recompile` and `run` are older developer commands that still use the original interpreter path.

## Keys and encryption

Bifrost does not handle console keys, title keys or firmware, and it never decrypts anything. NCAs must already be plaintext, decrypted with your own tools from hardware you own (for example `hactool --plaintext`). If an NCA is encrypted, Bifrost rejects it and explains why. An update RomFS stored as a BKTR patch must be merged with the base RomFS beforehand and passed as `--romfs`. The generated project is a local derivative of your input, so do not redistribute it.

## How the Switch pipeline works

1. **Intake** (`src/intake.rs`)
   - Reads PFS0 (NSP), HFS0 (XCI secure partition), NCA3/NCA2 headers and their sections, ExeFS PFS0 images, and `exefs/` directories.
   - Layering: updates are applied in order, and an update's ExeFS replaces the base ExeFS, matching how Horizon does it. A full RomFS from a later layer replaces the earlier one. `--romfs` overrides both. DLC is packaged alongside the RomFS.
   - Title name and title ID come from `main.npdm` (META/ACI0) and the NCA program ID.
2. **Patches**: IPS, IPS32 (Atmosphère exefs_patches, matched to a module by the build-ID file name) and `.pchtxt` (`@nsobid`, `@flag offset_shift`, `@enabled`/`@stop`).
3. **Link** (`src/link.rs`)
   - Modules are laid out in Horizon load order (`rtld`, `main`, `subsdk0..9`, `sdk`, others) inside a reserved guest address window, with each module aligned to 2 MiB.
   - Each module is rebased through its relocations.
   - Imports are resolved across modules. Any that stay unresolved get unique HLE sentinel addresses.
4. **Code discovery and lifting** (`src/lift/`)
   - Entry points come from module entries, init arrays, exported functions, code pointers anywhere in the module image, and `ADRP+ADD` address materializations.
   - `cfg.rs` recovers functions and blocks, and detects compiler jump tables.
   - `decode.rs` (integer, system, memory and atomics) and `simd.rs` (scalar FP, AdvSIMD, AES/SHA/CRC, structure loads and stores) turn each instruction into C.
5. **Code generation** (`src/codegen.rs`, `src/project.rs`, `src/android.rs`)
   - Writes one C function per guest function, split across `lifted_NNN.c` units.
   - Writes a sorted dispatch table (`bf_dispatch.c`) and the image metadata (`bf_image.c`, `guest_image.bin`).
   - Writes the runtime sources plus a `Makefile`, `CMakeLists.txt` and `build.bat`, or an NDK build and APK for Android.
6. **Runtime** (`runtime/`)
   - `bf_cpu.h`, `bf_fp.h` and `bf_crypto.h`: guest CPU state and the instruction helpers. A guest address is the same as the host address inside a reserved window. This is what lets the renderer read guest memory directly.
   - `bf_main.c`: loader, entry ABI, indirect-call dispatch, the HLE function registry, and logging of missing code.
   - `bf_kernel.c`: Horizon SVC HLE, covering memory/heap/QueryMemory, handles, threads on host threads, mutex/condvar/address arbitration, events, WaitSynchronization and GetInfo.
   - `bf_ipc.c`: HIPC/CMIF parsing, domains, and service stubs (sm, fsp-srv with RomFS reads, time, hid, vi, acc, applet, audio, and others).
   - `bf_platform.c`: the Win32 and POSIX layer. `bf_android.c` is the Android host for builds without the renderer.
   - `las/`: the NVN → Vulkan renderer and its Android SDL3 host, vendored from [LASRecompAndroid](https://github.com/mamcjr/LASRecompAndroid) (MIT; see `runtime/las/README.md`).

If the program reaches code that static discovery missed, it writes the address to `bifrost_missing.txt` and stops. Rebuild with `--extra-entries bifrost_missing.txt` and repeat as needed.

## Testing

- The CPU core is checked against Unicorn instruction by instruction (`tests/diff/diff.py`).
- A full program check (`tests/e2e/run.sh`) compiles a freestanding AArch64 guest. It covers recursion, switch tables, FP, auto-vectorized SIMD, indirect calls and atomics.
  - The guest is run through Bifrost four ways: as a loose ELF, an ExeFS PFS0, an NSP, and a Windows build under Wine.
  - Output and exit code must match `qemu-aarch64` exactly.
- The Modernizer check (`tests/e2e/modernize.sh`) builds a 32-bit Direct3D 8 game (drawing from an engine DLL) and a 64-bit Direct3D 11 game with MinGW and modernizes both. It checks the LAA flag and checksum, the DXVK DLLs, `dxvk.conf`, the compatibility script, and that the originals are untouched.
- No commercial title has been run yet.

```
cargo test && cargo clippy --workspace --all-targets
python3 tests/diff/diff.py --count 2000 dpimm dpreg ldst fp simd   # needs unicorn, capstone, clang
tests/e2e/run.sh   # needs aarch64-linux-gnu-gcc, qemu-aarch64; optional mingw-w64 + wine
tests/e2e/modernize.sh   # needs mingw-w64; downloads DXVK once
```

## References

These projects informed Bifrost's design:
- Game-specific recompilations: [LASRecomp](https://github.com/peiikai/LASRecomp), [LASRecompAndroid](https://github.com/mamcjr/LASRecompAndroid), [totk-pc-recomp](https://github.com/valentderah/totk-pc-recomp) and [bloodborne_pc](https://github.com/Supermedo/bloodborne_pc).
- Container tooling: [NSC_BUILDER](https://github.com/julesontheroad/NSC_BUILDER).
- Emulation: [shadPS4Plus](https://github.com/AzaharPlus/shadPS4Plus).
