//! Android output: NativeActivity sources, NDK cross-compilation and APK packaging.
use anyhow::{anyhow, bail, Context, Result};
use crate::renderer::{self, RendererDeps, RendererPlatform};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;

pub const HOST_SOURCE: &str = include_str!("../runtime/bf_android.c");
pub const MIN_SDK: u32 = 26;
/// ABIs built into the APK: arm64 for devices, x86_64 for emulators and Chromebooks.
pub const ABIS: &[(&str, &str)] = &[
    ("arm64-v8a", "aarch64-linux-android"),
    ("x86_64", "x86_64-linux-android"),
];

#[derive(Debug, Clone)]
pub struct AndroidSdk {
    pub sdk: PathBuf,
    pub ndk: PathBuf,
    pub build_tools: PathBuf,
    pub platform_jar: PathBuf,
}

fn newest_subdir(dir: &Path) -> Option<PathBuf> {
    let mut v: Vec<PathBuf> = fs::read_dir(dir)
        .ok()?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.is_dir())
        .collect();
    v.sort_by_key(|p| {
        p.file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .split(|c: char| !c.is_ascii_digit())
            .filter_map(|s| s.parse::<u64>().ok())
            .collect::<Vec<_>>()
    });
    v.pop()
}

fn exe(name: &str) -> String {
    if cfg!(windows) {
        format!("{name}.exe")
    } else {
        name.to_string()
    }
}

impl AndroidSdk {
    /// Find the SDK (explicit path, then `ANDROID_HOME` / `ANDROID_SDK_ROOT`) and the newest NDK,
    /// build-tools and platform inside it (`ANDROID_NDK_HOME` overrides the NDK).
    pub fn locate(sdk: Option<&Path>) -> Result<Self> {
        let sdk = sdk
            .map(Path::to_path_buf)
            .or_else(|| std::env::var_os("ANDROID_HOME").map(PathBuf::from))
            .or_else(|| std::env::var_os("ANDROID_SDK_ROOT").map(PathBuf::from))
            .ok_or_else(|| {
                anyhow!("Android SDK not found: set ANDROID_HOME or choose the SDK folder")
            })?;
        let ndk = std::env::var_os("ANDROID_NDK_HOME")
            .map(PathBuf::from)
            .or_else(|| newest_subdir(&sdk.join("ndk")))
            .or_else(|| Some(sdk.join("ndk-bundle")).filter(|p| p.is_dir()))
            .ok_or_else(|| {
                anyhow!(
                    "no NDK in {} (install it with `sdkmanager \"ndk;26.3.11579264\"`)",
                    sdk.display()
                )
            })?;
        let build_tools = newest_subdir(&sdk.join("build-tools")).ok_or_else(|| {
            anyhow!(
                "no build-tools in {} (install with `sdkmanager \"build-tools;34.0.0\"`)",
                sdk.display()
            )
        })?;
        let platform_jar = newest_subdir(&sdk.join("platforms"))
            .map(|p| p.join("android.jar"))
            .filter(|p| p.is_file())
            .ok_or_else(|| {
                anyhow!(
                    "no platform in {} (install with `sdkmanager \"platforms;android-34\"`)",
                    sdk.display()
                )
            })?;
        Ok(AndroidSdk {
            sdk,
            ndk,
            build_tools,
            platform_jar,
        })
    }

    pub fn clang(&self) -> Result<PathBuf> {
        let prebuilt = self.ndk.join("toolchains/llvm/prebuilt");
        let host = newest_subdir(&prebuilt)
            .ok_or_else(|| anyhow!("NDK at {} has no LLVM toolchain", self.ndk.display()))?;
        Ok(host.join("bin").join(exe("clang")))
    }

    pub fn glue_dir(&self) -> PathBuf {
        self.ndk.join("sources/android/native_app_glue")
    }

    fn tool(&self, name: &str) -> PathBuf {
        let bat = self.build_tools.join(format!("{name}.bat"));
        if cfg!(windows) && bat.is_file() {
            bat
        } else {
            self.build_tools.join(exe(name))
        }
    }
}

pub fn package_name(title_id: u64, exe_name: &str) -> String {
    if title_id != 0 {
        return format!("com.bifrost.t{title_id:016x}");
    }
    let s: String = exe_name
        .to_ascii_lowercase()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .collect();
    format!("com.bifrost.g{s}")
}

fn xml_escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

