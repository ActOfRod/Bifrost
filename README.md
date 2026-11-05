# Bifrost

Bifrost recompiles decrypted Nintendo Switch games for Windows and Android, modernizes older Windows games, and provides an Android library for native apps and Windows games.

Bifrost has four targets. Switch → Windows, Switch → Android, Windows → Android and the Modernizer are in progress.

| Target | What it does | Progress |
|---|---|---|
| **Switch → Windows** | Recompiles a Switch title into a Windows `.exe` | `█████▌░░░░` 5.5 / 10 stages |
| **Switch → Android** | Recompiles a Switch title into an arm64 APK with a Vulkan renderer | `█████░░░░░` 5.5 / 11 stages |
| **Windows → Android** | A launcher for native Android apps and Windows games imported as `.bifrost` packages | `███████░░░` 5 / 7 stages |
| **Modernizer** | Updates an old Windows game (XP / Windows 7 era) to run better on current Windows and hardware | `███▌░░░░░░` 2.5 / 7 stages |

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
| Graphics: NVN → Vulkan on Windows | 🟡 Cross-builds and links against SDL3, shaderc and the Vulkan loader; renderer smoke test checks PE imports, not real-game rendering |
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

The launcher is a landscape Big Picture-style Android library for native apps and Windows games packaged on a PC. It installs or lists Android apps, auto-detects Bifrost ports, imports `.bifrost` packages, and launches PC games through Winlator. Runtime integration is implemented but has not been tested on a device; the launcher does not recompile Windows games.

| Stage | Status |
|---|---|
| Launcher app with Big Picture-style library | ✅ |
| Native port library (install APK / installed apps / auto-detect Bifrost ports) | ✅ |
| `.bifrost` packaging on PC (`bifrost pack` or Recomp → Windows PC game in the GUI) | ✅ |
| Import `.bifrost` into the launcher | ✅ |
| Windows runtime (Wine + Box64 + Mesa + DXVK) | ✅ Implemented; not tested on a device |
| Controller/touch input mapping for PC games | ⬜ |
| Validated on a real title | ⬜ |

#### Android runtime and source checkout

The Android app ID is `com.actofrod`, and its arm64-v8a-only APK is about 250 MB. Winlator is included as an LGPL-2.1 submodule pinned at `2c33741d50c5daab0521a098be5c969f63b9e887`. Bifrost distributes modified Winlator build outputs: `scripts/prepare_winlator.py` rewrites the baked app-data path and provider/action identifiers, then repacks the `.tzst` runtime assets; the source submodule itself remains unmodified. The pinned source and preparation script are the corresponding source for those build outputs. Winlator includes Wine, Box64, Mesa Turnip/Zink/VirGL, DXVK/D8VK/VKD3D, cnc-ddraw, and Termux glibc patches.

Clone with submodules, or initialize them after cloning:

```sh
git clone --recursive https://github.com/ActOfRod/Bifrost.git
# For an existing checkout:
git submodule update --init --recursive
```

Building the APK requires the `zstd` CLI. Run the preparation script once before Gradle, and again after any Winlator submodule bump:

```sh
python3 BifrostAndroid/scripts/prepare_winlator.py
```

### Modernizer

First version built: pick **Modernize** in the desktop app, or run `bifrost modernize`. It has only been tested on small test games built with MinGW, not on a real title yet. The Bifrost FSR Vulkan layer has a lavapipe smoke test; real-GPU and Windows rendering are not validated yet.

The Modernizer takes an older Windows game (Windows XP / Windows 7 era) and rebuilds or wraps its files so that it runs properly on current Windows. It can also add newer technology the game never shipped with, such as upscaling. The reference title is ***The Hobbit* (2003)**.

