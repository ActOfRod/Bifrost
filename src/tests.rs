use sha2::{Digest, Sha256};

use crate::error::Error;
use crate::interp::Stop;
use crate::lift::{decode, AluOp, IndexMode, Op};
use crate::module::{Format, Module, Segment, IMPORT_SENTINEL_BASE};

fn put_u16(buf: &mut [u8], off: usize, value: u16) {
    buf[off..off + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_u32(buf: &mut [u8], off: usize, value: u32) {
    buf[off..off + 4].copy_from_slice(&value.to_le_bytes());
}

fn put_u64(buf: &mut [u8], off: usize, value: u64) {
    buf[off..off + 8].copy_from_slice(&value.to_le_bytes());
}

fn code_module(image: Vec<u8>, text_size: u64) -> Module {
    let mut module = crate::format::empty_module(Format::Nro, "test");
    module.image = image;
    module.text = Segment {
        va: 0,
        size: text_size,
    };
    module.entry = 0;
    module
}

fn run_image(image: Vec<u8>, text_size: u64, args: &[u64]) -> crate::interp::ExecResult {
    let mut module = code_module(image, text_size);
    let lifted = crate::lift::lift(&module);
    crate::interp::execute(&mut module, &lifted, 0, args, 1000, false)
}

fn words(code: &[u32]) -> Vec<u8> {
    code.iter().flat_map(|word| word.to_le_bytes()).collect()
}

fn build_nso(text: &[u8], ro: &[u8], data: &[u8], flags: u32, text_mem: u32, ro_mem: u32, data_mem: u32) -> Vec<u8> {
    let text_file = if flags & 1 != 0 {
        lz4_flex::block::compress(text)
    } else {
        text.to_vec()
    };
    let ro_file = if flags & 2 != 0 {
        lz4_flex::block::compress(ro)
    } else {
        ro.to_vec()
    };
    let data_file = if flags & 4 != 0 {
        lz4_flex::block::compress(data)
    } else {
        data.to_vec()
    };
    let text_off = 0x100u32;
    let ro_off = text_off + text_file.len() as u32;
    let data_off = ro_off + ro_file.len() as u32;
    let mut file = vec![0u8; 0x100];
    file[0..4].copy_from_slice(b"NSO0");
    put_u32(&mut file, 0xC, flags);
    put_u32(&mut file, 0x10, text_off);
    put_u32(&mut file, 0x14, text_mem);
    put_u32(&mut file, 0x18, text.len() as u32);
    put_u32(&mut file, 0x20, ro_off);
    put_u32(&mut file, 0x24, ro_mem);
    put_u32(&mut file, 0x28, ro.len() as u32);
    put_u32(&mut file, 0x30, data_off);
    put_u32(&mut file, 0x34, data_mem);
    put_u32(&mut file, 0x38, data.len() as u32);
    put_u32(&mut file, 0x60, text_file.len() as u32);
    put_u32(&mut file, 0x64, ro_file.len() as u32);
    put_u32(&mut file, 0x68, data_file.len() as u32);
    if flags & 8 != 0 {
        file[0xA0..0xC0].copy_from_slice(&Sha256::digest(text));
    }
    if flags & 16 != 0 {
        file[0xC0..0xE0].copy_from_slice(&Sha256::digest(ro));
    }
    if flags & 32 != 0 {
        file[0xE0..0x100].copy_from_slice(&Sha256::digest(data));
    }
    file.extend_from_slice(&text_file);
    file.extend_from_slice(&ro_file);
    file.extend_from_slice(&data_file);
    file
}

#[test]
fn decodes_known_encodings() {
    assert!(matches!(decode(0xD503_201F, 0), Op::Nop));
    assert!(matches!(decode(0xD65F_03C0, 0), Op::Ret { reg: crate::lift::GReg::X(30) }));
    assert!(matches!(
        decode(0x8B01_0000, 0),
        Op::Alu {
            op: AluOp::Add,
            dst: crate::lift::GReg::X(0),
            rhs: crate::lift::Value::Reg(crate::lift::GReg::X(1)),
            ..
        }
    ));
    assert!(matches!(
        decode(0xAA01_03E0, 0),
        Op::Alu {
            op: AluOp::Or,
            dst: crate::lift::GReg::X(0),
            rhs: crate::lift::Value::Reg(crate::lift::GReg::X(1)),
            ..
        }
    ));
    assert!(matches!(
        decode(0xD100_43FF, 0),
        Op::Alu {
            op: AluOp::Sub,
            dst: crate::lift::GReg::Sp,
            lhs: crate::lift::GReg::Sp,
            rhs: crate::lift::Value::Imm(16),
            ..
        }
    ));
    assert!(matches!(
        decode(0xA9BF_7BFD, 0),
        Op::StorePair {
            rt: crate::lift::GReg::X(29),
            rt2: crate::lift::GReg::X(30),
            base: crate::lift::GReg::Sp,
            offset: -16,
            index: IndexMode::Pre,
            ..
        }
    ));
}

#[test]
fn interprets_add_branch_and_load() {
    let added = run_image(words(&[0x8B01_0000, 0xD65F_03C0]), 8, &[3, 4]);
    assert_eq!(added.x0, 7);
    assert_eq!(added.stop, Stop::Return);

    let branched = run_image(
        words(&[0xD280_0020, 0x1400_0002, 0xD280_0040, 0xD65F_03C0]),
        16,
        &[],
    );
    assert_eq!(branched.x0, 1, "branch should skip the second mov");
    assert_eq!(branched.stop, Stop::Return);

    let mut image = vec![0u8; 0x1008];
    image[..12].copy_from_slice(&words(&[0xB000_0000, 0xF940_0000, 0xD65F_03C0]));
    image[0x1000..0x1008].copy_from_slice(&0x1122_3344_5566_7788u64.to_le_bytes());
    let loaded = run_image(image, 12, &[]);
    assert_eq!(loaded.stop, Stop::Return);
    assert_eq!(loaded.x0, 0x1122_3344_5566_7788);
}

#[test]
fn nso_lz4_roundtrip_and_hash_mismatch() {
    let text = words(&[0xD280_02A0, 0xD65F_03C0]); // movz x0, #21; ret
    let file = build_nso(&text, &[0; 16], &[0; 16], 1 | 8, 0, 0x1000, 0x2000);
    let module = crate::load_bytes("sample.nso", &file).unwrap();
    assert_eq!(module.format, Format::Nso);
    assert_eq!(&module.image[..8], &text);
    let mut module = module;
    let lifted = crate::lift::lift(&module);
    let ran = crate::interp::execute(&mut module, &lifted, 0, &[], 100, false);
    assert_eq!(ran.x0, 21);
    assert_eq!(ran.stop, Stop::Return);

    let mut bad = build_nso(&text, &[0; 16], &[0; 16], 8, 0, 0x1000, 0x2000);
    bad[0xA0] = 0xFF;
    let err = crate::load_bytes("bad.nso", &bad).unwrap_err();
    assert!(err.to_string().contains("SHA-256 mismatch"), "{err}");
}

#[test]
fn nro_roundtrip_with_homebrew_prefix() {
    let magic_off = 0x10;
    let text_off = magic_off + 0x80;
    let code = 0xD65F_03C0u32.to_le_bytes();
    let size = text_off + code.len();
    let mut file = vec![0u8; size];
    file[magic_off..magic_off + 4].copy_from_slice(b"NRO0");
    put_u32(&mut file, magic_off + 8, size as u32);
    put_u32(&mut file, magic_off + 0x10, text_off as u32);
    put_u32(&mut file, magic_off + 0x14, code.len() as u32);
    file[text_off..].copy_from_slice(&code);
    let module = crate::load_bytes("homebrew.nro", &file).unwrap();
    assert_eq!(module.format, Format::Nro);
    assert_eq!(module.entry, text_off as u64);
    assert_eq!(module.read_u32(module.entry), Some(0xD65F_03C0));
}

#[test]
fn elf_keeps_nonzero_base() {
    let code_off = 120usize;
    let code = 0xD65F_03C0u32.to_le_bytes();
    let filesz = code_off + code.len();
    let base = 0x8000_0000u64;
    let mut file = vec![0u8; filesz];
    file[0..4].copy_from_slice(b"\x7fELF");
    file[4] = 2;
    file[5] = 1;
    file[6] = 1;
    put_u16(&mut file, 16, 3);
    put_u16(&mut file, 18, 183);
    put_u32(&mut file, 20, 1);
    put_u64(&mut file, 24, base + code_off as u64);
    put_u64(&mut file, 32, 64);
    put_u16(&mut file, 52, 64);
    put_u16(&mut file, 54, 56);
    put_u16(&mut file, 56, 1);
    put_u32(&mut file, 64, 1);
    put_u32(&mut file, 68, 5);
    put_u64(&mut file, 80, base);
    put_u64(&mut file, 88, base);
    put_u64(&mut file, 96, filesz as u64);
    put_u64(&mut file, 104, filesz as u64);
    file[code_off..].copy_from_slice(&code);

    let mut module = crate::load_bytes("mod.elf", &file).unwrap();
    assert_eq!(module.format, Format::Elf);
    assert_eq!(module.image_base, base);
    assert_eq!(module.entry, base + code_off as u64);
    let entry = module.entry;
    let lifted = crate::lift::lift(&module);
    let ran = crate::interp::execute(&mut module, &lifted, entry, &[], 10, false);
    assert_eq!(ran.stop, Stop::Return);
}

#[test]
fn jump_slot_becomes_import_sentinel() {
    let mut text = vec![0u8; 0x20];
    put_u32(&mut text, 0, 0xD65F_03C0);
    put_u32(&mut text, 4, 0x1000);
    let mut ro = vec![0u8; 0x200];
    ro[0..4].copy_from_slice(b"MOD0");
    put_u32(&mut ro, 4, 0x40);
    let dyn_at = 0x40;
    let mut dyn_off = dyn_at;
    for (tag, val) in [
        (5i64, 0x1100u64),
        (10, 32),
        (6, 0x1140),
        (23, 0x1180),
        (2, 24),
        (0, 0),
    ] {
        put_u64(&mut ro, dyn_off, tag as u64);
        put_u64(&mut ro, dyn_off + 8, val);
        dyn_off += 16;
    }
    ro[0x100..0x110].copy_from_slice(b"\0nn_test_import\0");
    put_u32(&mut ro, 0x140, 1);
    ro[0x144] = 2;
    let mut rela = [0u8; 24];
    put_u64(&mut rela, 0, 0x2000);
    put_u64(&mut rela, 8, 1026);
    ro[0x180..0x198].copy_from_slice(&rela);

    let mut file = build_nso(&text, &ro, &[0; 16], 0, 0, 0x1000, 0x2000);
    put_u32(&mut file, 0x90, 0x100);
    put_u32(&mut file, 0x94, 32);
    put_u32(&mut file, 0x98, 0x140);
    put_u32(&mut file, 0x9C, 24);

    let module = crate::load_bytes("imports.nso", &file).unwrap();
    assert_eq!(module.imports.len(), 1, "{:?}", module.notes);
    assert_eq!(module.imports[0].name, "nn_test_import");
    assert_eq!(module.imports[0].sentinel, IMPORT_SENTINEL_BASE);
    assert_eq!(module.read_u64(0x2000), Some(IMPORT_SENTINEL_BASE));

    let lifted = crate::lift::lift(&module);
    let dir = std::env::temp_dir().join(format!("bifrost-emit-{}", std::process::id()));
    let report = crate::emit(&module, &lifted, &dir).unwrap();
    let lifted_c = std::fs::read_to_string(dir.join("lifted.c")).unwrap();
    assert!(lifted_c.contains("nn_test_import"), "{lifted_c}");
    assert!(report.imports == 1);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn refuses_encrypted_containers() {
    let cases: &[(&[u8], &str)] = &[
        (b"PFS0rest", "PFS0"),
        (b"NCA3rest", "NCA3"),
        (b"HFS0rest", "HFS0"),
    ];
    for (bytes, magic) in cases {
        let err = crate::load_bytes("dump", bytes).unwrap_err();
        match err {
            Error::Container { magic: ref found } => assert_eq!(found, magic),
            other => panic!("expected container refusal, got {other}"),
        }
        assert!(err.to_string().contains("does not decrypt"));
    }
    let mut xci = vec![0u8; 0x104];
    xci[0x100..0x104].copy_from_slice(b"HEAD");
    assert!(matches!(
        crate::load_bytes("game.xci", &xci),
        Err(Error::Container { .. })
    ));
    assert!(matches!(
        crate::load_bytes("nope.bin", b"XXXX"),
        Err(Error::BadMagic { .. })
    ));
}

fn linked_module(name: &str, text: &[u32]) -> Module {
    let mut m = code_module(words(text), text.len() as u64 * 4);
    m.format = Format::Nso;
    m.name = name.into();
    m.image.resize(0x2000, 0);
    m.data = Segment { va: 0x1000, size: 0x1000 };
    m
}

#[test]
fn links_modules_in_horizon_order_and_resolves_cross_module_imports() {
    use crate::module::{Import, Symbol, SymbolKind};
    let mut main = linked_module("main", &[0xD65F_03C0]);
    main.imports.push(Import { name: "nn_shared".into(), slot_va: Some(0x1000), sentinel: IMPORT_SENTINEL_BASE });
    main.imports.push(Import { name: "nn_missing".into(), slot_va: Some(0x1008), sentinel: IMPORT_SENTINEL_BASE + 1 });
    let mut sdk = linked_module("sdk", &[0xD280_0540, 0xD65F_03C0]);
    sdk.symbols.push(Symbol { name: "nn_shared".into(), va: 0x4, size: 4, defined: true, kind: SymbolKind::Func });
    let sub = linked_module("subsdk1", &[0xD65F_03C0]);
    let program = crate::link::link(vec![sdk, sub, main]);
    let names: Vec<_> = program.modules.iter().map(|m| m.name.as_str()).collect();
    assert_eq!(names, ["main", "subsdk1", "sdk"]);
    assert_eq!(program.modules[0].image_base, crate::link::CODE_BASE);
    assert!(program.modules.windows(2).all(|w| w[1].image_base > w[0].image_base && w[1].image_base % 0x20_0000 == 0));
    let sdk_base = program.modules[2].image_base;
    let main = &program.modules[0];
    assert_eq!(main.read_u64(main.image_base + 0x1000), Some(sdk_base + 4));
    assert_eq!(program.resolved_imports, 1);
    assert_eq!(program.imports.len(), 1);
    assert_eq!(main.read_u64(main.image_base + 0x1008), Some(program.imports[0].0));
    let entries = program.entry_points();
    assert!(entries.contains(&(sdk_base + 4)), "data pointer into code becomes an entry");
    let lifted = crate::lift::cfg::lift_program(&program, &entries);
    let gen = crate::codegen::generate(&lifted);
    assert!(gen.header.contains(&format!("f_{:x}", sdk_base + 4)));
    let mut addrs: Vec<u64> = lifted.functions.keys().copied().collect();
    addrs.sort();
    assert_eq!(addrs, lifted.functions.keys().copied().collect::<Vec<_>>());
}

#[test]
fn applies_ips_ips32_and_pchtxt_patches() {
    let dir = std::env::temp_dir().join(format!("bifrost-patch-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let mut modules = vec![linked_module("main", &[0, 0, 0, 0])];
    modules[0].module_id[0] = 0xAB;
    let ips = dir.join("main.ips");
    let mut f = b"PATCH".to_vec();
    f.extend_from_slice(&[0x00, 0x01, 0x00, 0x00, 0x04, 0xC0, 0x03, 0x5F, 0xD6]);
    f.extend_from_slice(&[0x00, 0x01, 0x08, 0x00, 0x00, 0x00, 0x02, 0x7F]);
    f.extend_from_slice(b"EOF");
    std::fs::write(&ips, f).unwrap();
    crate::intake::apply_patch(&mut modules, &ips).unwrap();
    assert_eq!(modules[0].read_u32(0), Some(0xD65F_03C0));
    assert_eq!(modules[0].read(8, 3), Some(&[0x7F, 0x7F, 0][..]));
    let ips32 = dir.join(format!("AB{}.ips", "0".repeat(62)));
    let mut f = b"IPS32".to_vec();
    f.extend_from_slice(&[0, 0, 0x01, 0x04, 0x00, 0x01, 0x55]);
    f.extend_from_slice(b"EEOF");
    std::fs::write(&ips32, f).unwrap();
    crate::intake::apply_patch(&mut modules, &ips32).unwrap();
    assert_eq!(modules[0].read(4, 1), Some(&[0x55][..]));
    let pch = dir.join("p.pchtxt");
    std::fs::write(&pch, "@nsobid-AB00\n@flag offset_shift 0x100\n@enabled\n0000000C 1F2003D5 // nop\n@stop\n0000000C FFFFFFFF\n").unwrap();
    crate::intake::apply_patch(&mut modules, &pch).unwrap();
    assert_eq!(modules[0].read_u32(0xC), Some(0xD503_201F));
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn parses_pfs0_images() {
    let mut f = b"PFS0".to_vec();
    f.extend_from_slice(&2u32.to_le_bytes());
    f.extend_from_slice(&16u32.to_le_bytes());
    f.extend_from_slice(&0u32.to_le_bytes());
    for (off, size, name) in [(0u64, 3u64, 0u32), (3, 2, 5)] {
        f.extend_from_slice(&off.to_le_bytes());
        f.extend_from_slice(&size.to_le_bytes());
        f.extend_from_slice(&name.to_le_bytes());
        f.extend_from_slice(&0u32.to_le_bytes());
    }
    f.extend_from_slice(b"main\0sdk\0\0\0\0\0\0\0\0");
    f.extend_from_slice(b"abcde");
    let files = crate::intake::parse_pfs0_bytes(&f).unwrap();
    assert_eq!(files, vec![("main".to_string(), b"abc".to_vec()), ("sdk".to_string(), b"de".to_vec())]);
}

#[test]
fn romfs_directory_round_trip() {
    let root = std::env::temp_dir().join(format!("bifrost_romfs_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(root.join("Model/Field")).unwrap();
    std::fs::create_dir_all(root.join("Sound")).unwrap();
    std::fs::write(root.join("Model/Field/island.bfres"), vec![7u8; 1000]).unwrap();
    std::fs::write(root.join("Model/a.txt"), b"alpha").unwrap();
    std::fs::write(root.join("Sound/bgm.bfstm"), b"music!").unwrap();
    std::fs::write(root.join("top.bin"), b"").unwrap();
    let image = root.with_extension("romfs");
    let size = crate::romfs::build_from_dir(&root, &image, &mut |_, _| {}).unwrap();
    assert_eq!(size, std::fs::metadata(&image).unwrap().len());
    let read = |p: &str| crate::romfs::read_file(&image, p).unwrap();
    assert_eq!(read("Model/Field/island.bfres"), Some(vec![7u8; 1000]));
    assert_eq!(read("Model/a.txt").as_deref(), Some(&b"alpha"[..]));
    assert_eq!(read("Sound/bgm.bfstm").as_deref(), Some(&b"music!"[..]));
    assert_eq!(read("top.bin"), Some(Vec::new()));
    assert_eq!(read("Sound/missing"), None);
    assert!(matches!(crate::intake::dlc_romfs(&image).unwrap(), crate::intake::RomfsSource::Image(_)));
    assert!(matches!(crate::intake::dlc_romfs(&root).unwrap(), crate::intake::RomfsSource::Dir(_)));
    std::fs::remove_dir_all(&root).unwrap();
    std::fs::remove_file(&image).unwrap();
}

#[test]
fn romfs_hash_matches_horizon() {
    assert_eq!(crate::romfs::hash_table_len(0), 3);
    assert_eq!(crate::romfs::hash_table_len(10), 11);
    assert_eq!(crate::romfs::hash_table_len(20), 23);
    assert_eq!(crate::romfs::path_hash(0, b""), 123_456_789);
}

#[test]
fn romfs_only_input_is_explained() {
    let root = std::env::temp_dir().join(format!("bifrost_romfs_only_{}", std::process::id())).join("romfs");
    std::fs::create_dir_all(&root).unwrap();
    std::fs::write(root.join("a.bin"), b"x").unwrap();
    let opts = crate::pipeline::BuildOptions { inputs: vec![root.clone()], out: root.with_file_name("out"), ..Default::default() };
    let err = crate::pipeline::run(&opts, &mut |_| {}).unwrap_err().to_string();
    assert!(err.contains("ExeFS"), "{err}");
    std::fs::remove_dir_all(root.parent().unwrap()).unwrap();
}
