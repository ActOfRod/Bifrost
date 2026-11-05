//! Shared NVN → Vulkan renderer sources and dependency discovery.
use anyhow::{bail, Context, Result};
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

pub static LAS_DIR: include_dir::Dir<'static> =
    include_dir::include_dir!("$CARGO_MANIFEST_DIR/runtime/las");
const WINDOWS_COMPAT: &str = include_str!("../runtime/las/platform/win_compat.h");

pub const NVN_IMPORTS: &[&str] = &["nvnBootstrapLoader", "nvnDeviceGetProcAddress"];

pub fn mingw_posix_tool(name: &str) -> Result<PathBuf> {
    let candidate = format!("{name}-posix");
    let path = std::env::var_os("PATH").unwrap_or_default();
    std::env::split_paths(&path)
        .map(|dir| dir.join(&candidate))
        .find(|path| path.is_file())
        .with_context(|| format!(
            "required POSIX-thread MinGW tool {candidate} was not found; install the MinGW POSIX toolchain"
        ))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RendererPlatform {
    Android,
    Windows,
}

pub struct RendererDeps {
    pub root: PathBuf,
}

impl RendererDeps {
    pub fn locate(platform: RendererPlatform, dir: Option<&Path>) -> Option<Self> {
        let env = match platform {
            RendererPlatform::Android => "BIFROST_ANDROID_DEPS",
            RendererPlatform::Windows => "BIFROST_WINDOWS_DEPS",
        };
        let folder = match platform {
            RendererPlatform::Android => "android-deps",
            RendererPlatform::Windows => "windows-deps",
        };
        let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"));
        let env_path = std::env::var_os(env).map(PathBuf::from);
        let candidates = [
            dir.map(Path::to_path_buf),
            env_path.as_ref().map(|d| d.join("out")),
            env_path,
            home.map(|h| PathBuf::from(h).join(".bifrost").join(folder).join("out")),
        ];
        candidates
            .into_iter()
            .flatten()
            .map(|root| RendererDeps { root })
            .find(|d| d.complete(platform))
    }

    fn complete(&self, platform: RendererPlatform) -> bool {
        let files: &[&str] = match platform {
            RendererPlatform::Android => &[
                "lib/libSDL3.so",
                "lib/libshaderc_shared.so",
                "include/vulkan/vulkan_core.h",
                "include/vma/vk_mem_alloc.h",
                "volk/volk.c",
                "java/org/libsdl/app/SDLActivity.java",
            ],
            RendererPlatform::Windows => &[
                "lib/SDL3.dll",
                "lib/libSDL3.dll.a",
                "lib/shaderc_shared.dll",
                "lib/libshaderc_shared.dll.a",
                "lib/libvulkan-1.dll.a",
                "include/SDL3/SDL.h",
                "include/shaderc/shaderc.h",
                "include/vulkan/vulkan_core.h",
                "include/vma/vk_mem_alloc.h",
                "include/imgui/imgui.h",
                "include/imgui/backends/imgui_impl_vulkan.h",
                "volk/volk.c",
            ],
        };
        files.iter().all(|p| self.root.join(p).is_file())
    }
}

pub fn write_renderer_sources(
    out: &Path,
    deps: &RendererDeps,
    platform: RendererPlatform,
) -> Result<Vec<PathBuf>> {
    let las = out.join("las");
    let _ = fs::remove_dir_all(&las);
    fs::create_dir_all(&las)?;
    LAS_DIR.extract(&las)?;
    if platform == RendererPlatform::Windows {
        let platform_dir = las.join("platform");
        fs::create_dir_all(&platform_dir)?;
        fs::write(platform_dir.join("win_compat.h"), WINDOWS_COMPAT)?;
    }
    let gen = las.join("gen");
    fs::create_dir_all(&gen)?;
    fs::write(
        gen.join("shader_variants.inc"),
        c_string_lines(&fs::read_to_string(las.join("nvn/shader_variants.txt"))?),
    )?;
    fs::write(
        gen.join("smaa_hlsl.inc"),
        c_string_lines(&String::from_utf8_lossy(&fs::read(
            las.join("third_party/smaa/SMAA.hlsl"),
        )?)),
    )?;
    let mut srcs = Vec::new();
    for sub in ["nvn", "nvn/maxwell", "nvn/vulkan"] {
        let mut files: Vec<PathBuf> = fs::read_dir(las.join(sub))?
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| p.extension().is_some_and(|x| x == "c"))
            .map(|p| p.strip_prefix(out).unwrap().to_path_buf())
            .collect();
        files.sort();
        srcs.extend(files);
    }
    srcs.extend(
        ["bf_las_glue.c", "bf_las_game_stubs.c"]
            .into_iter()
            .map(|name| Path::new("las").join(name)),
    );
    srcs.push(Path::new("las").join(match platform {
        RendererPlatform::Android => "bf_android_sdl.c",
        RendererPlatform::Windows => "bf_windows_sdl.c",
    }));
    srcs.push(deps.root.join("volk/volk.c"));
    Ok(srcs)
}

pub fn defines(
    deps: &RendererDeps,
    out: &Path,
    platform: RendererPlatform,
) -> Vec<String> {
    let las = out.join("las");
    let mut v: Vec<String> = [
        (platform == RendererPlatform::Android).then_some("-DVK_NO_PROTOTYPES"),
        Some(match platform {
            RendererPlatform::Android => "-DVK_USE_PLATFORM_ANDROID_KHR",
            RendererPlatform::Windows => "-DVK_USE_PLATFORM_WIN32_KHR",
        }),
        Some("-DVMA_STATIC_VULKAN_FUNCTIONS=0"),
        Some("-DVMA_DYNAMIC_VULKAN_FUNCTIONS=1"),
        Some("-DBF_RENDERER=1"),
    ]
    .into_iter()
    .flatten()
    .map(str::to_string)
    .collect();
    for inc in [
        las.clone(),
        las.join("nvn"),
        las.join("gen"),
        las.join("third_party/smaa"),
        deps.root.join("include"),
        deps.root.join("include/imgui"),
        deps.root.join("volk"),
    ] {
        v.push(format!("-I{}", inc.display()));
    }
    v
}

fn c_string_lines(text: &str) -> String {
    let mut s = String::new();
    for line in text.replace('\r', "").split('\n') {
        s.push('"');
        for ch in line.chars() {
            match ch {
                '"' => s.push_str("\\\""),
                '\\' => s.push_str("\\\\"),
                '\t' => s.push_str("\\t"),
                c if (c as u32) < 0x20 => {}
                c => s.push(c),
            }
        }
        s.push_str("\\n\"\n");
    }
    s.push_str("\"\"\n");
    s
}

pub fn compile_windows_cpp(
    source: &Path,
    compiler: &Path,
    deps: &RendererDeps,
    out: &Path,
    obj: &Path,
) -> Result<()> {
    fs::create_dir_all(obj.parent().context("C++ object has no parent")?)?;
    let mut cmd = Command::new(compiler);
    let mut compile_defines = defines(deps, out, RendererPlatform::Windows);
    compile_defines.retain(|d| !d.starts_with("-DVMA_"));
    cmd.current_dir(out)
        .args(["-c", "-O2", "-std=c++17", "-fno-strict-aliasing"])
        .args(compile_defines)
        .arg(format!(
            "-I{}",
            deps.root.join("include/imgui/backends").display()
        ))
        .arg(source)
        .arg("-o")
        .arg(obj);
    let output = cmd.output().with_context(|| format!("run {cmd:?}"))?;
    if !output.status.success() {
        bail!(
            "{cmd:?} failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
    Ok(())
}
