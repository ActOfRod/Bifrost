#![cfg_attr(windows, windows_subsystem = "windows")]
//! Bifrost desktop front end. Pick a task:
//! - Recomp: input/output systems and content, then Begin Recomp.
//! - Modernize: an older Windows game and the upgrades to apply, then Begin Modernization.
use bifrost::modernize::{self, Api, Arch, ApiReport, Compat, DxvkSource, ModernizeOptions, Scan, Upscale};
use bifrost::pipeline::{self, BuildOptions, Event, InputSystem, Target};
use eframe::egui;
use std::path::PathBuf;
use std::sync::mpsc;

#[derive(Clone, Copy, PartialEq, Eq)]
enum Task {
    Recomp,
    Modernize,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Output {
    Windows,
    Android,
}

impl Output {
    fn label(self) -> &'static str {
        match self {
            Output::Windows => "Windows (x86-64 .exe)",
            Output::Android => "Android (arm64 + x86_64 .apk)",
        }
    }
}

enum Status {
    Idle,
    Running { progress: f32, step: String },
    Done { artifact: Option<PathBuf>, project: PathBuf, summary: String },
    Failed(String),
}

/// Modernize task state.
struct Modern {
    game: Option<PathBuf>,
    scan: Option<Result<Scan, String>>,
    exe: usize,
    apis: ApiReport,
    dxvk: bool,
    dxvk_local: Option<PathBuf>,
    anisotropy: u32,
    cap_fps: bool,
    max_fps: u32,
    laa: bool,
    compat: Compat,
    apply_compat: bool,
    ddraw: bool,
    upscale: Upscale,
    out_dir: String,
}

impl Default for Modern {
    fn default() -> Self {
        let d = ModernizeOptions::default();
        let out = std::env::current_dir().map(|c| c.join("modernized")).unwrap_or_else(|_| "modernized".into());
        Modern {
            game: None,
            scan: None,
            exe: 0,
            apis: ApiReport::default(),
            dxvk: true,
            dxvk_local: None,
            anisotropy: d.anisotropy,
            cap_fps: false,
            max_fps: 60,
            laa: d.large_address_aware,
            compat: d.compat,
            apply_compat: false,
            ddraw: true,
            upscale: Upscale::Off,
            out_dir: out.display().to_string(),
        }
    }
}

impl Modern {
    fn set_game(&mut self, p: PathBuf) {
        self.scan = Some(modernize::scan(&p).map_err(|e| format!("{e:#}")));
        self.exe = match &self.scan {
            Some(Ok(s)) => s.main.unwrap_or(0),
            _ => 0,
        };
        self.game = Some(p);
        self.rescan_apis();
    }

    fn rescan_apis(&mut self) {
        self.apis = match &self.scan {
            Some(Ok(s)) => modernize::detect_apis(&s.root, &s.exes[self.exe].path),
            _ => ApiReport::default(),
        };
    }

    fn exe(&self) -> Option<&modernize::ExeCandidate> {
        match &self.scan {
            Some(Ok(s)) => s.exes.get(self.exe),
            _ => None,
        }
    }

    fn uses_d3d(&self) -> bool {
        self.apis.apis.keys().any(|a| matches!(a, Api::Direct3D8 | Api::Direct3D9 | Api::Direct3D10 | Api::Direct3D11 | Api::Dxgi))
    }
}

struct App {
    task: Task,
    modern: Modern,
    system: InputSystem,
    output: Output,
    base: Vec<PathBuf>,
    updates: Vec<PathBuf>,
    patches: Vec<PathBuf>,
    romfs: Option<PathBuf>,
    dlc_enabled: bool,
    dlc: Vec<PathBuf>,
    out_dir: String,
    android_sdk: String,
    status: Status,
    log: Vec<String>,
    rx: Option<mpsc::Receiver<Msg>>,
}

enum Msg {
    Event(Event),
    Finished(Result<Finished, String>),
}

struct Finished {
    artifact: Option<PathBuf>,
    project: PathBuf,
    summary: String,
}

