#![cfg_attr(windows, windows_subsystem = "windows")]
//! Bifrost desktop front end: pick the input/output systems and content, then Begin Recomp.
use bifrost::pipeline::{self, BuildOptions, Event, InputSystem, Target};
use eframe::egui;
use std::path::PathBuf;
use std::sync::mpsc;

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

struct App {
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
    Finished(Result<pipeline::BuildOutput, String>),
}

impl Default for App {
    fn default() -> Self {
        let sdk = std::env::var("ANDROID_HOME").or_else(|_| std::env::var("ANDROID_SDK_ROOT")).unwrap_or_default();
        let out = std::env::current_dir().map(|d| d.join("bifrost-out")).unwrap_or_else(|_| "bifrost-out".into());
        App {
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
                let mut typed = String::new();
                let r = ui.add(egui::TextEdit::singleline(&mut typed).hint_text("or paste a path and press Enter").desired_width(260.0));
                if r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) && !typed.trim().is_empty() {
                    paths.push(PathBuf::from(typed.trim()));
                }
                ui.data_mut(|d| d.insert_temp(r.id, typed));
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
        let (tx, rx) = mpsc::channel();
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            let etx = tx.clone();
            let ectx = ctx.clone();
            let result = pipeline::run(&opts, &mut |ev| {
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

    fn poll(&mut self) {
        let Some(rx) = &self.rx else { return };
        while let Ok(msg) = rx.try_recv() {
            match msg {
                Msg::Event(Event::Progress(p, step)) => {
                    if let Status::Running { progress, step: s } = &mut self.status {
                        *progress = p;
                        if *s != step && !step.contains("compiled") && !step.starts_with("Copying") && !step.starts_with("Packing") {
                            self.log.push(step.clone());
                        }
                        *s = step;
                    }
                }
                Msg::Event(Event::Log(line)) => self.log.push(line),
                Msg::Finished(Ok(out)) => {
                    let r = &out.report;
                    let summary = format!(
                        "{}: {} functions, {} instructions ({} unsupported), {} imports unresolved",
                        out.title, r.functions, r.instructions, r.unsupported, r.imports
                    );
                    self.log.push(summary.clone());
                    self.status = Status::Done { artifact: out.artifact, project: out.project, summary };
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
            ui.heading("Bifrost Recompiler");
            ui.label("Statically recompiles dumped console games into native programs. Content must already be decrypted.");
            ui.separator();
            egui::ScrollArea::vertical().auto_shrink([false, true]).max_height(ui.available_height() - 210.0).show(ui, |ui| {
                ui.add_enabled_ui(!busy, |ui| {
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
                });
            });
            ui.separator();
            let ready = !self.base.is_empty() && !self.out_dir.trim().is_empty() && !busy;
            let button = egui::Button::new(egui::RichText::new("Begin Recomp").size(18.0)).min_size(egui::vec2(180.0, 36.0));
            if ui.add_enabled(ready, button).clicked() {
                self.begin(ctx);
            }
            match &self.status {
                Status::Idle => {
                    ui.label(if self.base.is_empty() { "Add the base game to begin." } else { "Ready." });
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
        viewport: egui::ViewportBuilder::default().with_inner_size([760.0, 720.0]).with_title("Bifrost Recompiler"),
        ..Default::default()
    };
    eframe::run_native("Bifrost Recompiler", options, Box::new(|_cc| Ok(Box::<App>::default())))
}
