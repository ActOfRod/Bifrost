#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(any(windows, test))]
use std::collections::{BTreeMap, BTreeSet};

#[cfg(any(windows, test))]
#[derive(Debug, Default, PartialEq, Eq)]
struct Config {
    exe: String,
    args: String,
    env: BTreeMap<String, String>,
    single_core: bool,
    high_priority: bool,
    controller: bool,
    buttons: BTreeMap<String, String>,
}

#[cfg(any(windows, test))]
fn parse_ini(text: &str) -> Result<Config, String> {
    let mut config = Config::default();
    let mut section = String::new();
    for (line_number, line) in text.lines().enumerate() {
        let line = line.trim();
        if line.is_empty() || line.starts_with(';') || line.starts_with('#') {
            continue;
        }
        if line.starts_with('[') && line.ends_with(']') {
            section = line[1..line.len() - 1].trim().to_ascii_lowercase();
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            return Err(format!("invalid INI line {}", line_number + 1));
        };
        let (key, value) = (key.trim().to_ascii_lowercase(), value.trim().to_string());
        match section.as_str() {
            "game" if key == "exe" => config.exe = value,
            "game" if key == "args" => config.args = value,
            "env" => {
                config.env.insert(key, value);
            }
            "timing" if key == "single_core" => config.single_core = value.eq_ignore_ascii_case("true"),
            "timing" if key == "priority" => config.high_priority = value.eq_ignore_ascii_case("high"),
            "controller" if key == "enabled" => config.controller = value.eq_ignore_ascii_case("true"),
            "buttons" => {
                config.buttons.insert(key, value);
            }
            _ => {}
        }
    }
    if config.exe.is_empty() {
        return Err("missing [game] exe".into());
    }
    Ok(config)
}

#[cfg(any(windows, test))]
#[derive(Clone, Copy, Debug, Default)]
struct PadState {
    buttons: u16,
    left_trigger: u8,
    right_trigger: u8,
    left_x: i16,
    left_y: i16,
    right_x: i16,
    right_y: i16,
}

#[cfg(any(windows, test))]
fn active_inputs(pad: PadState) -> BTreeSet<&'static str> {
    const DPAD_UP: u16 = 0x0001;
    const DPAD_DOWN: u16 = 0x0002;
    const DPAD_LEFT: u16 = 0x0004;
    const DPAD_RIGHT: u16 = 0x0008;
    const START: u16 = 0x0010;
    const BACK: u16 = 0x0020;
    const L3: u16 = 0x0040;
    const R3: u16 = 0x0080;
    const LB: u16 = 0x0100;
    const RB: u16 = 0x0200;
    const A: u16 = 0x1000;
    const B: u16 = 0x2000;
    const X: u16 = 0x4000;
    const Y: u16 = 0x8000;
    const DEADZONE: i16 = 8192;
    let mut active = BTreeSet::new();
    for (mask, name) in [
        (DPAD_UP, "dpad_up"), (DPAD_DOWN, "dpad_down"), (DPAD_LEFT, "dpad_left"), (DPAD_RIGHT, "dpad_right"),
        (START, "start"), (BACK, "back"), (L3, "l3"), (R3, "r3"), (LB, "lb"), (RB, "rb"),
        (A, "a"), (B, "b"), (X, "x"), (Y, "y"),
    ] {
        if pad.buttons & mask != 0 {
            active.insert(name);
        }
    }
    if pad.left_y > DEADZONE { active.insert("left_up"); }
    if pad.left_y < -DEADZONE { active.insert("left_down"); }
    if pad.left_x < -DEADZONE { active.insert("left_left"); }
    if pad.left_x > DEADZONE { active.insert("left_right"); }
    if pad.right_y > DEADZONE { active.insert("right_up"); }
    if pad.right_y < -DEADZONE { active.insert("right_down"); }
    if pad.right_x < -DEADZONE { active.insert("right_left"); }
    if pad.right_x > DEADZONE { active.insert("right_right"); }
    if pad.left_trigger > 30 { active.insert("lt"); }
    if pad.right_trigger > 30 { active.insert("rt"); }
    active
}

