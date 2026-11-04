#!/usr/bin/env bash
# Modernizer end-to-end: build small DirectDraw, Direct3D 8 and 64-bit Direct3D 11 "games" with MinGW, modernize
# them, and check the copy (LAA flag + checksum, DXVK/cnc-ddraw files, settings, compat script) and original safety.
set -euo pipefail
cd "$(dirname "$0")/../.."
cargo build --quiet
BF=$PWD/target/debug/bifrost
W=$(mktemp -d)
fail() { echo "FAIL $*"; exit 1; }
check_laa() {
    grep -q "large address aware" <(i686-w64-mingw32-objdump -p "$1") || fail "$2"
}
mkdir -p "$W/g32/System" "$W/g64" "$W/gdd" "$W/gd7" "$W/gown" "$W/gtwo/System"
cat > "$W/engine.c" <<'C'
#include <windows.h>
#include <d3d8.h>
__declspec(dllexport) int engine_init(void) { IDirect3D8 *d = Direct3DCreate8(D3D_SDK_VERSION); return d != 0; }
C
cat > "$W/game.c" <<'C'
__declspec(dllimport) int engine_init(void);
int main(void) { return engine_init(); }
C
cat > "$W/game64.c" <<'C'
#include <windows.h>
#include <d3d11.h>
int main(void) { return D3D11CreateDevice(0, 0, 0, 0, 0, 0, D3D11_SDK_VERSION, 0, 0, 0) ? 1 : 0; }
C
cat > "$W/gdd.c" <<'C'
#include <windows.h>
#include <ddraw.h>
int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command, int show) {
    LPDIRECTDRAW dd = 0;
    return FAILED(DirectDrawCreate(0, &dd, 0));
}
C
cat > "$W/gd7.c" <<'C'
#include <windows.h>
#include <ddraw.h>
static const unsigned char iid_d3d7[16] = {
    0x77, 0x9e, 0x04, 0xf5, 0x61, 0x48, 0xd2, 0x11,
    0xa4, 0x07, 0x00, 0xa0, 0xc9, 0x06, 0x29, 0xa8
};
int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command, int show) {
    LPDIRECTDRAW dd = 0;
    LPVOID d3d = 0;
    if (SUCCEEDED(DirectDrawCreate(0, &dd, 0)))
        IDirectDraw_QueryInterface(dd, (const GUID *)iid_d3d7, &d3d);
    return d3d != 0;
}
C
cat > "$W/ddraw_stub.c" <<'C'
__declspec(dllexport) int ddraw_stub(void) { return 1; }
C
echo "int main(void){return 0;}" > "$W/unins.c"
i686-w64-mingw32-gcc -shared -O2 -o "$W/g32/System/Engine.dll" "$W/engine.c" -ld3d8 -Wl,--out-implib,"$W/libengine.a"
i686-w64-mingw32-gcc -O2 -mwindows -o "$W/g32/System/Game.exe" "$W/game.c" -L"$W" -lengine
i686-w64-mingw32-gcc -O2 -o "$W/g32/unins000.exe" "$W/unins.c"
x86_64-w64-mingw32-gcc -O2 -mwindows -o "$W/g64/Game64.exe" "$W/game64.c" -ld3d11
i686-w64-mingw32-gcc -shared -O2 -o "$W/ddraw.dll" "$W/ddraw_stub.c"
i686-w64-mingw32-gcc -O2 -mwindows -o "$W/gdd/Game.exe" "$W/gdd.c" -lddraw
i686-w64-mingw32-gcc -O2 -mwindows -o "$W/gd7/Game.exe" "$W/gd7.c" -lddraw
cp "$W/gdd/Game.exe" "$W/gtwo/System/GameA.exe"
cp "$W/gdd/Game.exe" "$W/gtwo/System/GameB.exe"
cp "$W/gdd/Game.exe" "$W/gown/Game.exe"
cp "$W/ddraw.dll" "$W/gown/ddraw.dll"
python3 - "$W/cnc-ddraw.zip" "$W/ddraw.dll" <<'PY'
from zipfile import ZIP_DEFLATED, ZipFile
import sys
with ZipFile(sys.argv[1], "w", ZIP_DEFLATED) as archive:
    archive.write(sys.argv[2], "ddraw.dll")
    archive.writestr("Shaders/interpolation/fsr.glsl", "fsr")
    archive.writestr("Shaders/interpolation/fsr.glsl.pass1", "pass1")
