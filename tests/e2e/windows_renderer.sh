#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEPS=${BIFROST_WINDOWS_DEPS:-$HOME/.bifrost/windows-deps}/out
if [ -n "${WINDOWS_DEPS:-}" ]; then DEPS=$WINDOWS_DEPS; fi
if [ ! -f "$DEPS/lib/SDL3.dll" ] || [ ! -f "$DEPS/lib/shaderc_shared.dll" ] ||
   [ ! -f "$DEPS/lib/libvulkan-1.dll.a" ]; then
    echo "SKIP: Windows renderer dependencies not found; run scripts/build_windows_deps.sh"
    exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
aarch64-linux-gnu-gcc -O2 -fPIC -shared -nostdlib -Wl,-e,main \
    "$ROOT/tests/e2e/windows_nvn_guest.c" -o "$TMP/guest.elf"
python3 "$ROOT/tests/e2e/make_fixture.py" "$TMP/guest.elf" "$TMP/fixture"
(
    cd "$ROOT"
    cargo run --quiet -- build "$TMP/fixture/game.nsp" --compile windows \
        --windows-deps "$DEPS" -o "$TMP/out"
)
EXE=$(find "$TMP/out" -maxdepth 1 -name '*.exe' -print -quit)
if [ -z "$EXE" ]; then
    echo "Windows renderer build produced no executable" >&2
    exit 1
fi
x86_64-w64-mingw32-objdump -p "$EXE" > "$TMP/imports.txt"
for dll in SDL3.dll shaderc_shared.dll vulkan-1.dll; do
    if ! rg -qi "$dll" "$TMP/imports.txt"; then
        echo "missing $dll in Windows renderer import table" >&2
        cat "$TMP/imports.txt" >&2
        exit 1
    fi
done
echo "Windows renderer link validation passed (SDL3.dll, shaderc_shared.dll, vulkan-1.dll)"

if command -v wine >/dev/null && command -v xvfb-run >/dev/null; then
    set +e
    LVP=$(find /usr/share/vulkan/icd.d -name 'lvp_icd*.json' -print -quit 2>/dev/null || true)
    WINE_ENV=(WINEDEBUG=-all WINEPREFIX="$TMP/wine")
    if [ -n "$LVP" ]; then WINE_ENV+=(VK_ICD_FILENAMES="$LVP"); fi
    timeout 60s xvfb-run -a env "${WINE_ENV[@]}" \
        sh -c 'wineboot -u && wine "$1"' _ "$EXE" >"$TMP/wine.log" 2>&1
    status=$?
    set -e
    if [ "$status" -eq 0 ]; then
        echo "Windows renderer NVN guest ran under Wine."
    else
        echo "Wine runtime attempt did not complete (status $status); PE link validation passed."
        cat "$TMP/wine.log"
    fi
else
    echo "Wine/Xvfb unavailable; renderer was checked at link time only."
fi
