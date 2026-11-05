#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

REGEN=0
WINDOWS_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --shaders) REGEN=1 ;;
        --windows-only) WINDOWS_ONLY=1 ;;
        *) echo "usage: $0 [--shaders] [--windows-only]" >&2; exit 2 ;;
    esac
done

HEADERS="$ROOT/target/vulkan-headers"
if [[ ! -f "$HEADERS/include/vulkan/vulkan.h" ]]; then
    if [[ -e "$HEADERS" ]]; then
        echo "$HEADERS exists but is not a Vulkan-Headers checkout" >&2
        exit 1
    fi
    git clone --depth 1 --branch v1.3.280 https://github.com/KhronosGroup/Vulkan-Headers.git "$HEADERS"
fi

SHADERS="$ROOT/layer/fsr/shaders"
FSR_HEADERS="$ROOT/third_party/fidelityfx-fsr"
NIS_HEADERS="$ROOT/third_party/nvidia-image-scaling/NIS"
if (( REGEN )); then
    command -v glslangValidator >/dev/null
    glslangValidator -V --vn easu_spv -I"$FSR_HEADERS" -o "$SHADERS/easu.frag.h" "$SHADERS/easu.frag"
    glslangValidator -V --vn rcas_spv -I"$FSR_HEADERS" -o "$SHADERS/rcas.frag.h" "$SHADERS/rcas.frag"
    glslangValidator -V --vn fullscreen_vert_spv -o "$SHADERS/fullscreen.vert.h" "$SHADERS/fullscreen.vert"
    glslangValidator -V --vn copy_spv -o "$SHADERS/copy.frag.h" "$SHADERS/copy.frag"
    glslangValidator -V --target-env vulkan1.0 --vn nis_spv \
        -I"$NIS_HEADERS" -o "$SHADERS/nis.comp.h" "$SHADERS/nis.comp"
fi
for shader in easu.frag rcas.frag fullscreen.vert copy.frag; do
    [[ -f "$SHADERS/$shader.h" ]] || { echo "missing generated $shader shader; run $0 --shaders" >&2; exit 1; }
done
[[ -f "$SHADERS/nis.comp.h" ]] || { echo "missing generated NIS shader; run $0 --shaders" >&2; exit 1; }

COMMON=(-O2 -Wall -Wextra -Werror -Wno-missing-field-initializers -fvisibility=hidden -I"$HEADERS/include" -I"$FSR_HEADERS" -I"$NIS_HEADERS" -I"$SHADERS")
build_manifest() {
    local dir=$1 lib=$2
    mkdir -p "$dir"
    printf '{\n  "file_format_version": "1.2.0",\n  "layer": {\n    "name": "VK_LAYER_BIFROST_fsr1",\n    "type": "GLOBAL",\n    "library_path": "%s",\n    "api_version": "1.3.0",\n    "implementation_version": 1,\n    "description": "Bifrost FSR 1 Vulkan upscaler"\n  }\n}\n' "$lib" > "$dir/bifrost_fsr.json"
}

if (( ! WINDOWS_ONLY )); then
    dir="$ROOT/target/fsr-layer/linux"
    mkdir -p "$dir"
    gcc -std=c11 "${COMMON[@]}" -fPIC -c "$ROOT/layer/fsr/bifrost_fsr.c" -o "$dir/bifrost_fsr.o"
    g++ -std=c++17 -O2 -fPIC -c "$ROOT/layer/fsr/nis_config.cpp" -I"$NIS_HEADERS" -o "$dir/nis_config.o"
    g++ "$dir/bifrost_fsr.o" "$dir/nis_config.o" -shared -o "$dir/libbifrost_fsr.so" -ldl -lpthread -lm
    build_manifest "$dir" "./libbifrost_fsr.so"
fi

for spec in "x86:i686-w64-mingw32-gcc" "x64:x86_64-w64-mingw32-gcc"; do
    arch=${spec%%:*}
    cc=${spec#*:}
    dir="$ROOT/target/fsr-layer/$arch"
    mkdir -p "$dir"
    cxx=${cc%gcc}g++
    "$cc" -std=c11 "${COMMON[@]}" -c "$ROOT/layer/fsr/bifrost_fsr.c" -o "$dir/bifrost_fsr.o"
    "$cxx" -std=c++17 -O2 -c "$ROOT/layer/fsr/nis_config.cpp" -I"$NIS_HEADERS" -o "$dir/nis_config.o"
    "$cxx" "$dir/bifrost_fsr.o" "$dir/nis_config.o" -shared -static-libgcc -static-libstdc++ -Wl,--kill-at -lpsapi -o "$dir/bifrost_fsr.dll"
    build_manifest "$dir" '.\\bifrost_fsr.dll'
done