PY
mkdir -p "$W/fsr-layer/x86" "$W/fsr-layer/x64"
printf 'x86-fsr-layer' > "$W/fsr-layer/x86/bifrost_fsr.dll"
printf 'x64-fsr-layer' > "$W/fsr-layer/x64/bifrost_fsr.dll"
printf '{"layer":"x86"}\n' > "$W/fsr-layer/x86/bifrost_fsr.json"
printf '{"layer":"x64"}\n' > "$W/fsr-layer/x64/bifrost_fsr.json"
before=$(cd "$W" && find g32 g64 gdd gd7 gown gtwo -type f -exec sha256sum {} + | sort)

$BF modernize "$W/g32" --scan > "$W/scan.txt"
grep -q '^\* System/Game.exe (32-bit' "$W/scan.txt" || { cat "$W/scan.txt"; fail "main exe detection"; }
grep -q 'Direct3D 8 (System/Engine.dll)' "$W/scan.txt" || fail "API scan through engine DLL"
echo "ok   scan"

$BF modernize "$W/g32" -o "$W/o32" --max-fps 60 > /dev/null 2>&1 || $BF modernize "$W/g32" -o "$W/o32" --max-fps 60
O=$W/o32/System
check_laa "$O/Game.exe" "LAA flag"
python3 - "$O/Game.exe" <<'PY' || fail "PE checksum"
import struct, sys
d = open(sys.argv[1], 'rb').read(); off = struct.unpack_from('<I', d, 0x3c)[0] + 24 + 64
s = 0
for i in range(0, len(d), 2):
    if i in (off, off + 2): continue
    s += d[i] | ((d[i + 1] if i + 1 < len(d) else 0) << 8); s = (s & 0xffff) + (s >> 16)
s = (s & 0xffff) + (s >> 16)
sys.exit(0 if struct.unpack_from('<I', d, off)[0] == (s + len(d)) & 0xffffffff else 1)
PY
for f in d3d8.dll d3d9.dll DXVK_LICENSE.txt; do [ -f "$O/$f" ] || fail "missing $f"; done
grep -q "Intel 80386" <(file "$O/d3d9.dll") || fail "32-bit DXVK expected"
grep -q "d3d9.samplerAnisotropy = 16" "$O/dxvk.conf" && grep -q "d3d9.maxFrameRate = 60" "$O/dxvk.conf" || fail dxvk.conf
grep -q 'System\\Game.exe" /t REG_SZ /d "~ HIGHDPIAWARE DISABLEDXMAXIMIZEDWINDOWEDMODE"' "$W/o32/bifrost_compat.bat" || fail "compat script"
grep -q 'setlocal DisableDelayedExpansion' "$W/o32/bifrost_compat.bat" || fail "compat script delayed-expansion safety"
grep -q 'setlocal DisableDelayedExpansion' "$W/o32/bifrost_compat_undo.bat" || fail "undo script delayed-expansion safety"
[ -f "$W/o32/unins000.exe" ] && [ -f "$W/o32/bifrost_modernize.txt" ] || fail "copy/report"
echo "ok   32-bit Direct3D 8 game"

$BF modernize "$W/g64/Game64.exe" -o "$W/o64" --anisotropy 8 --xp-mode > /dev/null 2>&1
[ -f "$W/o64/d3d11.dll" ] && [ -f "$W/o64/dxgi.dll" ] && [ ! -f "$W/o64/d3d9.dll" ] || fail "64-bit DXVK set"
grep -q "x86-64" <(file "$W/o64/d3d11.dll") || fail "64-bit DXVK expected"
grep -q "d3d11.samplerAnisotropy = 8" "$W/o64/dxvk.conf" || fail "64-bit dxvk.conf"
grep -q "WINXPSP3" "$W/o64/bifrost_compat.bat" || fail "XP mode"
cmp -s "$W/g64/Game64.exe" "$W/o64/Game64.exe" || fail "64-bit exe should not be patched"
echo "ok   64-bit Direct3D 11 game"

$BF modernize "$W/gtwo" --exe System/GameA.exe -o "$W/otwo" --no-dxvk --no-ddraw > "$W/two-first.txt" 2>&1 || fail "first two-executable modernization"
$BF modernize "$W/gtwo" --exe System/GameB.exe -o "$W/otwo" --no-dxvk --no-ddraw > "$W/two-second.txt" 2>&1 || fail "rerun with a different executable"
cmp -s "$W/gtwo/System/GameA.exe" "$W/otwo/System/GameA.exe" || fail "previous executable was not restored on re-run"
check_laa "$W/otwo/System/GameB.exe" "re-run executable LAA flag"

