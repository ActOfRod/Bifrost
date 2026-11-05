#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

DSOAL_COMMIT=e11128250439d586483ee6598d1670ab52ab5e37
OPENAL_TAG=1.24.3
OPENAL_COMMIT=dc7d7054a5b4f3bec1dc23a42fd616a0847af948
SOURCES="$ROOT/target/dsoal-sources"
BUILD="$ROOT/target/dsoal-build-llvm"
OUTPUT="$ROOT/target/dsoal"
LLVM_MINGW_TAG=20240619
LLVM_MINGW_ARCHIVE=llvm-mingw-20240619-ucrt-ubuntu-20.04-x86_64.tar.xz
LLVM_MINGW_URL="https://github.com/mstorsjo/llvm-mingw/releases/download/$LLVM_MINGW_TAG/$LLVM_MINGW_ARCHIVE"
LLVM_MINGW_SHA256=27d33157cc252c29ad6f777a96a0d94176fea1b534ff09b5071485def143b90e
LLVM_MINGW_CACHE="$ROOT/target/llvm-mingw"
LLVM_MINGW_ROOT="$LLVM_MINGW_CACHE/llvm-mingw-20240619-ucrt-ubuntu-20.04-x86_64"

mkdir -p "$LLVM_MINGW_CACHE"
if [[ ! -f "$LLVM_MINGW_CACHE/$LLVM_MINGW_ARCHIVE" ]]; then
    curl --fail --location --retry 2 "$LLVM_MINGW_URL" -o "$LLVM_MINGW_CACHE/$LLVM_MINGW_ARCHIVE"
fi
printf '%s  %s\n' "$LLVM_MINGW_SHA256" "$LLVM_MINGW_CACHE/$LLVM_MINGW_ARCHIVE" | sha256sum --check -
if [[ ! -x "$LLVM_MINGW_ROOT/bin/x86_64-w64-mingw32-clang++" ]]; then
    tar -xf "$LLVM_MINGW_CACHE/$LLVM_MINGW_ARCHIVE" -C "$LLVM_MINGW_CACHE"
fi

checkout_pinned() {
    local url=$1 destination=$2 revision=$3
    if [[ ! -d "$destination/.git" ]]; then
        mkdir -p "$(dirname "$destination")"
        git clone --filter=blob:none --no-checkout "$url" "$destination"
    fi
    git -C "$destination" fetch --depth 1 origin "$revision"
    git -C "$destination" checkout --detach "$revision"
}

checkout_pinned https://github.com/kcat/dsoal.git "$SOURCES/dsoal" "$DSOAL_COMMIT"
checkout_pinned https://github.com/kcat/openal-soft.git "$SOURCES/openal-soft" "$OPENAL_TAG"
[[ "$(git -C "$SOURCES/openal-soft" rev-parse HEAD)" == "$OPENAL_COMMIT" ]] || {
    echo "OpenAL Soft tag $OPENAL_TAG no longer resolves to $OPENAL_COMMIT" >&2
    exit 1
}

python3 - "$SOURCES/dsoal/src/dsoundoal.cpp" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
old = '''    ALCdevicePtr aldev{alcOpenDevice(drv_name.c_str())};
    if(!aldev)
    {
        WARN("Couldn't open device \\"{}\\", {:#x}", drv_name, alcGetError(nullptr));
        return ds::unexpected(DSERR_NODRIVER);
    }'''
new = '''    ALCdevicePtr aldev{alcOpenDevice(drv_name.c_str())};
    if(!aldev)
    {
        WARN("Couldn't open device \\"{}\\", {:#x}; falling back to the OpenAL default",
            drv_name, alcGetError(nullptr));
        aldev.reset(alcOpenDevice(nullptr));
        if(!aldev)
            return ds::unexpected(DSERR_NODRIVER);
    }'''
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("pinned DSOAL device-open code changed; review the OpenAL default fallback")
path.write_text(text)
PY

python3 - "$SOURCES/dsoal/CMakeLists.txt" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
old = '''    set(DSOAL_LINK_FLAGS ${DSOAL_LINK_FLAGS} -static-libgcc -static-libstdc++)
    set(DSOAL_LINK_FLAGS ${DSOAL_LINK_FLAGS} "-Wl,--push-state,-Bstatic,-lstdc++,-lwinpthread,--pop-state")'''