impl Default for App {
    fn default() -> Self {
        let sdk = std::env::var("ANDROID_HOME").or_else(|_| std::env::var("ANDROID_SDK_ROOT")).unwrap_or_default();
        let out = std::env::current_dir().map(|d| d.join("bifrost-out")).unwrap_or_else(|_| "bifrost-out".into());
        App {
            task: Task::Recomp,
            modern: Modern::default(),
            system: InputSystem::Switch,
            output: Output::Windows,
            base: Vec::new(),
            updates: Vec::new(),
            patches: Vec::new(),
            romfs: None,
            dlc_enabled: false,
            dlc: Vec::new(),
            out_dir: out.display().to_string(),
            android_sdk: sdk,
            status: Status::Idle,
            log: Vec::new(),
            rx: None,
        }
    }
}

const CONTENT_EXTS: &[&str] = &["nsp", "xci", "nca", "nso", "nro", "elf", "bin", "romfs", "img"];

/// A list of paths with Add file / Add folder / clear controls. `id` keeps the widgets distinct.
fn path_list(ui: &mut egui::Ui, id: &str, paths: &mut Vec<PathBuf>, filters: &[&str], enabled: bool) {
    ui.add_enabled_ui(enabled, |ui| {
        let mut remove = None;
        for (i, p) in paths.iter().enumerate() {
            ui.horizontal(|ui| {
                if ui.small_button("✖").on_hover_text("Remove").clicked() {
                    remove = Some(i);
                }
                ui.label(p.display().to_string());
            });
        }
        if let Some(i) = remove {
            paths.remove(i);
        }
        ui.horizontal(|ui| {
            if ui.button("Add file…").clicked() {
                let mut d = rfd::FileDialog::new();
                if !filters.is_empty() {
                    d = d.add_filter("Content", filters).add_filter("All files", &["*"]);
                }
                if let Some(files) = d.pick_files() {
                    paths.extend(files);
                }
            }
            if ui.button("Add folder…").clicked() {
                if let Some(dir) = rfd::FileDialog::new().pick_folder() {
                    paths.push(dir);
                }
            }
            ui.push_id(id, |ui| {
                let field = ui.make_persistent_id("typed_path");
                let mut typed: String = ui.data_mut(|d| d.get_temp(field).unwrap_or_default());
                let r = ui.add(egui::TextEdit::singleline(&mut typed).hint_text("or paste a path and press Enter").desired_width(260.0));
                if r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) && !typed.trim().is_empty() {
                    paths.push(PathBuf::from(typed.trim()));
                    typed.clear();
                }
                ui.data_mut(|d| d.insert_temp(field, typed));
            });
        });
    });
}

impl App {
    fn running(&self) -> bool {
        matches!(self.status, Status::Running { .. })
    }

    fn begin(&mut self, ctx: &egui::Context) {
        let opts = BuildOptions {
            system: self.system,
            inputs: self.base.clone(),
            updates: self.updates.clone(),
            patches: self.patches.clone(),
            dlc: if self.dlc_enabled { self.dlc.clone() } else { Vec::new() },
            romfs: self.romfs.clone(),
            out: PathBuf::from(self.out_dir.trim()),
            target: match self.output {
                Output::Windows => Target::Windows,
                Output::Android => Target::Android,
            },
            android_sdk: Some(self.android_sdk.trim()).filter(|s| !s.is_empty()).map(PathBuf::from),
            ..Default::default()
        };
        self.spawn(ctx, move |sink| {
            let out = pipeline::run(&opts, sink)?;
            let r = &out.report;
            let summary = format!(
                "{}: {} functions, {} instructions ({} unsupported), {} imports unresolved",
                out.title, r.functions, r.instructions, r.unsupported, r.imports
            );
            Ok(Finished { artifact: out.artifact, project: out.project, summary })
        });
    }

