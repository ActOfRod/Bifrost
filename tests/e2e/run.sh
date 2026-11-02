#!/usr/bin/env bash
# End-to-end: compile the test guest for AArch64, run it under qemu for reference, then push it
# through Bifrost as a loose ELF, an ExeFS PFS0 and an NSP, compile natively (and for Windows when
# MinGW + Wine are available) and compare the output.
set -euo pipefail
cd "$(dirname "$0")/../.."
cargo build --quiet
BF=./target/debug/bifrost
W=$(mktemp -d)
F="-O2 -mno-outline-atomics -ffreestanding -nostdlib"
aarch64-linux-gnu-gcc $F -DLINUX -static -o "$W/ref" tests/e2e/guest.c
aarch64-linux-gnu-gcc $F -fPIE -static-pie -o "$W/guest.elf" tests/e2e/guest.c
set +e; qemu-aarch64 "$W/ref" > "$W/ref.txt"; REF=$?; set -e
python3 tests/e2e/make_fixture.py "$W/guest.elf" "$W/fx"
check() { # name out_dir exe
    set +e; (cd "$2" && timeout 60 $3) 2>&1 | tr -d "\r" > "$W/$1.txt"; local rc=${PIPESTATUS[0]}; set -e
    if [ "$rc" != "$REF" ] || ! diff -q "$W/ref.txt" "$W/$1.txt" >/dev/null; then
        echo "FAIL $1 (exit $rc, want $REF)"; diff "$W/ref.txt" "$W/$1.txt" || true; exit 1
    fi
    echo "ok   $1"
}
$BF build "$W/guest.elf" -o "$W/elf" --compile native >/dev/null && check elf "$W/elf" ./guest
$BF build "$W/fx/exefs.pfs0" -o "$W/pfs0" --compile native >/dev/null && check pfs0 "$W/pfs0" ./BifrostTest
$BF build "$W/fx/game.nsp" -o "$W/nsp" --compile native >/dev/null && check nsp "$W/nsp" ./BifrostTest
if $BF build "$W/fx/game_encrypted.nsp" -o "$W/enc" 2>"$W/enc.txt"; then echo "FAIL encrypted NSP accepted"; exit 1; fi
grep -q "encrypted" "$W/enc.txt" && echo "ok   encrypted NSP rejected"
if command -v x86_64-w64-mingw32-gcc >/dev/null && command -v wine >/dev/null; then
    $BF build "$W/fx/game.nsp" -o "$W/win" --compile windows >/dev/null
    WINEDEBUG=-all check windows "$W/win" "wine BifrostTest.exe"
fi
echo "all e2e checks passed"