fn main() {
    #[cfg(windows)]
    if let Err(()) = windows::run() {
        std::process::exit(1);
    }
}

#[cfg(windows)]
mod windows {
    use super::{active_inputs, parse_ini, Config, PadState};
    use std::collections::{BTreeMap, BTreeSet};
    use std::ffi::{c_void, OsStr};
    use std::mem::zeroed;
    use std::os::windows::ffi::OsStrExt;
    use std::path::PathBuf;
    use std::ptr::null;

    type Handle = *mut c_void;
    type Bool = i32;
    type Word = u16;
    type Dword = u32;

    #[repr(C)]
    struct StartupInfo {
        cb: Dword,
        reserved: *mut u16,
        desktop: *mut u16,
        title: *mut u16,
        x: Dword,
        y: Dword,
        x_size: Dword,
        y_size: Dword,
        x_count: Dword,
        y_count: Dword,
        fill: Dword,
        flags: Dword,
        show: Word,
        reserved2_size: Word,
        reserved2: *mut u8,
        stdin: Handle,
        stdout: Handle,
        stderr: Handle,
    }

    #[repr(C)]
    struct ProcessInfo {
        process: Handle,
        thread: Handle,
        process_id: Dword,
        thread_id: Dword,
    }

    #[repr(C)]
    #[derive(Clone, Copy, Default)]
    struct Gamepad {
        buttons: Word,
        left_trigger: u8,
        right_trigger: u8,
        left_x: i16,
        left_y: i16,
        right_x: i16,
        right_y: i16,
    }

    #[repr(C)]
    #[derive(Clone, Copy, Default)]
    struct XInputState {
        packet: Dword,
        gamepad: Gamepad,
    }

    #[repr(C)]
    #[derive(Clone, Copy, Default)]
    struct KeyInput {
        vk: Word,
        scan: Word,
        flags: Dword,
        time: Dword,
        extra: usize,
    }

    #[repr(C)]
    #[derive(Clone, Copy, Default)]
    struct MouseInput {
        dx: i32,
        dy: i32,
        data: Dword,
        flags: Dword,
        time: Dword,
        extra: usize,
    }

    #[repr(C)]
    #[derive(Clone, Copy)]
    union InputData {
        key: KeyInput,
        mouse: MouseInput,
    }

