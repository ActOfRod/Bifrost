#!/usr/bin/env bash
# Build the NVN renderer dependencies for x86-64 Windows from Linux.
set -euo pipefail
D=${BIFROST_WINDOWS_DEPS:-$HOME/.bifrost/windows-deps}
S=$D/src
P=$D/out
W=$D/work
mkdir -p "$S" "$P/include" "$P/lib" "$W"
JOBS=$(nproc 2>/dev/null || echo 8)

SDL_URL=https://github.com/libsdl-org/SDL/releases/download/release-3.4.16/SDL3-devel-3.4.16-mingw.tar.gz
SDL_SHA=c7ef65bd72eabac6e5b535411dbd8d5824d0aab24fd62ff8812666b336f18a9c
IMGUI_URL=https://github.com/ocornut/imgui/archive/refs/tags/v1.91.9b.tar.gz
IMGUI_SHA=8e1bbc76c71d74fef2fb85db7e7ca8eba13d6a86623c54992b60162db554ffdb
LLVM_URL=https://github.com/mstorsjo/llvm-mingw/releases/download/20240619/llvm-mingw-20240619-ucrt-ubuntu-20.04-x86_64.tar.xz
LLVM_SHA=27d33157cc252c29ad6f777a96a0d94176fea1b534ff09b5071485def143b90e

fetch() {
    [ -f "$S/$3" ] || { curl -fsSL -o "$S/$3.part" "$1" && mv "$S/$3.part" "$S/$3"; }
    (cd "$S" && echo "$2  $3" | sha256sum -c --quiet)
}
unpack() {
    rm -rf "$W/$2"
    mkdir -p "$W/$2"
    (cd "$S" && tar --force-local -xf "$1" -C "$W/$2" --strip-components 1)
}

fetch "$SDL_URL" "$SDL_SHA" SDL3-devel-3.4.16-mingw.tar.gz
fetch "$IMGUI_URL" "$IMGUI_SHA" imgui-v1.91.9b.tar.gz
fetch https://github.com/google/shaderc/archive/refs/tags/v2026.3.tar.gz \
    ee493ccf1b3038b4ef2fe024664c5eb2dc4bcc1f6b05b33e3909de0e19c81024 shaderc-2026.3.tar.gz
fetch https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/vulkan-sdk-1.4.350.1.tar.gz \
    6d1bb65e49520344cc0a48af3dc02e993781efff14c7ebdcb8ae9fa23ddf7e83 Vulkan-Headers-1.4.350.1.tar.gz
fetch https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator/archive/refs/tags/v3.3.0.tar.gz \
    c4f6bbe6b5a45c2eb610ca9d231158e313086d5b1a40c9922cb42b597419b14e VulkanMemoryAllocator-3.3.0.tar.gz
fetch https://github.com/zeux/volk/archive/refs/tags/vulkan-sdk-1.4.350.1.tar.gz \
    078a9411298e4e0f60f5f5398c890783427c25a414619294ca8e69587bbd5eae volk-1.4.350.1.tar.gz
fetch https://raw.githubusercontent.com/KhronosGroup/Vulkan-Loader/vulkan-sdk-1.4.350.1/loader/vulkan-1.def \
    9ba339b7f5ee2df28487698a6840ecf095fac58b415c2c89ad9f161e9d316378 vulkan-1.def

if [ ! -f "$P/include/vulkan/vulkan_core.h" ]; then
    unpack Vulkan-Headers-1.4.350.1.tar.gz vkh
    cp -r "$W/vkh/include/vulkan" "$W/vkh/include/vk_video" "$P/include/"
fi
if [ ! -f "$P/include/vma/vk_mem_alloc.h" ]; then
    unpack VulkanMemoryAllocator-3.3.0.tar.gz vma
    mkdir -p "$P/include/vma"
    cp "$W/vma/include/vk_mem_alloc.h" "$P/include/vma/"
fi
if [ ! -f "$P/include/imgui/imgui.h" ]; then
    unpack imgui-v1.91.9b.tar.gz imgui
    mkdir -p "$P/include/imgui"
    cp -r "$W/imgui/"* "$P/include/imgui/"
fi
if [ ! -f "$P/volk/volk.c" ]; then
    unpack volk-1.4.350.1.tar.gz volk
    mkdir -p "$P/volk"
    cp "$W/volk/volk.h" "$W/volk/volk.c" "$W/volk/LICENSE.md" "$P/volk/"
fi

