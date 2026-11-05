# Windows renderer — Part C WIP

This branch is `devin/1793903769-windows-renderer`, based on `origin/main` at
`747cafb280333f78a7acf87bea9a26b8bd1df78d`. This is an incomplete work-in-progress,
not a release-ready Windows renderer.

## Current blocker

The last completed `tests/e2e/windows_renderer.sh` run produced a PE32+ executable,
then failed its import assertion with this exact error:

```text
missing vulkan-1.dll in Windows renderer import table
```

That run found `SDL3.dll` and `libshaderc_shared.dll` in the executable's import
table, but not `vulkan-1.dll`. It was a link-time/import-table check only; the
executable was not run under Wine. A follow-up change in
`runtime/las/bf_las_glue.c` now initializes Volk through MinGW's
`__imp_vkGetInstanceProcAddr` import pointer to make the Vulkan loader a direct
PE import. The subsequent E2E rerun was stopped before compilation/linking and
the import check completed, so that attempted fix is unverified. Resume by
rerunning the E2E command below.

## Work by brief-C step

1. **Windows dependencies — implemented and built locally.** The script uses
   SHA-256-verified pins and writes to
   `~/.bifrost/windows-deps/out`. `shaderc_shared.dll` built with the installed
   GCC 10 MinGW toolchain; the pinned llvm-mingw fallback was not used.
2. **Renderer dependency discovery and shared build logic — implemented.**
   `RendererDeps::locate` supports Windows and Android, the CLI accepts
   `--windows-deps`, and the GUI has a Windows dependency field. The Windows
   path uses `VK_USE_PLATFORM_WIN32_KHR`. Rust tests and Clippy have not been run
   against the latest WIP changes.
3. **Windows Vulkan surface and swapchain — no `device.c` change.** Existing
   desktop Windows surface, sizing, minimize, and swapchain-recreation behavior
   was compared with upstream LASRecomp and appeared to be present already.
   Android renderer code was not intentionally changed. Windows runtime
   behavior remains unverified.
4. **Windows SDL host — implemented.** `runtime/las/bf_windows_sdl.c` provides
   the Windows host, including the guest thread, SDL main handling, and
   beside-executable logging. It compiled into the last completed PE build; it
   has not been run under Wine.
5. **Windows NVN pipeline — implemented, link/import verification incomplete.**
   It compiles C and C++ sources, links the renderer dependencies, and copies
   runtime DLLs. A PE was produced by the last completed E2E run, but the
   Vulkan import assertion failed; the most recent source change has not been
   rebuilt.
6. **Windows renderer E2E — added, currently failing/unverified.**
   `tests/e2e/windows_renderer.sh` builds a minimal NVN guest when no fixture is
   available, checks PE imports, then attempts Wine. It has not reached the
   Wine portion because the import assertion failed. No Part C game executable
   has run under Wine.
7. **CI job — implemented, not run.** The workflow builds/caches Windows
   dependencies and invokes the renderer E2E. CI has not been checked for this
   branch.
8. **Documentation — partially updated.** `README.md` and
   `runtime/las/README.md` describe Windows renderer build/validation status.
   This WIP note records the remaining blocker.

## Pins

Dependency output directory: `~/.bifrost/windows-deps/out`.

| Dependency | Pin | SHA-256 |
| --- | --- | --- |
| SDL3 Android/common source | 3.4.16 | `7322236cd12090c3eb40b9728be4d49c76f66ad17d04369584d4ecad5cf77c68` |
| SDL3 official MinGW archive | 3.4.16 | `c7ef65bd72eabac6e5b535411dbd8d5824d0aab24fd62ff8812666b336f18a9c` |
| shaderc | 2026.3 | `ee493ccf1b3038b4ef2fe024664c5eb2dc4bcc1f6b05b33e3909de0e19c81024` |
| Vulkan-Headers | 1.4.350.1 | `6d1bb65e49520344cc0a48af3dc02e993781efff14c7ebdcb8ae9fa23ddf7e83` |
| Vulkan-Loader `vulkan-1.def` | 1.4.350.1 | `9ba339b7f5ee2df28487698a6840ecf095fac58b415c2c89ad9f161e9d316378` |
| VMA | 3.3.0 | `c4f6bbe6b5a45c2eb610ca9d231158e313086d5b1a40c9922cb42b597419b14e` |
| volk | 1.4.350.1 | `078a9411298e4e0f60f5f5398c890783427c25a414619294ca8e69587bbd5eae` |
| Dear ImGui | v1.91.9b | `8e1bbc76c71d74fef2fb85db7e7ca8eba13d6a86623c54992b60162db554ffdb` |
| llvm-mingw fallback | release `20240619` | `27d33157cc252c29ad6f777a96a0d94176fea1b534ff09b5071485def143b90e` |

The llvm-mingw archive is
`https://github.com/mstorsjo/llvm-mingw/releases/download/20240619/llvm-mingw-20240619-ucrt-ubuntu-20.04-x86_64.tar.xz`.
The renderer build currently selects the installed POSIX-thread MinGW GCC/G++
compilers; llvm-mingw is pinned as the dependency-build fallback.

## Verification and resuming

Completed locally:

- `scripts/build_windows_deps.sh` — passed; output included `SDL3.dll`,
  `libSDL3.dll.a`, `libshaderc_shared.dll.a`, `libvulkan-1.dll.a`, and
  `shaderc_shared.dll`.
- `bash -n scripts/build_windows_deps.sh tests/e2e/windows_renderer.sh` — passed.
- `git diff --cached --check` — passed before this WIP commit.
- The last completed E2E run compiled and linked a Windows PE and confirmed
  `SDL3.dll` and `libshaderc_shared.dll` imports, then failed on the missing
  `vulkan-1.dll` import above.

Not completed: `cargo test`, CI-equivalent Clippy,
`tests/e2e/run.sh`, the Android-unit verification path, a passing
`tests/e2e/windows_renderer.sh`, and Wine execution.

To continue on this branch:

```sh
cd /home/ubuntu/repos/Bifrost
scripts/build_windows_deps.sh
cargo test
cargo clippy --workspace --all-targets -- -D warnings
tests/e2e/run.sh
tests/e2e/windows_renderer.sh
git diff --check
```

The dependency script's output remains at
`/home/ubuntu/.bifrost/windows-deps/out`; do not delete it before resuming.