$BF modernize "$W/gdd" --scan > "$W/gdd-scan.txt"
grep -q DirectDraw "$W/gdd-scan.txt" || fail "DirectDraw scan"
$BF modernize "$W/gd7" --scan > "$W/gd7-scan.txt"
grep -q "Direct3D 7 or older" "$W/gd7-scan.txt" || fail "Direct3D 7 scan"
$BF modernize "$W/gdd" -o "$W/odd" --no-dxvk --ddraw "$W/cnc-ddraw.zip" --fsr --max-fps 60 > "$W/gdd-fsr.txt" 2>&1 || fail "DirectDraw FSR run"
for f in ddraw.dll CNC_DDRAW_LICENSE.txt Shaders/interpolation/fsr.glsl Shaders/interpolation/fsr.glsl.pass1; do
    [ -f "$W/odd/$f" ] || fail "missing cnc-ddraw file $f"
done
grep -q 'shader=Shaders\\interpolation\\fsr.glsl' "$W/odd/ddraw.ini" || fail "FSR shader setting"
grep -q 'renderer=opengl' "$W/odd/ddraw.ini" || fail "FSR renderer setting"
grep -q 'fullscreen=true' "$W/odd/ddraw.ini" || fail "FSR fullscreen setting"
grep -q 'windowed=true' "$W/odd/ddraw.ini" || fail "FSR windowed setting"
grep -q 'maxfps=60' "$W/odd/ddraw.ini" || fail "FSR frame-rate setting"
grep -q 'FSR 1' "$W/odd/bifrost_modernize.txt" || fail "FSR report"
[ ! -e "$W/odd/bifrost_fsr.dll" ] && [ ! -e "$W/odd/Play with FSR.bat" ] || fail "Vulkan FSR layer installed for DirectDraw"
$BF modernize "$W/gdd" -o "$W/odd" --no-dxvk --ddraw "$W/cnc-ddraw.zip" > "$W/gdd-bicubic.txt" 2>&1 || fail "DirectDraw bicubic re-run"
grep -q 'shader=Bicubic' "$W/odd/ddraw.ini" || fail "default DirectDraw shader setting"
grep -q 'renderer=auto' "$W/odd/ddraw.ini" || fail "default DirectDraw renderer setting"
grep -q 'maxfps=-1' "$W/odd/ddraw.ini" || fail "default DirectDraw frame-rate setting"
$BF modernize "$W/gdd" -o "$W/odd" --no-dxvk --no-ddraw > "$W/gdd-no-ddraw.txt" 2>&1 || fail "DirectDraw removal re-run"
[ ! -e "$W/odd/ddraw.dll" ] && [ ! -e "$W/odd/ddraw.ini" ] && [ ! -e "$W/odd/CNC_DDRAW_LICENSE.txt" ] && [ ! -e "$W/odd/Shaders" ] || fail "cnc-ddraw files remained after --no-ddraw"
$BF modernize "$W/gd7" -o "$W/ogd7" --no-dxvk --ddraw "$W/cnc-ddraw.zip" > "$W/gd7-ddraw.txt" 2>&1 || fail "Direct3D 7 skip run"
[ ! -e "$W/ogd7/ddraw.dll" ] || fail "cnc-ddraw installed for Direct3D 7 game"
grep -q 'Direct3D 7' "$W/ogd7/bifrost_modernize.txt" || fail "Direct3D 7 warning"
$BF modernize "$W/gown" -o "$W/ogown" --no-dxvk --ddraw "$W/cnc-ddraw.zip" > "$W/gown-ddraw.txt" 2>&1 || fail "own DirectDraw wrapper run"
cmp -s "$W/gown/ddraw.dll" "$W/ogown/ddraw.dll" || fail "game's own ddraw.dll changed"
grep -q 'ships its own ddraw.dll' "$W/ogown/bifrost_modernize.txt" || fail "own DirectDraw wrapper warning"
$BF modernize "$W/g32" -o "$W/ofsr8" --no-dxvk --fsr > "$W/g32-fsr.txt" 2>&1 || fail "FSR on Direct3D 8 game"
grep -q 'FSR 1 was requested, but neither the DirectDraw/cnc-ddraw nor Direct3D 8–11/DXVK path applies' "$W/ofsr8/bifrost_modernize.txt" || fail "FSR-on-Direct3D warning"
if $BF modernize "$W/gdd" -o "$W/omissing" --no-dxvk --ddraw "$W/missing.zip" > "$W/gdd-missing.txt" 2>&1; then fail "missing cnc-ddraw accepted"; fi
[ ! -e "$W/omissing" ] || fail "missing cnc-ddraw created output"
echo "ok   DirectDraw game"