new = '''    set(DSOAL_LINK_FLAGS ${DSOAL_LINK_FLAGS} "-Wl,-Bstatic,-lc++,-lc++abi,-lunwind,-Bdynamic")'''
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("pinned DSOAL MinGW link flags changed; review the llvm-mingw adaptation")
old_def = "    set(DEF_FILE mingw.def)"
new_def = '''    if(CMAKE_SIZEOF_VOID_P MATCHES "8")
        set(DEF_FILE msvc64.def)
    else()
        set(DEF_FILE mingw.def)
    endif()'''
if old_def not in text:
    if new_def not in text:
        raise SystemExit("pinned DSOAL definition-file selection changed; review the llvm-mingw adaptation")
else:
    text = text.replace(old_def, new_def, 1)
path.write_text(text)
PY

mkdir -p "$OUTPUT"
for spec in "x86:i686-w64-mingw32" "x64:x86_64-w64-mingw32"; do
    arch=${spec%%:*}
    prefix=${spec#*:}
    openal_build="$BUILD/$arch/openal"
    dsoal_build="$BUILD/$arch/dsoal"
    cmake -S "$SOURCES/openal-soft" -B "$openal_build" \
        -DCMAKE_SYSTEM_NAME=Windows \
        -DCMAKE_C_COMPILER="$LLVM_MINGW_ROOT/bin/${prefix}-clang" \
        -DCMAKE_CXX_COMPILER="$LLVM_MINGW_ROOT/bin/${prefix}-clang++" \
        -DCMAKE_RC_COMPILER="$LLVM_MINGW_ROOT/bin/${prefix}-windres" \
        -DCMAKE_BUILD_TYPE=Release \
        -DALSOFT_UPDATE_BUILD_VERSION=OFF \
        -DALSOFT_INSTALL=OFF \
        -DALSOFT_INSTALL_CONFIG=OFF \
        -DALSOFT_INSTALL_HRTF_DATA=OFF \
        -DALSOFT_INSTALL_AMBDEC_PRESETS=OFF \
        -DALSOFT_INSTALL_EXAMPLES=OFF \
        -DALSOFT_INSTALL_UTILS=OFF \
        -DALSOFT_UTILS=OFF \
        -DALSOFT_EXAMPLES=OFF \
        -DALSOFT_TESTS=OFF \
        -DALSOFT_BACKEND_PIPEWIRE=OFF \
        -DALSOFT_BACKEND_PULSEAUDIO=OFF \
        -DALSOFT_BACKEND_WASAPI=ON \
        -DALSOFT_BACKEND_WAVE=ON
    cmake --build "$openal_build" --parallel
    openal_dll=$(find "$openal_build" -type f -iname OpenAL32.dll -print -quit)
    [[ -n "$openal_dll" ]] || { echo "OpenAL Soft did not produce OpenAL32.dll for $arch" >&2; exit 1; }

    mkdir -p "$dsoal_build"
    cp "$openal_dll" "$dsoal_build/dsoal-aldrv.dll"
    cmake -S "$SOURCES/dsoal" -B "$dsoal_build" \
        -DCMAKE_SYSTEM_NAME=Windows \
        -DCMAKE_C_COMPILER="$LLVM_MINGW_ROOT/bin/${prefix}-clang" \
        -DCMAKE_CXX_COMPILER="$LLVM_MINGW_ROOT/bin/${prefix}-clang++" \
        -DCMAKE_RC_COMPILER="$LLVM_MINGW_ROOT/bin/${prefix}-windres" \
        -DCMAKE_BUILD_TYPE=Release \
        -DDSOAL_UPDATE_BUILD_VERSION=OFF
    cmake --build "$dsoal_build" --parallel
    dsound_dll=$(find "$dsoal_build" -type f -iname dsound.dll -print -quit)
    [[ -n "$dsound_dll" ]] || { echo "DSOAL did not produce dsound.dll for $arch" >&2; exit 1; }
    mkdir -p "$OUTPUT/$arch"
    cp "$dsound_dll" "$OUTPUT/$arch/dsound.dll"
    cp "$dsoal_build/dsoal-aldrv.dll" "$OUTPUT/$arch/dsoal-aldrv.dll"
    cp "$LLVM_MINGW_ROOT/$prefix/bin/libc++.dll" "$OUTPUT/$arch/libc++.dll"
    cp "$LLVM_MINGW_ROOT/$prefix/bin/libunwind.dll" "$OUTPUT/$arch/libunwind.dll"
done

cp third_party/dsoal/LICENSE "$OUTPUT/DSOAL_LICENSE.txt"
cp third_party/openal-soft/COPYING "$OUTPUT/OpenAL_LICENSE.txt"
cp third_party/dsoal/SOURCE.txt "$OUTPUT/SOURCE.txt"