    fn begin_modernize(&mut self, ctx: &egui::Context) {
        let m = &self.modern;
        let opts = ModernizeOptions {
            game: m.game.clone().unwrap_or_default(),
            exe: m.exe().map(|e| e.path.clone()),
            out: PathBuf::from(m.out_dir.trim()),
            dxvk: m.dxvk.then(|| m.dxvk_local.clone().map_or(DxvkSource::Download, DxvkSource::Local)),
            anisotropy: m.anisotropy,
            max_fps: if m.cap_fps { m.max_fps } else { 0 },
            large_address_aware: m.laa,
            compat: m.compat.clone(),
            apply_compat: m.apply_compat,
            ddraw: m.ddraw.then_some(DxvkSource::Download),
            upscale: if m.ddraw || m.dxvk { m.upscale } else { Upscale::Off },
            fsr_layer: None,
        };
        self.spawn(ctx, move |sink| {
            let out = modernize::run(&opts, sink)?;
            let summary = format!("{} changes, {} warnings. Run {}", out.changes.len(), out.warnings.len(), out.exe.display());
            Ok(Finished { artifact: Some(out.exe), project: out.out, summary })
        });
    }

    fn spawn<F>(&mut self, ctx: &egui::Context, job: F)
    where
        F: FnOnce(&mut dyn FnMut(Event)) -> anyhow::Result<Finished> + Send + 'static,
    {
        let (tx, rx) = mpsc::channel();
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            let etx = tx.clone();
            let ectx = ctx.clone();
            let result = job(&mut |ev| {
                let _ = etx.send(Msg::Event(ev));
                ectx.request_repaint();
            });
            let _ = tx.send(Msg::Finished(result.map_err(|e| format!("{e:#}"))));
            ctx.request_repaint();
        });
        self.rx = Some(rx);
        self.log.clear();
        self.status = Status::Running { progress: 0.0, step: "Starting".into() };
    }

    fn recomp_form(&mut self, ui: &mut egui::Ui) {
        egui::Grid::new("systems").num_columns(2).spacing([16.0, 8.0]).show(ui, |ui| {
            ui.label("Input system");
            egui::ComboBox::from_id_salt("input_system").selected_text("Nintendo Switch").width(260.0).show_ui(ui, |ui| {
                ui.selectable_value(&mut self.system, InputSystem::Switch, "Nintendo Switch");
            });
            ui.end_row();
            ui.label("Output system");
            egui::ComboBox::from_id_salt("output_system").selected_text(self.output.label()).width(260.0).show_ui(ui, |ui| {
                ui.selectable_value(&mut self.output, Output::Windows, Output::Windows.label());
                ui.selectable_value(&mut self.output, Output::Android, Output::Android.label());
            });
            ui.end_row();
        });
        ui.add_space(8.0);
        ui.strong("Base game");
        ui.label("Decrypted NSP/XCI/NCA, an exefs folder, or a folder with exefs/ and romfs/.");
        path_list(ui, "base", &mut self.base, CONTENT_EXTS, true);
        ui.add_space(6.0);
        ui.strong("Updates (optional, applied in order)");
        path_list(ui, "updates", &mut self.updates, CONTENT_EXTS, true);
        ui.add_space(6.0);
        ui.strong("Patches (optional: IPS, IPS32, pchtxt)");
        path_list(ui, "patches", &mut self.patches, &["ips", "pchtxt"], true);
        ui.add_space(6.0);
        ui.strong("RomFS (optional: overrides the one in the base game)");
        ui.horizontal(|ui| {
            ui.label(self.romfs.as_ref().map_or("from base game".to_string(), |p| p.display().to_string()));
            if ui.button("Image…").clicked() {
                if let Some(f) = rfd::FileDialog::new().pick_file() {
                    self.romfs = Some(f);
                }
            }
            if ui.button("Folder…").clicked() {
                if let Some(d) = rfd::FileDialog::new().pick_folder() {
                    self.romfs = Some(d);
                }
            }
            if self.romfs.is_some() && ui.button("Clear").clicked() {
                self.romfs = None;
            }
        });
        ui.add_space(6.0);
        ui.checkbox(&mut self.dlc_enabled, "Include DLC");
        if self.dlc_enabled {
            ui.label("Dumped add-on content: NSP/NCA (plaintext), RomFS image, or extracted RomFS folder.");
            path_list(ui, "dlc", &mut self.dlc, CONTENT_EXTS, true);
        }
        ui.add_space(6.0);
        egui::Grid::new("paths").num_columns(3).spacing([8.0, 6.0]).show(ui, |ui| {
            ui.label("Output folder");
            ui.add(egui::TextEdit::singleline(&mut self.out_dir).desired_width(380.0));
            if ui.button("Browse…").clicked() {
                if let Some(d) = rfd::FileDialog::new().pick_folder() {
                    self.out_dir = d.display().to_string();
                }
            }
            ui.end_row();
            if self.output == Output::Android {
                ui.label("Android SDK");
                ui.add(egui::TextEdit::singleline(&mut self.android_sdk).hint_text("needs an NDK, build-tools and a platform").desired_width(380.0));
                if ui.button("Browse…").clicked() {
                    if let Some(d) = rfd::FileDialog::new().pick_folder() {
                        self.android_sdk = d.display().to_string();
                    }
                }
                ui.end_row();
            }
        });
    }

    fn modern_form(&mut self, ui: &mut egui::Ui) {
        let m = &mut self.modern;
        ui.strong("Game");
        ui.label("The installed game's folder, or its .exe.");
        let mut picked = None;
        ui.horizontal(|ui| {
            ui.label(m.game.as_ref().map_or("none selected".to_string(), |p| p.display().to_string()));
            if ui.button("Folder…").clicked() {
                picked = rfd::FileDialog::new().pick_folder();
            }
            if ui.button("Executable…").clicked() {
                picked = rfd::FileDialog::new().add_filter("Windows executable", &["exe"]).pick_file();
            }
        });
        ui.push_id("modern_game", |ui| {
            let field = ui.make_persistent_id("typed_path");
            let mut typed: String = ui.data_mut(|d| d.get_temp(field).unwrap_or_default());
            let r = ui.add(egui::TextEdit::singleline(&mut typed).hint_text("or paste a path and press Enter").desired_width(380.0));
            if r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) && !typed.trim().is_empty() {
                picked = Some(PathBuf::from(typed.trim()));
                typed.clear();
            }
            ui.data_mut(|d| d.insert_temp(field, typed));
        });
        if let Some(p) = picked {
            m.set_game(p);
        }
        match &m.scan {
            None => {}
            Some(Err(e)) => {
                ui.colored_label(egui::Color32::LIGHT_RED, e);
            }
            Some(Ok(scan)) => {
                let before = m.exe;
                egui::Grid::new("modern_scan").num_columns(2).spacing([16.0, 6.0]).show(ui, |ui| {
                    ui.label("Game executable");
                    let label = |e: &modernize::ExeCandidate| format!("{} — {}", e.path.display(), e.info.arch.label());
                    egui::ComboBox::from_id_salt("modern_exe").selected_text(label(&scan.exes[m.exe])).width(380.0).show_ui(ui, |ui| {
                        for (i, e) in scan.exes.iter().enumerate() {
                            ui.selectable_value(&mut m.exe, i, label(e));
                        }
                    });
                    ui.end_row();
                    ui.label("Detected");
                    let apis: Vec<_> = m.apis.apis.keys().map(|a| a.label()).collect();
                    ui.label(if apis.is_empty() { "no graphics, audio or input APIs found".to_string() } else { apis.join(", ") });
                    ui.end_row();
                });
                if m.exe != before {
                    m.rescan_apis();
                }
            }
        }
        ui.add_space(8.0);
        let scanned = matches!(m.scan, Some(Ok(_)));
        let x64 = m.exe().is_some_and(|e| e.info.arch == Arch::X64);
        let uses_d3d = m.uses_d3d();
        egui::Grid::new("modern_opts").num_columns(2).spacing([16.0, 8.0]).show(ui, |ui| {
            ui.strong("Graphics");
            ui.vertical(|ui| {
                ui.checkbox(&mut m.dxvk, format!("Translate Direct3D 8–11 to Vulkan (DXVK {})", modernize::DXVK_VERSION))
                    .on_hover_text("Faster, more stable rendering on modern GPUs; enables the options below.");
                if scanned && !uses_d3d {
                    ui.weak("This game doesn't use Direct3D 8–11, so DXVK will be skipped.");
                }
                ui.add_enabled_ui(m.dxvk, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(m.dxvk_local.as_ref().map_or("Download from GitHub (checksum-verified, cached)".to_string(), |p| p.display().to_string()));
                        if ui.small_button("Use local release…").clicked() {
                            if let Some(f) = rfd::FileDialog::new().add_filter("DXVK release", &["gz", "tgz"]).pick_file() {
                                m.dxvk_local = Some(f);
                            }
                        }
                        if m.dxvk_local.is_some() && ui.small_button("Download instead").clicked() {
                            m.dxvk_local = None;
                        }
                    });
                });
            });
            ui.end_row();
            ui.label("Anisotropic filtering");
            ui.add_enabled_ui(m.dxvk, |ui| {
                let name = |n: u32| if n == 0 { "Game default".to_string() } else { format!("{n}x") };
                egui::ComboBox::from_id_salt("aniso").selected_text(name(m.anisotropy)).show_ui(ui, |ui| {
                    for n in [0, 2, 4, 8, 16] {
                        ui.selectable_value(&mut m.anisotropy, n, name(n));
                    }
                });
            });
            ui.end_row();
            ui.label("DirectDraw");
            ui.checkbox(&mut m.ddraw, format!("cnc-ddraw {} (renders old 2D DirectDraw games through OpenGL / Direct3D 9)", modernize::CNC_DDRAW_VERSION));
            ui.end_row();
            ui.label("Frame-rate cap");
            ui.add_enabled_ui(m.dxvk || m.ddraw, |ui| {
                ui.horizontal(|ui| {
                    ui.checkbox(&mut m.cap_fps, "");
                    ui.add_enabled(m.cap_fps, egui::DragValue::new(&mut m.max_fps).range(20..=360).suffix(" FPS"));
                });
            });
            ui.end_row();
            ui.label("Upscaling");
            ui.horizontal(|ui| {
                ui.radio_value(&mut m.upscale, Upscale::Off, "Off");
                ui.add_enabled_ui(m.ddraw || m.dxvk, |ui| {
                    ui.radio_value(&mut m.upscale, Upscale::Fsr1, "AMD FSR 1")
                        .on_hover_text("Upscales DirectDraw through cnc-ddraw or Direct3D 8–11 through the Bifrost Vulkan layer. Direct3D requires the packaged fsr-layer.")
                });
                ui.add_enabled(false, egui::RadioButton::new(false, "NVIDIA DLSS"))
                    .on_disabled_hover_text("Coming later: DLSS needs depth, motion vectors and camera jitter, which older games don't provide.");
            });
            ui.end_row();
            ui.strong("Memory");
            ui.vertical(|ui| {
                ui.add_enabled(!x64, egui::Checkbox::new(&mut m.laa, "Large Address Aware (4 GB instead of 2 GB for 32-bit games)"));
                if x64 {
                    ui.weak("Not needed: this is a 64-bit game.");
                }
            });
            ui.end_row();
            ui.strong("Windows compatibility");
            ui.vertical(|ui| {
                ui.checkbox(&mut m.compat.high_dpi, "Override high-DPI scaling");
                ui.checkbox(&mut m.compat.disable_fullscreen_optimizations, "Disable fullscreen optimizations");
                ui.checkbox(&mut m.compat.xp_mode, "Windows XP SP3 compatibility mode");
                ui.add_enabled(cfg!(windows), egui::Checkbox::new(&mut m.apply_compat, "Apply now for this user"))
                    .on_hover_text("Otherwise run bifrost_compat.bat from the modernized folder.");
            });
            ui.end_row();
            ui.label("Output folder");
            ui.horizontal(|ui| {
                ui.add(egui::TextEdit::singleline(&mut m.out_dir).desired_width(380.0));
                if ui.button("Browse…").clicked() {
                    if let Some(d) = rfd::FileDialog::new().pick_folder() {
                        m.out_dir = d.display().to_string();
                    }
                }
            });
            ui.end_row();
        });
    }

    fn poll(&mut self) {
        let Some(rx) = &self.rx else { return };
        while let Ok(msg) = rx.try_recv() {
            match msg {
                Msg::Event(Event::Progress(p, step)) => {
                    if let Status::Running { progress, step: s } = &mut self.status {
                        *progress = p;
                        if *s != step
                            && !step.contains("compiled")
                            && !step.starts_with("Copying")
                            && !step.starts_with("Packing")
                            && !step.starts_with("Downloading")
                        {
                            self.log.push(step.clone());
                        }
                        *s = step;
                    }
                }
                Msg::Event(Event::Log(line)) => self.log.push(line),
                Msg::Finished(Ok(Finished { artifact, project, summary })) => {
                    self.log.push(summary.clone());
                    self.status = Status::Done { artifact, project, summary };
                }
                Msg::Finished(Err(e)) => {
                    self.log.push(format!("error: {e}"));
                    self.status = Status::Failed(e);
                }
            }
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.poll();
        let busy = self.running();
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("Bifrost");
            ui.add_enabled_ui(!busy, |ui| {
                ui.horizontal(|ui| {
                    ui.label("Task");
                    ui.selectable_value(&mut self.task, Task::Recomp, egui::RichText::new("Recomp").size(16.0));
                    ui.selectable_value(&mut self.task, Task::Modernize, egui::RichText::new("Modernize").size(16.0));
                });
            });
            ui.label(match self.task {
                Task::Recomp => "Statically recompiles dumped console games into native programs. Content must already be decrypted.",
                Task::Modernize => "Updates an older Windows game (XP / Windows 7 era) to run better on current Windows. Writes a modernized copy; your install is never changed.",
            });
            ui.separator();
            egui::ScrollArea::vertical().auto_shrink([false, true]).max_height(ui.available_height() - 210.0).show(ui, |ui| {
                ui.add_enabled_ui(!busy, |ui| {
                    match self.task {
                        Task::Recomp => self.recomp_form(ui),
                        Task::Modernize => self.modern_form(ui),
                    }
                });
            });
            ui.separator();
            let (ready, label, hint) = match self.task {
                Task::Recomp => (
                    !self.base.is_empty() && !self.out_dir.trim().is_empty(),
                    "Begin Recomp",
                    self.base.is_empty().then_some("Add the base game to begin."),
                ),
                Task::Modernize => (
                    matches!(self.modern.scan, Some(Ok(_))) && !self.modern.out_dir.trim().is_empty(),
                    "Begin Modernization",
                    self.modern.game.is_none().then_some("Choose the game to begin."),
                ),
            };
            let button = egui::Button::new(egui::RichText::new(label).size(18.0)).min_size(egui::vec2(200.0, 36.0));
            if ui.add_enabled(ready && !busy, button).clicked() {
                match self.task {
                    Task::Recomp => self.begin(ctx),
                    Task::Modernize => self.begin_modernize(ctx),
                }
            }
            match &self.status {
                Status::Idle => {
                    ui.label(hint.unwrap_or("Ready."));
                }
                Status::Running { progress, step } => {
                    ui.add(egui::ProgressBar::new(*progress).show_percentage().animate(true));
                    ui.label(step);
                }
                Status::Done { artifact, project, summary } => {
                    ui.add(egui::ProgressBar::new(1.0).text("Done"));
                    ui.label(summary);
                    let shown = artifact.clone().unwrap_or_else(|| project.clone());
                    ui.horizontal(|ui| {
                        ui.label(format!("Output: {}", shown.display()));
                        if ui.button("Open folder").clicked() {
                            let dir = if shown.is_dir() { shown.clone() } else { shown.parent().map(PathBuf::from).unwrap_or_default() };
                            let opener = if cfg!(windows) { "explorer" } else if cfg!(target_os = "macos") { "open" } else { "xdg-open" };
                            let _ = std::process::Command::new(opener).arg(dir).spawn();
                        }
                    });
                }
                Status::Failed(e) => {
                    ui.add(egui::ProgressBar::new(0.0).text("Failed"));
                    ui.colored_label(egui::Color32::LIGHT_RED, e);
                }
            }
            egui::CollapsingHeader::new("Log").default_open(true).show(ui, |ui| {
                egui::ScrollArea::vertical().max_height(110.0).stick_to_bottom(true).auto_shrink([false, true]).show(ui, |ui| {
                    for line in &self.log {
                        ui.monospace(line);
                    }
                });
            });
        });
    }
}

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default().with_inner_size([780.0, 760.0]).with_title("Bifrost"),
        ..Default::default()
    };
    eframe::run_native("Bifrost", options, Box::new(|_cc| Ok(Box::<App>::default())))
}
