# Vendored NVN → Vulkan renderer (LASRecomp / LASRecompAndroid)

`nvn/` is the NVN HLE layer and Vulkan backend from [LASRecompAndroid](https://github.com/mamcjr/LASRecompAndroid)
(a port of [LASRecomp](https://github.com/peiikai/LASRecomp)), MIT licensed — see `nvn/LICENSE.LASRecomp`.
`third_party/smaa` is SMAA (MIT, see its LICENSE.txt); `third_party/imgui/imstb_truetype.h` is stb_truetype (public domain / MIT).

The headers at this level (`rt.h`, `hle.h`, `cpu.h`, ...) are LAS's runtime interface; Bifrost implements it on top of
its own runtime (`cpu.h` maps `Cpu` to `BfCpu`). Local changes to the vendored files:
- `nvn/nvn.h`: `RETF` clears `v[0]` with `memset` (Bifrost's vector register type is a union).
- `nvn/texpatch.c`: stb_truetype include path.
- `nvn/vulkan/android_driver.c` is kept as `.adrenotools` (custom driver loading; not built yet).

Built into Android APKs for titles that load NVN (`bifrost build ... --compile android --android-deps ...`) and cross-built for Windows when `--windows-deps` points at the output of `scripts/build_windows_deps.sh`. The Windows smoke test checks that the executable imports SDL3, shaderc and the Vulkan loader; real-title rendering has not been validated. Android uses `bf_android_sdl.c`; Windows uses `bf_windows_sdl.c`, reads runtime files beside the executable, and tees logs to stderr and `bifrost.log`.
