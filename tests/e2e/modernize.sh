#!/usr/bin/env bash
# Modernizer end-to-end: build small 32-bit (Direct3D 8 via an engine DLL) and 64-bit (Direct3D 11) "games" with
# MinGW, modernize them, and check the copy (LAA flag + checksum, DXVK DLLs, dxvk.conf, compat script) and that the
# originals are untouched.
set -euo pipefail
cd "$(dirname "$0")/../.."
cargo build --quiet
BF=$PWD/target/debug/bifrost
W=$(mktemp -d)
fail() { echo "FAIL $*"; exit 1; }
mkdir -p "$W/g32/System" "$W/g64"
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
echo "int main(void){return 0;}" > "$W/unins.c"
i686-w64-mingw32-gcc -shared -O2 -o "$W/g32/System/Engine.dll" "$W/engine.c" -ld3d8 -Wl,--out-implib,"$W/libengine.a"
i686-w64-mingw32-gcc -O2 -mwindows -o "$W/g32/System/Game.exe" "$W/game.c" -L"$W" -lengine
i686-w64-mingw32-gcc -O2 -o "$W/g32/unins000.exe" "$W/unins.c"
x86_64-w64-mingw32-gcc -O2 -mwindows -o "$W/g64/Game64.exe" "$W/game64.c" -ld3d11
before=$(cd "$W" && find g32 g64 -type f -exec sha256sum {} + | sort)

$BF modernize "$W/g32" --scan > "$W/scan.txt"
grep -q '^\* System/Game.exe (32-bit' "$W/scan.txt" || { cat "$W/scan.txt"; fail "main exe detection"; }
grep -q 'Direct3D 8 (System/Engine.dll)' "$W/scan.txt" || fail "API scan through engine DLL"
echo "ok   scan"

$BF modernize "$W/g32" -o "$W/o32" --max-fps 60 > /dev/null 2>&1 || $BF modernize "$W/g32" -o "$W/o32" --max-fps 60
O=$W/o32/System
grep -q "large address aware" <(i686-w64-mingw32-objdump -p "$O/Game.exe") || fail "LAA flag"
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
[ -f "$W/o32/unins000.exe" ] && [ -f "$W/o32/bifrost_modernize.txt" ] || fail "copy/report"
echo "ok   32-bit Direct3D 8 game"

$BF modernize "$W/g64/Game64.exe" -o "$W/o64" --anisotropy 8 --xp-mode > /dev/null 2>&1
[ -f "$W/o64/d3d11.dll" ] && [ -f "$W/o64/dxgi.dll" ] && [ ! -f "$W/o64/d3d9.dll" ] || fail "64-bit DXVK set"
grep -q "x86-64" <(file "$W/o64/d3d11.dll") || fail "64-bit DXVK expected"
grep -q "d3d11.samplerAnisotropy = 8" "$W/o64/dxvk.conf" || fail "64-bit dxvk.conf"
grep -q "WINXPSP3" "$W/o64/bifrost_compat.bat" || fail "XP mode"
cmp -s "$W/g64/Game64.exe" "$W/o64/Game64.exe" || fail "64-bit exe should not be patched"
echo "ok   64-bit Direct3D 11 game"

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
[ "$before" == "$(cd "$W" && find g32 g64 -type f -exec sha256sum {} + | sort)" ] || fail "original files changed"
echo "ok   safety checks"
echo "all modernizer checks passed"