if [ ! -f "$P/lib/SDL3.dll" ] || [ ! -f "$P/lib/libSDL3.dll.a" ]; then
    unpack SDL3-devel-3.4.16-mingw.tar.gz sdl
    SDL=$W/sdl/x86_64-w64-mingw32
    cp -r "$SDL/include/SDL3" "$P/include/"
    cp "$SDL/lib/libSDL3.dll.a" "$P/lib/"
    cp "$SDL/bin/SDL3.dll" "$P/lib/"
fi

# The desktop renderer links the Windows Vulkan loader directly; it is installed with the GPU driver.
if [ ! -f "$P/lib/libvulkan-1.dll.a" ]; then
    x86_64-w64-mingw32-dlltool -d "$S/vulkan-1.def" -l "$P/lib/libvulkan-1.dll.a" -D vulkan-1.dll
fi

if [ ! -f "$P/lib/shaderc_shared.dll" ] || [ ! -f "$P/lib/libshaderc_shared.dll.a" ]; then
    unpack shaderc-2026.3.tar.gz shaderc
    (cd "$W/shaderc" && python3 utils/git-sync-deps)
    make_toolchain() {
        local cc=$1 cxx=$2 rc=$3 file=$4
        printf 'set(CMAKE_SYSTEM_NAME Windows)\nset(CMAKE_SYSTEM_PROCESSOR x86_64)\nset(CMAKE_C_COMPILER "%s")\nset(CMAKE_CXX_COMPILER "%s")\nset(CMAKE_RC_COMPILER "%s")\nset(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)\nset(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)\nset(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)\nset(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)\n' \
            "$cc" "$cxx" "$rc" > "$file"
    }
    build_shaderc() {
        local cc=$1 cxx=$2 rc=$3 name=$4
        local toolchain=$W/$name-toolchain.cmake
        local cxxflags="-static-libgcc -static-libstdc++"
        local ldflags="-static-libgcc -static-libstdc++"
        local generator=(-G "Unix Makefiles")
        if command -v ninja >/dev/null 2>&1; then generator=(-G Ninja); fi
        if [ "$name" = llvm-mingw ]; then
            cxxflags="-static -stdlib=libc++"
            ldflags="-static -stdlib=libc++"
        fi
        make_toolchain "$cc" "$cxx" "$rc" "$toolchain"
        cmake -S "$W/shaderc" -B "$W/shaderc/build" "${generator[@]}" \
            -DCMAKE_TOOLCHAIN_FILE="$toolchain" -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_C_FLAGS="-static-libgcc" \
            -DCMAKE_CXX_FLAGS="$cxxflags" \
            -DCMAKE_SHARED_LINKER_FLAGS="$ldflags" \
            -DSHADERC_SKIP_TESTS=ON -DSHADERC_SKIP_EXAMPLES=ON -DSHADERC_SKIP_COPYRIGHT_CHECK=ON \
            -DSPIRV_SKIP_TESTS=ON -DSPIRV_SKIP_EXECUTABLES=ON -DENABLE_GLSLANG_BINARIES=OFF \
            -DSPIRV_TOOLS_BUILD_STATIC=ON -DBUILD_SHARED_LIBS=OFF
        cmake --build "$W/shaderc/build" --target shaderc_shared -j"$JOBS"
    }
    if ! build_shaderc x86_64-w64-mingw32-gcc x86_64-w64-mingw32-g++ x86_64-w64-mingw32-windres mingw \
        >"$W/shaderc-mingw.log" 2>&1; then
        cat "$W/shaderc-mingw.log" >&2
        fetch "$LLVM_URL" "$LLVM_SHA" llvm-mingw-20240619.tar.xz
        unpack llvm-mingw-20240619.tar.xz llvm-mingw
        LLVM=$W/llvm-mingw/bin
        rm -rf "$W/shaderc/build"
        build_shaderc "$LLVM/x86_64-w64-mingw32-clang" "$LLVM/x86_64-w64-mingw32-clang++" \
            "$LLVM/llvm-windres" llvm-mingw
    fi
    mkdir -p "$P/include/shaderc"
    cp "$W/shaderc/libshaderc/include/shaderc/"*.h "$P/include/shaderc/"
    cp "$W/shaderc/build/libshaderc/libshaderc_shared.dll" "$P/lib/shaderc_shared.dll"
    cp "$W/shaderc/build/libshaderc/libshaderc_shared.dll.a" "$P/lib/"
fi

echo "deps: $P"
ls "$P/lib"