$BF modernize "$W/g32" -o "$W/ofsr32" --fsr --fsr-layer "$W/fsr-layer" > "$W/fsr32.txt" 2>&1 || fail "32-bit Direct3D FSR run"
cmp -s "$W/fsr-layer/x86/bifrost_fsr.dll" "$W/ofsr32/System/bifrost_fsr.dll" || fail "32-bit FSR layer architecture"
cmp -s "$W/fsr-layer/x86/bifrost_fsr.json" "$W/ofsr32/System/bifrost_fsr.json" || fail "32-bit FSR layer manifest"
for f in bifrost_fsr.ini FSR_LICENSE.txt "Play with FSR.bat"; do [ -f "$W/ofsr32/System/$f" ] || fail "missing Direct3D FSR file $f"; done
grep -q 'AMD FSR 1 upscales Direct3D to the display: launch with "Play with FSR.bat"' "$W/ofsr32/bifrost_modernize.txt" || fail "Direct3D FSR report"
python3 - "$W/ofsr32/System/Play with FSR.bat" <<'PY' || fail "FSR launcher contents or CRLF"
import pathlib, sys
data = pathlib.Path(sys.argv[1]).read_bytes()
expected = (
    b'@echo off\r\nsetlocal DisableDelayedExpansion\r\n'
    b'rem Written by the Bifrost Modernizer: starts the game with AMD FSR 1 upscaling.\r\n'
    b'set "VK_LAYER_PATH=%~dp0"\r\nset "VK_INSTANCE_LAYERS=VK_LAYER_BIFROST_fsr1"\r\n'
    b'set "BIFROST_FSR_CONFIG_DIR=%~dp0"\r\ncd /d "%~dp0"\r\nstart "" "%~dp0Game.exe"\r\n'
)
sys.exit(0 if data == expected else 1)
PY
before_missing=$(cd "$W/ofsr32" && find . -type f -exec sha256sum {} + | sort)
if $BF modernize "$W/g32" -o "$W/ofsr32" --fsr --fsr-layer "$W/missing-layer" > "$W/fsr-missing.txt" 2>&1; then fail "missing FSR layer accepted"; fi
grep -q -- '--fsr-layer' "$W/fsr-missing.txt" || fail "missing layer error does not identify --fsr-layer"
after_missing=$(cd "$W/ofsr32" && find . -type f -exec sha256sum {} + | sort)
[ "$before_missing" = "$after_missing" ] || fail "missing FSR layer changed existing output"
$BF modernize "$W/g32" -o "$W/ofsr32" > "$W/fsr32-off.txt" 2>&1 || fail "rerun without Direct3D FSR"
for f in bifrost_fsr.dll bifrost_fsr.json bifrost_fsr.ini FSR_LICENSE.txt "Play with FSR.bat"; do
    [ ! -e "$W/ofsr32/System/$f" ] || fail "stale Direct3D FSR file $f"
done
$BF modernize "$W/g64/Game64.exe" -o "$W/ofsr64" --fsr --fsr-layer "$W/fsr-layer" > "$W/fsr64.txt" 2>&1 || fail "64-bit Direct3D FSR run"
cmp -s "$W/fsr-layer/x64/bifrost_fsr.dll" "$W/ofsr64/bifrost_fsr.dll" || fail "64-bit FSR layer architecture"
cmp -s "$W/fsr-layer/x64/bifrost_fsr.json" "$W/ofsr64/bifrost_fsr.json" || fail "64-bit FSR layer manifest"
for f in bifrost_fsr.ini FSR_LICENSE.txt "Play with FSR.bat"; do [ -f "$W/ofsr64/$f" ] || fail "missing 64-bit Direct3D FSR file $f"; done
echo "ok   Direct3D FSR layer"

