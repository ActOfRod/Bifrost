# Bifrost

Bifrost turns an already-decrypted Nintendo Switch executable into something that can run as a native Windows program. It is the Switch counterpart to a PS5 relinker such as AnyPS5, with one hard difference: PS5 games are already x86-64, so those projects relink and replace system libraries. Switch games are AArch64. Bifrost has to translate the instructions as well as replace Horizon and nnSdk imports.

## What it accepts

Pass an **NSO**, an **NRO**, or an **AArch64 ELF** that is already decrypted. Bifrost maps the executable, applies its relocations, lifts reachable AArch64 into an intermediate form, and can either interpret that form or emit C plus a small runtime.

```
bifrost inspect game.nso
bifrost recompile game.nso -o out
bifrost run game.nro --max-steps 100000
```

`recompile` writes `lifted.c`, `bifrost_rt.c`, `bifrost_rt.h`, `main.c`, `guest_image.bin`, `imports.txt`, and `report.txt`. Compile the C locally:

```
clang -O2 -std=c11 -o bifrost_guest.exe main.c lifted.c bifrost_rt.c
```

Run that exe from the output directory. The generated files are a local derivative of whatever you passed in. Do not redistribute them if the input was copyrighted.

## What it refuses

NSP, XCI, and NCA containers are rejected on sight. Bifrost does not unpack those formats, does not decrypt them, and does not take prod.keys, title keys, or firmware. If you have a game, extract the already-decrypted executable yourself with whatever legal right you have to do that, then point this tool at that NSO, NRO, or ELF.

There is no kernel, no jailbreak, and no GPU. A few benign SVCs (`OutputDebugString`, `SleepThread`, `GetSystemTick`) are recognized. Everything else stops as unimplemented. nnSdk imports are named and given sentinel addresses so calls can be substituted later; they are not implemented.

## Coverage

The lifter currently understands the ordinary integer subset: branches, add/sub, logical operations, wide moves, bitfield moves, conditional selects, multiply-add, and the common load/store forms. Unsupported opcodes halt instead of being guessed. `inspect` reports how many reachable instructions lifted and shows examples of the rest.

The interpreter is the path used to check that lifted code does what the instruction says. The C emitter covers the same subset well enough to compile, and leaves the rest as an explicit stop. Computed jumps go through a block lookup. A real port still needs broader ISA coverage, a Horizon/nnSdk shim, and a graphics path such as NVN to Vulkan.
