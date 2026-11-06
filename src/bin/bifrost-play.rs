#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(any(windows, test))]
use std::collections::{BTreeMap, BTreeSet};

#[cfg(any(windows, test))]
#[derive(Debug, PartialEq, Eq)]
struct Config {
    exe: String,
    args: String,
    env: BTreeMap<String, String>,
    single_core: bool,
    high_priority: bool,
    controller: bool,
    mouse_speed: u32,
    buttons: BTreeMap<String, String>,
}

#[cfg(any(windows, test))]
impl Default for Config {
    fn default() -> Self {
        Self {
            exe: String::new(),
            args: String::new(),
            env: BTreeMap::new(),
            single_core: false,
            high_priority: false,
            controller: false,
            mouse_speed: 12,
            buttons: BTreeMap::new(),
        }
    }
}

#[cfg(any(windows, test))]
fn insert_case_insensitive(map: &mut BTreeMap<String, String>, key: String, value: String) {
    if let Some(existing) = map.keys().find(|existing| existing.eq_ignore_ascii_case(&key)).cloned() {
        map.remove(&existing);
    }
    map.insert(key, value);
}

#[cfg(any(windows, test))]
fn merge_environment(
    inherited: impl IntoIterator<Item = (String, String)>,
    overrides: impl IntoIterator<Item = (String, String)>,
) -> Vec<(String, String)> {
    let mut vars = Vec::<(String, String)>::new();
    for (key, value) in inherited.into_iter().chain(overrides) {
        if let Some(existing) = vars.iter_mut().find(|(existing, _)| existing.eq_ignore_ascii_case(&key)) {
            *existing = (key, value);
        } else {
            vars.push((key, value));
        }
    }
    vars.sort_by(|(a, _), (b, _)| a.to_ascii_lowercase().cmp(&b.to_ascii_lowercase()).then_with(|| a.cmp(b)));
    vars
}

#[cfg(any(windows, test))]
fn expand_bifrost_dir(value: &str, directory: &str) -> String {
    value.replace("%BIFROST_DIR%", directory)
}

#[cfg(any(windows, test))]
const STICK_DEADZONE: i16 = 8192;

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
        let raw_key = key.trim().to_string();
        let key = raw_key.to_ascii_lowercase();
        let value = value.trim().to_string();
        match section.as_str() {
            "game" if key == "exe" => config.exe = value,
            "game" if key == "args" => config.args = value,
            "env" => insert_case_insensitive(&mut config.env, raw_key, value),
            "timing" if key == "single_core" => config.single_core = value.eq_ignore_ascii_case("true"),
            "timing" if key == "priority" => config.high_priority = value.eq_ignore_ascii_case("high"),
            "controller" if key == "enabled" => config.controller = value.eq_ignore_ascii_case("true"),
            "controller" if key == "mouse_speed" => {
                config.mouse_speed = value.parse().map_err(|_| format!("invalid controller mouse_speed at line {}", line_number + 1))?;
            }
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
    if pad.left_y > STICK_DEADZONE { active.insert("left_up"); }
    if pad.left_y < -STICK_DEADZONE { active.insert("left_down"); }
    if pad.left_x < -STICK_DEADZONE { active.insert("left_left"); }
    if pad.left_x > STICK_DEADZONE { active.insert("left_right"); }
    if pad.right_y > STICK_DEADZONE { active.insert("right_up"); }
    if pad.right_y < -STICK_DEADZONE { active.insert("right_down"); }
    if pad.right_x < -STICK_DEADZONE { active.insert("right_left"); }
    if pad.right_x > STICK_DEADZONE { active.insert("right_right"); }
    if pad.left_trigger > 30 { active.insert("lt"); }
    if pad.right_trigger > 30 { active.insert("rt"); }
    active
}

#[cfg(any(windows, test))]
fn active_bindings(active: &BTreeSet<&'static str>, buttons: &BTreeMap<String, String>) -> BTreeSet<String> {
    active
        .iter()
        .filter_map(|input| buttons.get(*input))
        .filter(|binding| !binding.eq_ignore_ascii_case("none"))
        .map(|binding| binding.to_ascii_lowercase())
        .collect()
}

#[cfg(any(windows, test))]
fn binding_transitions(held: &BTreeSet<String>, now: &BTreeSet<String>) -> (BTreeSet<String>, BTreeSet<String>) {
    (held.difference(now).cloned().collect(), now.difference(held).cloned().collect())
}

#[cfg(any(windows, test))]
fn lowest_affinity_bit(mask: usize) -> Option<usize> {
    (mask != 0).then(|| 1usize << mask.trailing_zeros())
}

#[cfg(any(windows, test))]
fn scale_mouse_axis(value: i16, mouse_speed: u32) -> i32 {
    let value = i64::from(value);
    let deadzone = i64::from(STICK_DEADZONE);
    if mouse_speed == 0 || value.abs() <= deadzone {
        return 0;
    }
    let max_axis = i64::from(i16::MAX);
    let magnitude = value.abs().min(max_axis) - deadzone;
    let range = max_axis - deadzone;
    let pixels = ((magnitude * i64::from(mouse_speed) + range / 2) / range)
        .max(1)
        .min(i64::from(i32::MAX)) as i32;
    if value < 0 { -pixels } else { pixels }
}