| Stage | Status |
|---|---|
| Intake: the game's install folder (`.exe`, DLLs, data files) and which graphics, audio and input APIs it imports | ✅ main `.exe` detected automatically (installers and uninstallers skipped); imports read from the `.exe` and the DLLs beside it |
| Compatibility fixes: modern resolutions and widescreen, high-DPI, frame-rate caps, timing bugs on fast CPUs | 🟡 high-DPI override, fullscreen optimizations off, XP SP3 mode, frame-rate cap, Large Address Aware patch; no widescreen or timing fixes yet |
| Graphics: older graphics APIs translated to a modern API (Vulkan or D3D11/12) | 🟡 Direct3D 8–11 → Vulkan through DXVK 3.1.1; 2D DirectDraw through cnc-ddraw 7.1.0.0 (Direct3D 7 or older is skipped); OpenGL and Glide not yet |
| Upscaling: spatial upscalers (FSR 1, NVIDIA Image Scaling) | 🟡 FSR 1 through cnc-ddraw for DirectDraw and the Bifrost Vulkan layer for Direct3D 8–11 (lavapipe only; real GPU/Windows rendering not validated); NIS not yet |
| Upscaling: NVIDIA DLSS | ⬜ |
| Input and audio: XInput controller support, a replacement for legacy DirectSound/EAX audio | ⬜ |
| Validated on *The Hobbit* (2003) | ⬜ |

The original install is never changed. The Modernizer copies the game to an output folder and changes only the copy:
- **Large Address Aware:** sets the flag on a 32-bit `.exe` so it can use 4 GB of memory instead of 2 GB (the PE checksum is updated too).
- **DXVK:** the matching 32- or 64-bit DLLs go next to the `.exe`, with DXVK's license. The release is downloaded once from GitHub, checked against its SHA-256 and cached; `--dxvk` uses a local copy instead.
- **cnc-ddraw:** for 32-bit DirectDraw games, installs v7.1.0.0 (MIT). Its GitHub release is pinned to SHA-256 `0b13ab89a64c9918189b1dadd449ef6ed3cb3b7b19cabd96d8adbd95505bb908` and cached like DXVK; `--ddraw <zip|dir>` selects a local release and `--no-ddraw` leaves DirectDraw alone. Direct3D 7 or older is detected and skipped because cnc-ddraw cannot render it.
- **FSR 1:** `--fsr` keeps the cnc-ddraw shader for DirectDraw and installs the Bifrost Vulkan layer for Direct3D 8–11 games using DXVK. For Direct3D, launch with **Play with FSR.bat**. Its `bifrost_fsr.ini` controls `sharpness` (0 is sharpest), `scale` (`auto` passes through; set a number ≥ 1.0 to upscale), and `log`; on Windows, `fake_mode_switch=true` (default) emulates display mode switches without changing the monitor mode. The layer is selected from `--fsr-layer <dir>`, `BIFROST_FSR_LAYER`, or the `fsr-layer` folder beside `bifrost.exe`. Linux lavapipe is tested; real-GPU and Windows rendering are not validated yet.
  Direct3D FSR passes through when a window has no fixed extent (e.g. Wayland) or no graphics queue can present to its surface.
- **`dxvk.conf`:** anisotropic filtering and the frame-rate cap.
- **`bifrost_compat.bat`:** writes the Windows compatibility settings for the copied `.exe` (`bifrost_compat_undo.bat` removes them). The app can also apply them directly on Windows.
- **`bifrost_modernize.txt`:** a report of what was detected and changed.

FSR 1 is available for DirectDraw and DXVK-backed Direct3D 8–11 games in the app and command line. DLSS is not available yet.

**About DLSS.** Copying NVIDIA's DLSS DLL (`nvngx_dlss.dll`) into the game folder does nothing on its own. The game has to call DLSS every frame and pass it a depth buffer, per-pixel motion vectors and camera jitter. A 2003 game doesn't produce motion vectors, so the Modernizer's graphics layer would have to generate them before DLSS can work. Spatial upscalers (FSR 1, NIS) only need the final image; FSR 1 is supported through cnc-ddraw for DirectDraw and the Bifrost Vulkan layer for DXVK games. The DLSS DLL comes from NVIDIA's DLSS SDK under NVIDIA's license, and DLSS needs an NVIDIA RTX GPU.