    #[repr(C)]
    #[derive(Clone, Copy)]
    struct Input {
        kind: Dword,
        data: InputData,
    }

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn CreateProcessW(application: *const u16, command: *mut u16, process_attrs: *const c_void, thread_attrs: *const c_void,
            inherit: Bool, flags: Dword, environment: *const c_void, directory: *const u16, startup: *mut StartupInfo,
            process_info: *mut ProcessInfo) -> Bool;
        fn SetProcessAffinityMask(process: Handle, mask: usize) -> Bool;
        fn SetPriorityClass(process: Handle, priority: Dword) -> Bool;
        fn ResumeThread(thread: Handle) -> Dword;
        fn WaitForSingleObject(handle: Handle, millis: Dword) -> Dword;
        fn GetExitCodeProcess(process: Handle, code: *mut Dword) -> Bool;
        fn CloseHandle(handle: Handle) -> Bool;
        fn LoadLibraryW(name: *const u16) -> Handle;
        fn GetProcAddress(module: Handle, name: *const i8) -> *mut c_void;
        fn FreeLibrary(module: Handle) -> Bool;
    }

    #[link(name = "user32")]
    unsafe extern "system" {
        fn GetForegroundWindow() -> Handle;
        fn GetWindowThreadProcessId(window: Handle, process_id: *mut Dword) -> Dword;
        fn SendInput(count: Dword, input: *const Input, size: i32) -> Dword;
    }

    fn wide(s: &OsStr) -> Vec<u16> {
        s.encode_wide().chain(Some(0)).collect()
    }

    fn quote_arg(s: &str) -> String {
        if !s.is_empty() && !s.chars().any(char::is_whitespace) && !s.contains('"') {
            return s.to_string();
        }
        let mut out = String::from("\"");
        let mut slashes = 0;
        for ch in s.chars() {
            if ch == '\\' { slashes += 1; continue; }
            if ch == '"' {
                out.push_str(&"\\".repeat(slashes * 2 + 1));
                out.push('"');
            } else {
                out.push_str(&"\\".repeat(slashes));
                out.push(ch);
            }
            slashes = 0;
        }
        out.push_str(&"\\".repeat(slashes * 2));
        out.push('"');
        out
    }

    fn load_config(folder: &PathBuf) -> Result<Config, ()> {
        let text = std::fs::read_to_string(folder.join("bifrost_play.ini")).map_err(|_| ())?;
        parse_ini(&text).map_err(|_| ())
    }

    fn environment(config: &Config) -> Vec<u16> {
        let mut vars: BTreeMap<String, String> = std::env::vars().collect();
        vars.extend(config.env.iter().map(|(k, v)| (k.clone(), v.clone())));
        let mut block = Vec::new();
        for (key, value) in vars {
            block.extend(OsStr::new(&format!("{key}={value}")).encode_wide());
            block.push(0);
        }
        block.push(0);
        block
    }

    fn key_code(binding: &str) -> Option<Word> {
        let b = binding.to_ascii_lowercase();
        Some(match b.as_str() {
            "enter" => 0x0d, "escape" | "esc" => 0x1b, "space" => 0x20, "tab" => 0x09,
            "shift" => 0x10, "ctrl" | "control" => 0x11, "alt" => 0x12,
            "up" | "up arrow" => 0x26, "down" | "down arrow" => 0x28,
            "left" | "left arrow" => 0x25, "right" | "right arrow" => 0x27,
            _ if b.len() == 1 && b.as_bytes()[0].is_ascii_alphanumeric() => b.as_bytes()[0].to_ascii_uppercase() as Word,
            _ if b.starts_with('f') && b[1..].parse::<u8>().is_ok_and(|n| (1..=12).contains(&n)) =>
                0x70 + b[1..].parse::<u8>().ok()?.saturating_sub(1) as Word,
            _ => return None,
        })
    }

    fn send_binding(binding: &str, down: bool) {
        let b = binding.to_ascii_lowercase();
        let input = if let Some(vk) = key_code(&b) {
            Input { kind: 1, data: InputData { key: KeyInput { vk, flags: if down { 0 } else { 2 }, ..KeyInput::default() } } }
        } else {
            let flags = match b.as_str() {
                "mouse_left" => if down { 0x0002 } else { 0x0004 },
                "mouse_right" => if down { 0x0008 } else { 0x0010 },
                "mouse_middle" => if down { 0x0020 } else { 0x0040 },
                _ => return,
            };
            Input { kind: 0, data: InputData { mouse: MouseInput { flags, ..MouseInput::default() } } }
        };
        unsafe { SendInput(1, &input, std::mem::size_of::<Input>() as i32); }
    }

    fn send_mouse_move(dx: i32, dy: i32) {
        if dx == 0 && dy == 0 { return; }
        let input = Input { kind: 0, data: InputData { mouse: MouseInput { dx, dy, flags: 0x0001, ..MouseInput::default() } } };
        unsafe { SendInput(1, &input, std::mem::size_of::<Input>() as i32); }
    }

    type XInputGetState = unsafe extern "system" fn(Dword, *mut XInputState) -> Dword;

    fn load_xinput() -> Option<(Handle, XInputGetState)> {
        for name in ["xinput1_4.dll", "xinput9_1_0.dll"] {
            let module = unsafe { LoadLibraryW(wide(OsStr::new(name)).as_ptr()) };
            if module.is_null() { continue; }
            let symbol = unsafe { GetProcAddress(module, b"XInputGetState\0".as_ptr().cast()) };
            if !symbol.is_null() {
                return Some((module, unsafe { std::mem::transmute::<*mut c_void, XInputGetState>(symbol) }));
            }
            unsafe { FreeLibrary(module); }
        }
        None
    }

    fn focused(pid: Dword) -> bool {
        let window = unsafe { GetForegroundWindow() };
        if window.is_null() { return false; }
        let mut foreground_pid = 0;
        unsafe { GetWindowThreadProcessId(window, &mut foreground_pid); }
        foreground_pid == pid
    }

    pub(super) fn run() -> Result<(), ()> {
        let folder = std::env::current_exe().map_err(|_| ())?.parent().ok_or(())?.to_path_buf();
        let config = load_config(&folder)?;
        let game = folder.join(&config.exe);
        let game_wide = wide(game.as_os_str());
        let command = format!("{} {}", quote_arg(&game.to_string_lossy()), config.args);
        let mut command_wide = wide(OsStr::new(&command));
        let mut env = environment(&config);
        let mut directory = wide(folder.as_os_str());
        let mut startup: StartupInfo = unsafe { zeroed() };
        startup.cb = std::mem::size_of::<StartupInfo>() as Dword;
        let mut process: ProcessInfo = unsafe { zeroed() };
        let ok = unsafe {
            CreateProcessW(game_wide.as_ptr(), command_wide.as_mut_ptr(), null(), null(), 0,
                0x00000004 | 0x00000400, env.as_mut_ptr().cast(), directory.as_mut_ptr(), &mut startup, &mut process)
        };
        if ok == 0 { return Err(()); }
        unsafe {
            if config.single_core { SetProcessAffinityMask(process.process, 1); }
            SetPriorityClass(process.process, if config.high_priority { 0x00000080 } else { 0x00000020 });
            ResumeThread(process.thread);
            CloseHandle(process.thread);
        }

        let xinput = config.controller.then(load_xinput).flatten();
        let mut held = BTreeSet::<String>::new();
        let mut exit_code = 1;
        loop {
            if unsafe { WaitForSingleObject(process.process, 8) } == 0 {
                unsafe { GetExitCodeProcess(process.process, &mut exit_code); }
                break;
            }
            let Some((_, get_state)) = xinput else { continue };
            let mut state = XInputState::default();
            let available = unsafe { get_state(0, &mut state) } == 0;
            let in_focus = focused(process.process_id);
            let active = if available && in_focus { active_inputs(PadState {
                buttons: state.gamepad.buttons, left_trigger: state.gamepad.left_trigger, right_trigger: state.gamepad.right_trigger,
                left_x: state.gamepad.left_x, left_y: state.gamepad.left_y, right_x: state.gamepad.right_x, right_y: state.gamepad.right_y,
            }) } else { BTreeSet::new() };
            let now: BTreeSet<String> = active.iter().filter(|name| {
                config.buttons.get(**name).is_some_and(|b| !b.eq_ignore_ascii_case("none"))
            }).map(|name| (*name).to_string()).collect();
            for name in held.difference(&now) {
                if let Some(binding) = config.buttons.get(name) { send_binding(binding, false); }
            }
            for name in now.difference(&held) {
                if let Some(binding) = config.buttons.get(name) { send_binding(binding, true); }
            }
            held = now;
            if in_focus && available {
                send_mouse_move(state.gamepad.right_x as i32 / 8192, -(state.gamepad.right_y as i32) / 8192);
            }
        }
        for name in held {
            if let Some(binding) = config.buttons.get(&name) { send_binding(binding, false); }
        }
        unsafe { CloseHandle(process.process); }
        let _ = (env, directory);
        std::process::exit(exit_code as i32)
    }
}

#[cfg(test)]
mod tests {
    use super::{active_inputs, parse_ini, PadState};

    #[test]
    fn parses_ini_sections_and_defaults() {
        let config = parse_ini("[game]\nexe=Game.exe\n[env]\nK=V\n[timing]\nsingle_core=true\n[controller]\nenabled=true\n[buttons]\na=Enter\n").unwrap();
        assert_eq!(config.exe, "Game.exe");
        assert_eq!(config.env["k"], "V");
        assert!(config.single_core && config.controller);
        assert_eq!(config.buttons["a"], "Enter");
    }

    #[test]
    fn stick_deadzone_filters_small_motion() {
        let active = active_inputs(PadState { left_x: 8000, ..PadState::default() });
        assert!(!active.contains("left_right"));
        let active = active_inputs(PadState { left_x: 9000, ..PadState::default() });
        assert!(active.contains("left_right"));
    }
}