pub fn manifest(package: &str, label: &str) -> String {
    format!(
        r#"<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}"
    android:versionCode="1" android:versionName="1.0">
  <uses-sdk android:minSdkVersion="{MIN_SDK}" android:targetSdkVersion="34"/>
  <application android:label="{label}" android:hasCode="false" android:extractNativeLibs="true">
    <meta-data android:name="com.actofrod.bifrost.PORT" android:value="switch"/>
    <activity android:name="android.app.NativeActivity" android:exported="true"
        android:configChanges="orientation|keyboardHidden|keyboard|screenSize|screenLayout|uiMode"
        android:screenOrientation="sensorLandscape" android:theme="@android:style/Theme.NoTitleBar.Fullscreen">
      <meta-data android:name="android.app.lib_name" android:value="bifrost"/>
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
      </intent-filter>
    </activity>
  </application>
</manifest>
"#,
        label = xml_escape(label)
    )
}

/// Write `android/` (manifest, host source, a standalone build script) into a generated project.
pub fn write_sources(out: &Path, package: &str, label: &str) -> Result<()> {
    let dir = out.join("android");
    fs::create_dir_all(&dir)?;
    fs::write(dir.join("AndroidManifest.xml"), manifest(package, label))?;
    fs::write(out.join("bf_android.c.in"), HOST_SOURCE)?;
    fs::write(
        dir.join("README.txt"),
        "Build the APK with `bifrost build ... --target android` (needs ANDROID_HOME with an NDK, build-tools and a\n\
         platform). The generated C is compiled with -DBF_NO_MAIN together with ../bf_android.c.in\n\
         and the NDK's native_app_glue into lib/<abi>/libbifrost.so.\n",
    )?;
    Ok(())
}

/// Compiler arguments for one ABI (sources are added by the caller).
pub fn compile_args(sdk: &AndroidSdk, triple: &str) -> Vec<String> {
    vec![
        format!("--target={triple}{MIN_SDK}"),
        "-fPIC".into(),
        "-DBF_NO_MAIN".into(),
        format!("-I{}", sdk.glue_dir().display()),
    ]
}

pub fn link_args(triple: &str) -> Vec<String> {
    vec![
        format!("--target={triple}{MIN_SDK}"),
        "-shared".into(),
        "-u".into(),
        "ANativeActivity_onCreate".into(),
        "-landroid".into(),
        "-llog".into(),
        "-lm".into(),
    ]
}