fn main() {
    #[cfg(windows)]
    windows::run();
}

#[cfg(windows)]
mod windows {
    use super::{
        active_bindings, active_inputs, binding_transitions, expand_bifrost_dir, lowest_affinity_bit,
        merge_environment, parse_ini, scale_mouse_axis, Config, PadState,
    };
    use std::collections::BTreeSet;
    use std::ffi::{c_void, OsStr};
    use std::mem::zeroed;
    use std::os::windows::ffi::OsStrExt;
    use std::path::PathBuf;
    use std::ptr::{null, null_mut};

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
        fn GetProcessAffinityMask(process: Handle, process_mask: *mut usize, system_mask: *mut usize) -> Bool;
        fn SetProcessAffinityMask(process: Handle, mask: usize) -> Bool;
        fn SetPriorityClass(process: Handle, priority: Dword) -> Bool;
        fn ResumeThread(thread: Handle) -> Dword;
        fn WaitForSingleObject(handle: Handle, millis: Dword) -> Dword;
        fn GetExitCodeProcess(process: Handle, code: *mut Dword) -> Bool;
        fn GetLastError() -> Dword;
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
        fn MessageBoxW(window: Handle, text: *const u16, caption: *const u16, kind: u32) -> i32;
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

    fn load_config(folder: &PathBuf) -> Result<Config, String> {
        let path = folder.join("bifrost_play.ini");
        let text = std::fs::read_to_string(&path).map_err(|error| format!("Could not read {}: {error}", path.display()))?;
        parse_ini(&text).map_err(|error| format!("Invalid {}: {error}", path.display()))
    }

    fn environment(config: &Config, folder: &PathBuf) -> Vec<u16> {
        let directory = folder.to_string_lossy();
        let vars = merge_environment(
            std::env::vars(),
            config.env.iter().map(|(key, value)| (key.clone(), expand_bifrost_dir(value, &directory))),
        );
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

    fn show_error(message: &str) {
        let text = wide(OsStr::new(message));
        let title = wide(OsStr::new("Bifrost"));
        unsafe { MessageBoxW(null_mut(), text.as_ptr(), title.as_ptr(), 0x10); }
    }

    fn apply_single_core(process: Handle) -> Result<(), Dword> {
        let mut process_mask = 0usize;
        let mut system_mask = 0usize;
        if unsafe { GetProcessAffinityMask(process, &mut process_mask, &mut system_mask) } == 0 {
            return Err(unsafe { GetLastError() });
        }
        let Some(mask) = lowest_affinity_bit(process_mask) else {
            return Err(87);
        };
        if unsafe { SetProcessAffinityMask(process, mask) } == 0 {
            return Err(unsafe { GetLastError() });
        }
        Ok(())
    }

    fn show_affinity_warning(error: Dword) {
        let message = format!(
            "Bifrost could not apply the single-core setting (Windows error {error}: {}). The game will start normally.",
            std::io::Error::from_raw_os_error(error as i32)
        );
        let text = wide(OsStr::new(&message));
        let title = wide(OsStr::new("Bifrost"));
        unsafe { MessageBoxW(null_mut(), text.as_ptr(), title.as_ptr(), 0x30); }
    }

    pub(super) fn run() {
        match run_inner() {
            Ok(code) => std::process::exit(code),
            Err(message) => {
                show_error(&message);
                std::process::exit(1);
            }
        }
    }

    fn run_inner() -> Result<i32, String> {
        let executable = std::env::current_exe().map_err(|error| format!("Could not locate the Bifrost launcher: {error}"))?;
        let folder = executable.parent().ok_or_else(|| "Could not locate the Bifrost launcher folder".to_string())?.to_path_buf();
        let config = load_config(&folder)?;
        let game = folder.join(&config.exe);
        if !game.is_file() {
            return Err(format!("Game executable not found: {}", game.display()));
        }
        let game_wide = wide(game.as_os_str());
        let command = format!("{} {}", quote_arg(&game.to_string_lossy()), config.args);
        let mut command_wide = wide(OsStr::new(&command));
        let mut env = environment(&config, &folder);
        let mut directory = wide(folder.as_os_str());
        let mut startup: StartupInfo = unsafe { zeroed() };
        startup.cb = std::mem::size_of::<StartupInfo>() as Dword;
        let mut process: ProcessInfo = unsafe { zeroed() };
        let ok = unsafe {
            CreateProcessW(game_wide.as_ptr(), command_wide.as_mut_ptr(), null(), null(), 0,
                0x00000004 | 0x00000400, env.as_mut_ptr().cast(), directory.as_mut_ptr(), &mut startup, &mut process)
        };
        if ok == 0 {
            let error = unsafe { GetLastError() };
            return Err(format!(
                "Could not start {} (Windows error {error}): {}",
                game.display(),
                std::io::Error::from_raw_os_error(error as i32)
            ));
        }
        if config.single_core {
            if let Err(error) = apply_single_core(process.process) {
                show_affinity_warning(error);
            }
        }
        unsafe {
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
            let now = active_bindings(&active, &config.buttons);
            let (released, pressed) = binding_transitions(&held, &now);
            for binding in released { send_binding(&binding, false); }
            for binding in pressed { send_binding(&binding, true); }
            held = now;
            if in_focus && available {
                send_mouse_move(
                    scale_mouse_axis(state.gamepad.right_x, config.mouse_speed),
                    -scale_mouse_axis(state.gamepad.right_y, config.mouse_speed),
                );
            }
        }
        for binding in held { send_binding(&binding, false); }
        unsafe { CloseHandle(process.process); }
        Ok(exit_code as i32)
    }
}

#[cfg(test)]
mod tests {
    use super::{
        active_bindings, active_inputs, binding_transitions, expand_bifrost_dir, lowest_affinity_bit,
        merge_environment, parse_ini, scale_mouse_axis, PadState,
    };

