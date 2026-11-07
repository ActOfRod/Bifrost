#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LLVM_MINGW_TAG=20240619
LLVM_MINGW_ARCHIVE=llvm-mingw-20240619-ucrt-ubuntu-20.04-x86_64.tar.xz
LLVM_MINGW_URL="https://github.com/mstorsjo/llvm-mingw/releases/download/$LLVM_MINGW_TAG/$LLVM_MINGW_ARCHIVE"
LLVM_MINGW_SHA256=27d33157cc252c29ad6f777a96a0d94176fea1b534ff09b5071485def143b90e
LLVM_MINGW_CACHE="$ROOT/target/llvm-mingw"
LLVM_MINGW_ROOT="$LLVM_MINGW_CACHE/llvm-mingw-20240619-ucrt-ubuntu-20.04-x86_64"
OUTPUT=${1:-"$ROOT/BifrostAndroid/app/build/winlator/assets/bifrost-launch.exe"}
COMPILER="$LLVM_MINGW_ROOT/bin/x86_64-w64-mingw32-clang"

for command in curl sha256sum tar; do
    command -v "$command" >/dev/null 2>&1 || {
        echo "Required command not found: $command" >&2
        exit 1
    }
done

mkdir -p "$LLVM_MINGW_CACHE"
if [[ ! -f "$LLVM_MINGW_CACHE/$LLVM_MINGW_ARCHIVE" ]]; then
    curl --fail --location --retry 2 "$LLVM_MINGW_URL" -o "$LLVM_MINGW_CACHE/$LLVM_MINGW_ARCHIVE"
fi
printf '%s  %s\n' "$LLVM_MINGW_SHA256" "$LLVM_MINGW_CACHE/$LLVM_MINGW_ARCHIVE" | sha256sum --check -
if [[ ! -x "$COMPILER" ]]; then
    tar -xf "$LLVM_MINGW_CACHE/$LLVM_MINGW_ARCHIVE" -C "$LLVM_MINGW_CACHE"
fi

if [[ ! -x "$COMPILER" ]]; then
    echo "Pinned llvm-mingw compiler was not found: $COMPILER" >&2
    exit 1
fi

mkdir -p "$(dirname "$OUTPUT")"
"$COMPILER" -O2 -municode -mwindows -static \
    -Wl,--no-insert-timestamp \
    "$ROOT/BifrostAndroid/guest/bifrost-launch.c" \
    -o "$OUTPUT" -ldinput8 -ldxguid -lshell32

OBJDUMP="$LLVM_MINGW_ROOT/bin/llvm-objdump"
if [[ ! -x "$OBJDUMP" ]]; then
    echo "Pinned llvm-objdump was not found: $OBJDUMP" >&2
    exit 1
fi
IMPORTS_FILE="$(mktemp)"
trap 'rm -f "$IMPORTS_FILE"' EXIT
"$OBJDUMP" -p "$OUTPUT" > "$IMPORTS_FILE"
if grep -Eiq 'DLL Name:.*(libc\+\+|libunwind|libgcc|libwinpthread)' "$IMPORTS_FILE"; then
    echo "Guest helper unexpectedly imports a toolchain runtime DLL:" >&2
    grep -Ei 'DLL Name:' "$IMPORTS_FILE" >&2
    exit 1
fi
printf 'Built guest helper: %s\n' "$OUTPUT"
grep -Ei 'DLL Name:' "$IMPORTS_FILE" || true