fn run(cmd: &mut Command) -> Result<()> {
    let output = cmd
        .output()
        .with_context(|| format!("run {:?}", cmd.get_program()))?;
    if !output.status.success() {
        bail!(
            "{:?} failed: {}{}",
            cmd.get_program(),
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
    }
    Ok(())
}

fn link_or_copy(src: &Path, dst: &Path) -> Result<()> {
    let _ = fs::remove_file(dst);
    if fs::hard_link(src, dst).is_err() {
        fs::copy(src, dst).with_context(|| format!("copy {}", src.display()))?;
    }
    Ok(())
}

fn debug_keystore(sdk: &AndroidSdk) -> Result<PathBuf> {
    let home = std::env::var_os("HOME")
        .or_else(|| std::env::var_os("USERPROFILE"))
        .map(PathBuf::from)
        .unwrap_or_else(|| sdk.sdk.clone());
    let dir = home.join(".bifrost");
    fs::create_dir_all(&dir)?;
    let ks = dir.join("debug.keystore");
    if !ks.is_file() {
        let keytool = std::env::var_os("JAVA_HOME")
            .map(|j| PathBuf::from(j).join("bin").join(exe("keytool")))
            .unwrap_or_else(|| exe("keytool").into());
        run(Command::new(keytool)
            .args(["-genkeypair", "-keystore"])
            .arg(&ks)
            .args([
                "-storepass",
                "android",
                "-keypass",
                "android",
                "-alias",
                "bifrost",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "10000",
                "-dname",
                "CN=Bifrost Debug",
            ]))?;
    }
    Ok(ks)
}

/// Extra APK contents for renderer builds.
#[derive(Default)]
pub struct ApkExtras {
    /// File name of the recompiled library (`libbifrost.so` when empty).
    pub lib_name: String,
    /// Prebuilt shared libraries shipped beside it, for every ABI in `libs`.
    pub shared_libs: Vec<PathBuf>,
    pub dex: Option<PathBuf>,
}

/// Assemble, align and sign `<exe_name>.apk`. `libs` maps ABI → built library.
pub fn package(
    out: &Path,
    sdk: &AndroidSdk,
    exe_name: &str,
    libs: &[(String, PathBuf)],
    embed_romfs: bool,
    extras: &ApkExtras,
) -> Result<PathBuf> {
    let dir = out.join("android");
    let assets = dir.join("assets");
    let _ = fs::remove_dir_all(&assets);
    fs::create_dir_all(&assets)?;
    link_or_copy(
        &out.join("guest_image.bin"),
        &assets.join("guest_image.bin"),
    )?;
    if embed_romfs && out.join("romfs.bin").is_file() {
        link_or_copy(&out.join("romfs.bin"), &assets.join("romfs.bin"))?;
    }
    if out.join("dlc").is_dir() {
        fs::create_dir_all(assets.join("dlc"))?;
        for e in fs::read_dir(out.join("dlc"))? {
            let p = e?.path();
            link_or_copy(&p, &assets.join("dlc").join(p.file_name().unwrap()))?;
        }
    }
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    fs::write(assets.join("bifrost.stamp"), format!("{stamp}\n"))?;
    let unsigned = dir.join("unsigned.apk");
    run(Command::new(sdk.tool("aapt2"))
        .arg("link")
        .arg("-o")
        .arg(&unsigned)
        .arg("--manifest")
        .arg(dir.join("AndroidManifest.xml"))
        .arg("-I")
        .arg(&sdk.platform_jar)
        .arg("-A")
        .arg(&assets)
        .args(["-0", "bin", "-0", "romfs"]))?;
    {
        let file = fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(&unsigned)?;
        let mut zip = zip::ZipWriter::new_append(file)?;
        let opts =
            zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        let lib_name = if extras.lib_name.is_empty() {
            "libbifrost.so"
        } else {
            extras.lib_name.as_str()
        };
        for (abi, so) in libs {
            zip.start_file(format!("lib/{abi}/{lib_name}"), opts)?;
            zip.write_all(&fs::read(so)?)?;
            for extra in &extras.shared_libs {
                zip.start_file(
                    format!("lib/{abi}/{}", extra.file_name().unwrap().to_string_lossy()),
                    opts,
                )?;
                zip.write_all(&fs::read(extra)?)?;
            }
        }
        if let Some(dex) = &extras.dex {
            zip.start_file("classes.dex", opts)?;
            zip.write_all(&fs::read(dex)?)?;
        }
        zip.finish()?;
    }
    let aligned = dir.join("aligned.apk");
    run(Command::new(sdk.tool("zipalign"))
        .args(["-p", "-f", "4"])
        .arg(&unsigned)
        .arg(&aligned))?;
    let apk = out.join(format!("{exe_name}.apk"));
    let ks = debug_keystore(sdk)?;
    run(Command::new(sdk.tool("apksigner"))
        .args(["sign", "--ks"])
        .arg(&ks)
        .args([
            "--ks-pass",
            "pass:android",
            "--key-pass",
            "pass:android",
            "--ks-key-alias",
            "bifrost",
            "--out",
        ])
        .arg(&apk)
        .arg(&aligned))?;
    let _ = fs::remove_file(&unsigned);
    let _ = fs::remove_file(&aligned);
    let _ = fs::remove_dir_all(&assets);
    Ok(apk)
}

// ---- NVN → Vulkan Android renderer builds ----

impl RendererDeps {
    /// Stripped copies of the shared libraries the APK ships (shaderc alone is ~160 MiB with symbols).
    pub fn shared_libs(&self, sdk: &AndroidSdk, out: &Path) -> Result<Vec<PathBuf>> {
        let strip = sdk.clang()?.with_file_name(exe("llvm-strip"));
        let dir = out.join("android").join("prebuilt");
        fs::create_dir_all(&dir)?;
        let mut v = Vec::new();
        for name in ["libSDL3.so", "libshaderc_shared.so"] {
            let dst = dir.join(name);
            run(Command::new(&strip)
                .arg("--strip-unneeded")
                .arg("-o")
                .arg(&dst)
                .arg(self.root.join("lib").join(name)))?;
            v.push(dst);
        }
        Ok(v)
    }
}

pub const RENDERER_ABI: (&str, &str) = ("arm64-v8a", "aarch64-linux-android");

/// Compiler arguments for the renderer build (arm64 only).
pub fn renderer_compile_args(deps: &RendererDeps, out: &Path) -> Vec<String> {
    let mut v = vec![
        format!("--target={}{MIN_SDK}", RENDERER_ABI.1),
        "-fPIC".into(),
        "-DBF_NO_MAIN".into(),
        "-fno-strict-aliasing".into(),
        "-Wno-format".into(),
    ];
    v.extend(renderer::defines(deps, out, RendererPlatform::Android));
    v
}

/// Compiles the C++ part (Vulkan Memory Allocator) to an object for the link line.
pub fn compile_vma(sdk: &AndroidSdk, deps: &RendererDeps, out: &Path) -> Result<PathBuf> {
    let clangxx = sdk.clang()?.with_file_name(exe("clang++"));
    let obj = out.join("obj").join(RENDERER_ABI.0).join("vma.o");
    fs::create_dir_all(obj.parent().unwrap())?;
    run(Command::new(clangxx)
        .arg(format!("--target={}{MIN_SDK}", RENDERER_ABI.1))
        .args(["-c", "-O2", "-fPIC", "-std=c++17"])
        .args(renderer::defines(deps, out, RendererPlatform::Android))
        .arg("-o")
        .arg(&obj)
        .arg(out.join("las/nvn/vulkan/vma.cpp")))?;
    Ok(obj)
}

pub fn renderer_link_args(deps: &RendererDeps, vma: &Path) -> Vec<String> {
    vec![
        format!("--target={}{MIN_SDK}", RENDERER_ABI.1),
        "-shared".into(),
        vma.display().to_string(),
        format!("-L{}", deps.root.join("lib").display()),
        "-lSDL3".into(),
        "-lshaderc_shared".into(),
        "-l:libc++_static.a".into(),
        "-l:libc++abi.a".into(),
        "-Wl,--no-undefined".into(),
        "-landroid".into(),
        "-llog".into(),
        "-lm".into(),
    ]
}

pub fn renderer_manifest(package: &str, label: &str) -> String {
    let label = xml_escape(label);
    format!(
        r#"<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">
  <uses-sdk android:minSdkVersion="{MIN_SDK}" android:targetSdkVersion="34"/>
  <uses-feature android:name="android.hardware.vulkan.version" android:version="0x401000" android:required="true"/>
  <uses-feature android:name="android.hardware.touchscreen" android:required="false"/>
  <uses-feature android:name="android.hardware.gamepad" android:required="false"/>
  <uses-permission android:name="android.permission.VIBRATE"/>
  <application android:label="{label}" android:hasCode="true" android:extractNativeLibs="true"
      android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen" android:hardwareAccelerated="true">
    <meta-data android:name="com.actofrod.bifrost.PORT" android:value="switch"/>
    <activity android:name="org.bifrost.BifrostActivity" android:exported="true" android:launchMode="singleInstance"
        android:screenOrientation="sensorLandscape"
        android:configChanges="layoutDirection|locale|orientation|uiMode|screenLayout|screenSize|smallestScreenSize|keyboard|keyboardHidden|navigation">
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
      </intent-filter>
    </activity>
  </application>
</manifest>
"#
    )
}

/// SDL's Java glue plus BifrostActivity, compiled to `classes.dex` (javac + d8).
pub fn build_dex(sdk: &AndroidSdk, deps: &RendererDeps, out: &Path) -> Result<PathBuf> {
    let dir = out.join("android").join("java");
    let classes = dir.join("classes");
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&classes)?;
    let mut srcs = Vec::new();
    collect_java(&deps.root.join("java"), &mut srcs)?;
    collect_java(&out.join("las/android"), &mut srcs)?;
    let javac = std::env::var_os("JAVA_HOME")
        .map(|h| PathBuf::from(h).join("bin").join(exe("javac")))
        .unwrap_or_else(|| PathBuf::from(exe("javac")));
    run(Command::new(javac)
        .args([
            "-nowarn",
            "-encoding",
            "UTF-8",
            "--release",
            "11",
            "-classpath",
        ])
        .arg(&sdk.platform_jar)
        .arg("-d")
        .arg(&classes)
        .args(&srcs))?;
    let mut class_files = Vec::new();
    collect_ext(&classes, "class", &mut class_files)?;
    let d8 = sdk.tool(if cfg!(windows) { "d8.bat" } else { "d8" });
    run(Command::new(d8)
        .arg("--lib")
        .arg(&sdk.platform_jar)
        .arg("--min-api")
        .arg(MIN_SDK.to_string())
        .arg("--output")
        .arg(&dir)
        .args(&class_files))?;
    Ok(dir.join("classes.dex"))
}

fn collect_java(dir: &Path, out: &mut Vec<PathBuf>) -> Result<()> {
    collect_ext(dir, "java", out)
}

fn collect_ext(dir: &Path, ext: &str, out: &mut Vec<PathBuf>) -> Result<()> {
    for e in fs::read_dir(dir)? {
        let p = e?.path();
        if p.is_dir() {
            collect_ext(&p, ext, out)?;
        } else if p.extension().is_some_and(|x| x == ext) {
            out.push(p);
        }
    }
    Ok(())
}