## Releases

A `vX.Y.Z` tag creates a GitHub Release containing `Bifrost-Windows-<v>.zip` and `Bifrost-Android-<v>.apk`. To sign Android releases with a stable key, generate one once:

```sh
keytool -genkeypair -v -keystore bifrost-release.jks -alias bifrost -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 bifrost-release.jks
```

Store the encoded output as `BIFROST_ANDROID_KEYSTORE_B64`, and the keystore password, alias, and key password as `BIFROST_ANDROID_KEYSTORE_PASSWORD`, `BIFROST_ANDROID_KEY_ALIAS`, and `BIFROST_ANDROID_KEY_PASSWORD`. Without these secrets, the APK uses a temporary key and installing a later update requires uninstalling the previous APK first.

## Using Bifrost

### Download

Each GitHub Release (tags `v*`) has a Windows zip with `bifrost-gui.exe` and `bifrost.exe`, plus the Android launcher APK, built by `.github/workflows/release.yml`. Pull requests and manual runs upload both as workflow artifacts. Recomp's compile step still needs a C toolchain (MinGW-w64 or clang, or the Android SDK/NDK) installed.

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
2. Choose the options: DXVK or cnc-ddraw, FSR 1 for DirectDraw or Direct3D 8–11, anisotropic filtering, frame-rate cap, Large Address Aware and the Windows compatibility settings.
3. Choose an empty output folder (or the previous output of the same game, which is updated in place so saves and edited settings are kept) and press **Begin Modernization**.

### Command line

```
bifrost build game.nsp --update update.nsp --patch mods/ --dlc dlc/ -o out --compile windows
bifrost build game.nsp --romfs romfs/ -o out --compile android --android-sdk ~/android-sdk --android-deps ~/.bifrost/android-deps/out
```

```
bifrost modernize "C:\Games\The Hobbit" --scan
bifrost modernize "C:\Games\The Hobbit" -o "C:\Games\The Hobbit (Modernized)" --max-fps 60
```

- `modernize` options: `--exe`, `--no-dxvk`, `--dxvk <release.tar.gz|folder>`, `--ddraw <zip|dir>`, `--no-ddraw`, `--fsr` (DirectDraw or DXVK-backed Direct3D 8–11), `--fsr-layer <dir>`, `--anisotropy 0|2|4|8|16`, `--max-fps`, `--no-laa`, `--no-high-dpi`, `--keep-fullscreen-optimizations`, `--xp-mode`, `--apply-compat`.
- `bifrost pack <GAME> [--exe <path>] [-o|--out <file.bifrost>] [--title <text>] [--cover <image>] [--force]` writes a `.bifrost` package for the Android launcher; `--force` replaces an existing package.
- `--compile` takes `none`, `native`, `windows` (MinGW-w64) or `android` (NDK; produces a signed APK).
- Titles that draw through NVN get the Vulkan renderer when `--android-deps` points at the `out/` directory from `scripts/build_android_deps.sh`, or `--windows-deps` points at the `out/` directory from `scripts/build_windows_deps.sh`. `BIFROST_ANDROID_DEPS` and `BIFROST_WINDOWS_DEPS` may point at either that directory or its parent build directory. The Windows renderer is cross-built and its SDL3, shaderc and Vulkan loader imports are checked in E2E; real-title rendering has not yet been validated.
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
- The Modernizer check (`tests/e2e/modernize.sh`) builds 32-bit Direct3D 8 and 64-bit Direct3D 11 games with MinGW and modernizes both, including architecture-specific FSR layer installation and cleanup. The lavapipe Vulkan layer test (`tests/e2e/fsr_layer.sh`) exercises scaled and pass-through swapchains with validation enabled.
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
