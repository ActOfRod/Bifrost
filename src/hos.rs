//! Public Horizon SVC names. This is a compatibility label table, not a kernel.
//! Only a few benign calls have behavior; everything else stops as unimplemented.

pub fn svc_name(imm: u16) -> Option<&'static str> {
    Some(match imm {
        0x01 => "svcSetHeapSize",
        0x02 => "svcSetMemoryPermission",
        0x03 => "svcSetMemoryAttribute",
        0x04 => "svcMapMemory",
        0x05 => "svcUnmapMemory",
        0x06 => "svcQueryMemory",
        0x07 => "svcExitProcess",
        0x08 => "svcCreateThread",
        0x09 => "svcStartThread",
        0x0A => "svcExitThread",
        0x0B => "svcSleepThread",
        0x0C => "svcGetThreadPriority",
        0x0D => "svcSetThreadPriority",
        0x10 => "svcGetCurrentProcessorNumber",
        0x16 => "svcCloseHandle",
        0x18 => "svcWaitSynchronization",
        0x1E => "svcGetSystemTick",
        0x1F => "svcConnectToNamedPort",
        0x21 => "svcSendSyncRequest",
        0x24 => "svcGetProcessId",
        0x25 => "svcGetThreadId",
        0x26 => "svcBreak",
        0x27 => "svcOutputDebugString",
        0x29 => "svcGetInfo",
        _ => return None,
    })
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SvcEffect {
    Continue,
    DebugString(String),
    Exit,
    Break,
    Unimplemented,
}

pub fn classify_svc(imm: u16) -> SvcEffect {
    match imm {
        0x07 | 0x0A => SvcEffect::Exit,
        0x0B | 0x1E => SvcEffect::Continue,
        0x26 => SvcEffect::Break,
        0x27 => SvcEffect::DebugString(String::new()),
        _ => SvcEffect::Unimplemented,
    }
}