    #[test]
    fn parses_ini_sections_and_defaults() {
        let config = parse_ini("[game]\nexe=Game.exe\n[env]\nK=V\n[timing]\nsingle_core=true\n[controller]\nenabled=true\n[buttons]\na=Enter\n").unwrap();
        assert_eq!(config.exe, "Game.exe");
        assert_eq!(config.env["K"], "V");
        assert!(config.single_core && config.controller);
        assert_eq!(config.mouse_speed, 12);
        assert_eq!(config.buttons["a"], "Enter");
    }

    #[test]
    fn parses_configured_controller_mouse_speed() {
        let config = parse_ini("[game]\nexe=Game.exe\n[controller]\nmouse_speed=20\n").unwrap();
        assert_eq!(config.mouse_speed, 20);
    }

    #[test]
    fn environment_overrides_match_case_insensitively_and_sort() {
        let config = parse_ini("[game]\nexe=Game.exe\n[env]\nPath=first\nPATH=last\n").unwrap();
        assert_eq!(config.env.len(), 1);
        assert_eq!(config.env["PATH"], "last");
        let vars = merge_environment(
            [("Path".into(), "inherited".into()), ("z".into(), "z".into()), ("alpha".into(), "a".into())],
            config.env,
        );
        assert_eq!(
            vars,
            vec![
                ("alpha".into(), "a".into()),
                ("PATH".into(), "last".into()),
                ("z".into(), "z".into()),
            ]
        );
    }

    #[test]
    fn expands_bifrost_directory_placeholder() {
        assert_eq!(
            expand_bifrost_dir(r"%BIFROST_DIR%\layer;%BIFROST_DIR%", r"C:\Games\Bifrost"),
            r"C:\Games\Bifrost\layer;C:\Games\Bifrost"
        );
    }

    #[test]
    fn stick_deadzone_filters_small_motion() {
        let active = active_inputs(PadState { left_x: 8000, ..PadState::default() });
        assert!(!active.contains("left_right"));
        let active = active_inputs(PadState { left_x: 9000, ..PadState::default() });
        assert!(active.contains("left_right"));
    }

    #[test]
    fn shared_output_bindings_release_after_the_last_input() {
        let config = parse_ini("[game]\nexe=Game.exe\n[buttons]\ndpad_up=Up\nleft_up=uP\n").unwrap();
        let both = active_inputs(PadState { buttons: 0x0001, left_y: 9000, ..PadState::default() });
        let mut held = active_bindings(&both, &config.buttons);
        assert_eq!(held.len(), 1);
        assert!(held.contains("up"));

        let left_only = active_inputs(PadState { left_y: 9000, ..PadState::default() });
        let (released, pressed) = binding_transitions(&held, &active_bindings(&left_only, &config.buttons));
        assert!(released.is_empty());
        assert!(pressed.is_empty());
        held = active_bindings(&left_only, &config.buttons);

        let none = active_inputs(PadState::default());
        let (released, pressed) = binding_transitions(&held, &active_bindings(&none, &config.buttons));
        assert_eq!(released.len(), 1);
        assert!(released.contains("up"));
        assert!(pressed.is_empty());
    }

    #[test]
    fn affinity_uses_lowest_available_processor_bit() {
        assert_eq!(lowest_affinity_bit(0b10100), Some(0b00100));
        assert_eq!(lowest_affinity_bit(0), None);
    }

    #[test]
    fn mouse_speed_scales_right_stick_deflection_after_deadzone() {
        assert_eq!(scale_mouse_axis(32767, 0), 0);
        assert_eq!(scale_mouse_axis(8192, 12), 0);
        assert_eq!(scale_mouse_axis(8193, 12), 1);
        assert_eq!(scale_mouse_axis(32767, 12), 12);
        assert_eq!(scale_mouse_axis(-32768, 12), -12);
        assert_eq!(scale_mouse_axis(32767, 20), 20);
    }
}