mkdir -p "$W/o32/Saves" && echo slot1 > "$W/o32/Saves/slot1.sav"
echo "volume=10" > "$W/o32/System/Engine.dll.ini"
$BF modernize "$W/g32" -o "$W/o32" --no-dxvk > /dev/null 2>&1 || fail "re-run into a previous output"
[ ! -f "$W/o32/System/d3d9.dll" ] || fail "stale DXVK after re-run"
grep -q slot1 "$W/o32/Saves/slot1.sav" || fail "player files lost on re-run"
grep -q volume=10 "$W/o32/System/Engine.dll.ini" || fail "edited settings lost on re-run"
[ ! -f "$W/o32/System/dxvk.conf" ] || fail "stale dxvk.conf after re-run"
mkdir -p "$W/forged" && touch "$W/forged/bifrost_modernize.txt" && echo keep > "$W/forged/notes.txt"
if $BF modernize "$W/g32" -o "$W/forged" >/dev/null 2>&1; then fail "folder with only a report accepted"; fi
grep -q keep "$W/forged/notes.txt" || fail "forged output touched"
mkdir -p "$W/forged/System" && cp "$W/g64/Game64.exe" "$W/forged/System/Game.exe"
if $BF modernize "$W/g32" -o "$W/forged" >/dev/null 2>&1; then fail "folder with a different exe accepted"; fi
cmp -s "$W/g64/Game64.exe" "$W/forged/System/Game.exe" || fail "unrelated exe overwritten"
grep -q bifrost- <(ls -a "$W") && fail "staging folder left behind"
if $BF modernize "$W/g32" -o "$W/o32" --dxvk "$W/missing.tar.gz" >/dev/null 2>&1; then fail "missing DXVK accepted"; fi
[ -f "$W/o32/Saves/slot1.sav" ] && [ -f "$W/o32/System/Game.exe" ] || fail "failed re-run damaged the previous output"
(cd "$W/g64" && $BF modernize Game64.exe -o "$W/o64cwd" --no-dxvk >/dev/null 2>&1) || fail "exe in the current directory"
if $BF modernize "$W/g32" --exe "$W/g64/Game64.exe" -o "$W/oabs" >/dev/null 2>&1; then fail "--exe outside the game accepted"; fi
mkdir -p "$W/gbad" && cp "$W/g64/Game64.exe" "$W/gbad/a&calc.exe"
$BF modernize "$W/gbad" -o "$W/obad" --no-dxvk >/dev/null 2>&1 || fail "unsafe exe name run"
[ ! -f "$W/obad/bifrost_compat.bat" ] || fail "unsafe exe name written into a .bat"
mkdir -p "$W/busy" && touch "$W/busy/keep"
if $BF modernize "$W/g32" -o "$W/busy" >/dev/null 2>&1; then fail "non-empty output accepted"; fi
if $BF modernize "$W/g32" -o "$W/g32/out" >/dev/null 2>&1; then fail "output inside the game accepted"; fi
[ ! -e "$W/g32/out" ] || fail "rejected output folder was created inside the game"
echo secret > "$W/secret.txt"
ln -s "$W/secret.txt" "$W/g32/leak.txt"; ln -s "$W/g32" "$W/g32/Loop"; ln -s "$W/g64" "$W/g32/Other"
$BF modernize "$W/g32" -o "$W/olink" --no-dxvk >/dev/null 2>&1 || fail "game with links"
[ ! -e "$W/olink/leak.txt" ] && [ ! -e "$W/olink/Loop" ] || fail "links in the game were copied"
if $BF modernize "$W/g32" --exe Other/Game64.exe -o "$W/olink2" >/dev/null 2>&1; then fail "--exe through a link accepted"; fi
mv "$W/olink/System" "$W/elsewhere"; ln -s "$W/elsewhere" "$W/olink/System"; touch "$W/elsewhere/keep.dll"
if $BF modernize "$W/g32" -o "$W/olink" >/dev/null 2>&1; then fail "re-run through a linked output folder accepted"; fi
[ -f "$W/elsewhere/keep.dll" ] && [ ! -f "$W/elsewhere/d3d9.dll" ] || fail "re-run wrote through a link"
rm "$W/g32/leak.txt" "$W/g32/Loop" "$W/g32/Other"
if $BF modernize "$W/g32" -o "$W/missing/../g32/out2" >/dev/null 2>&1; then fail "output inside the game via .. accepted"; fi
[ ! -e "$W/g32/out2" ] || fail "staging created inside the game via .."
ln -s Game64.exe "$W/g64/Linked.exe"
if $BF modernize "$W/g64/Linked.exe" -o "$W/olinkexe" --no-dxvk >/dev/null 2>&1; then fail "linked exe input accepted"; fi
rm "$W/g64/Linked.exe"
mkdir "$W/.ostage.bifrost-staging"; echo mine > "$W/.ostage.bifrost-staging/notes.txt"
$BF modernize "$W/g32" -o "$W/ostage" --no-dxvk >/dev/null 2>&1 || fail "run beside an old staging-named folder"
grep -q mine "$W/.ostage.bifrost-staging/notes.txt" || fail "unrelated staging-named folder deleted"
[ "$before" == "$(cd "$W" && find g32 g64 gdd gd7 gown gtwo -type f -exec sha256sum {} + | sort)" ] || fail "original files changed"
echo "ok   safety checks"
echo "all modernizer checks passed"
