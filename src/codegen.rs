//! Function-level C generation from recovered control flow.

use std::collections::BTreeSet;
use std::fmt::Write;

use crate::lift::cfg::Lifted;
use crate::lift::decode::Op;

pub struct GeneratedC {
    pub header: String,
    pub units: Vec<String>,
    pub dispatch: String,
}

const FUNCS_PER_UNIT: usize = 400;

pub fn generate(lifted: &Lifted) -> GeneratedC {
    let mut header = String::from("#pragma once\n#include \"bf_cpu.h\"\n");
    for entry in lifted.functions.keys() {
        let _ = writeln!(header, "void f_{entry:x}(BfCpu *c);");
    }
    let fns: Vec<_> = lifted.functions.iter().collect();
    let units = fns
        .chunks(FUNCS_PER_UNIT)
        .map(|chunk| {
            let mut s = String::from("#include \"bf_funcs.h\"\n\n");
            for (entry, leaders) in chunk {
                emit_function(&mut s, lifted, **entry, leaders);
            }
            s
        })
        .collect();
    let mut seen = BTreeSet::new();
    let mut rows = Vec::new();
    for (entry, leaders) in &lifted.functions {
        for l in leaders {
            if seen.insert(*l) {
                rows.push((*l, *entry));
            }
        }
    }
    rows.sort_unstable();
    let mut dispatch = String::from("#include \"bf_funcs.h\"\n#include \"bf_runtime.h\"\n\nconst BfEntry bf_entries[] = {\n");
    for (addr, f) in &rows {
        let _ = writeln!(dispatch, "    {{0x{addr:x}ull, f_{f:x}}},");
    }
    if rows.is_empty() {
        dispatch.push_str("    {0, 0},\n");
    }
    let _ = writeln!(dispatch, "}};\nconst size_t bf_entry_count = {};", rows.len());
    GeneratedC { header, units, dispatch }
}

fn emit_function(s: &mut String, lifted: &Lifted, entry: u64, leaders: &BTreeSet<u64>) {
    let _ = writeln!(s, "void f_{entry:x}(BfCpu *c) {{");
    if leaders.len() > 1 {
        s.push_str("    switch (c->pc) {\n");
        for l in leaders {
            if *l != entry {
                let _ = writeln!(s, "    case 0x{l:x}ull: goto L_{l:x};");
            }
        }
        s.push_str("    default: break;\n    }\n");
    }
    let has_label = |t: u64| leaders.contains(&t);
    let mut order: Vec<u64> = leaders.iter().copied().collect();
    order.sort_unstable_by_key(|&l| (l != entry, l));
    for &leader in &order {
        let _ = writeln!(s, "L_{leader:x}:;");
        let mut pc = leader;
        loop {
            let Some(insn) = lifted.insns.get(&pc) else {
                let _ = writeln!(s, "    c->pc = 0x{pc:x}ull; return;");
                break;
            };
            let next = pc + 4;
            let jump = |t: u64| {
                if has_label(t) {
                    format!("goto L_{t:x};")
                } else {
                    format!("{{ c->pc = 0x{t:x}ull; return; }}")
                }
            };
            match &insn.op {
                Op::C(code) => {
                    let _ = writeln!(s, "    {code}");
                }
                Op::B { target } => {
                    let _ = writeln!(s, "    {}", jump(*target));
                }
                Op::CondBranch { cond, target } => {
                    let _ = writeln!(s, "    if ({cond}) {}", jump(*target));
                }
                Op::Bl { target } => {
                    let call = if lifted.functions.contains_key(target) {
                        format!("f_{target:x}(c);")
                    } else {
                        "bf_call(c);".to_string()
                    };
                    let _ = writeln!(
                        s,
                        "    c->x[30] = 0x{next:x}ull; c->pc = 0x{target:x}ull; {call} if (c->pc != 0x{next:x}ull) bf_dispatch_until(c, 0x{next:x}ull);"
                    );
                }
                Op::Blr { reg } => {
                    let _ = writeln!(
                        s,
                        "    c->pc = c->x[{reg}]; c->x[30] = 0x{next:x}ull; bf_call(c); if (c->pc != 0x{next:x}ull) bf_dispatch_until(c, 0x{next:x}ull);"
                    );
                }
                Op::Br { reg } => {
                    if let Some(targets) = lifted.jump_tables.get(&pc) {
                        let _ = writeln!(s, "    switch (c->x[{reg}]) {{");
                        for t in targets {
                            if has_label(*t) {
                                let _ = writeln!(s, "    case 0x{t:x}ull: goto L_{t:x};");
                            }
                        }
                        s.push_str("    default: break;\n    }\n");
                    }
                    let _ = writeln!(s, "    c->pc = c->x[{reg}]; return;");
                }
                Op::Ret { reg } => {
                    let _ = writeln!(s, "    c->pc = c->x[{reg}]; return;");
                }
                Op::Svc { imm } => {
                    let _ = writeln!(s, "    c->pc = 0x{next:x}ull; bf_svc(c, {imm}, 0x{pc:x}ull);");
                }
                Op::Brk { imm } => {
                    let _ = writeln!(s, "    c->pc = 0x{pc:x}ull; bf_brk(c, {imm}, 0x{pc:x}ull); return;");
                }
                Op::Udf | Op::Unsupported => {
                    let _ = writeln!(s, "    c->pc = 0x{pc:x}ull; bf_unimpl(c, 0x{pc:x}ull, 0x{:08x}u); return;", insn.word);
                }
            }
            if insn.op.is_terminator() {
                break;
            }
            pc = next;
            if has_label(pc) {
                let _ = writeln!(s, "    goto L_{pc:x};");
                break;
            }
        }
    }
    s.push_str("}\n\n");
}
