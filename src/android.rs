//! Android output: NativeActivity sources, NDK cross-compilation and APK packaging.
use anyhow::{anyhow, bail, Context, Result};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;

pub const HOST_SOURCE: &str = include_str!("../runtime/bf_android.c");
pub const MIN_SDK: u32 = 26;
/// ABIs built into the APK: arm64 for devices, x86_64 for emulators and Chromebooks.
pub const ABIS: &[(&str, &str)] = &[("arm64-v8a", "aarch64-linux-android"), ("x86_64", "x86_64-linux-android")];

#[derive(Debug, Clone)]
pub struct AndroidSdk {
    pub sdk: PathBuf,
    pub ndk: PathBuf,
    pub build_tools: PathBuf,
    pub platform_jar: PathBuf,
}

fn newest_subdir(dir: &Path) -> Option<PathBuf> {
    let mut v: Vec<PathBuf> = fs::read_dir(dir).ok()?.filter_map(|e| e.ok().map(|e| e.path())).filter(|p| p.is_dir()).collect();
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
            .ok_or_else(|| anyhow!("Android SDK not found: set ANDROID_HOME or choose the SDK folder"))?;
        let ndk = std::env::var_os("ANDROID_NDK_HOME")
            .map(PathBuf::from)
            .or_else(|| newest_subdir(&sdk.join("ndk")))
            .or_else(|| Some(sdk.join("ndk-bundle")).filter(|p| p.is_dir()))
            .ok_or_else(|| anyhow!("no NDK in {} (install it with `sdkmanager \"ndk;26.3.11579264\"`)", sdk.display()))?;
        let build_tools = newest_subdir(&sdk.join("build-tools"))
            .ok_or_else(|| anyhow!("no build-tools in {} (install with `sdkmanager \"build-tools;34.0.0\"`)", sdk.display()))?;
        let platform_jar = newest_subdir(&sdk.join("platforms"))
            .map(|p| p.join("android.jar"))
            .filter(|p| p.is_file())
            .ok_or_else(|| anyhow!("no platform in {} (install with `sdkmanager \"platforms;android-34\"`)", sdk.display()))?;
        Ok(AndroidSdk { sdk, ndk, build_tools, platform_jar })
    }

    pub fn clang(&self) -> Result<PathBuf> {
        let prebuilt = self.ndk.join("toolchains/llvm/prebuilt");
        let host = newest_subdir(&prebuilt).ok_or_else(|| anyhow!("NDK at {} has no LLVM toolchain", self.ndk.display()))?;
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
    let s: String = exe_name.to_ascii_lowercase().chars().filter(|c| c.is_ascii_alphanumeric()).collect();
    format!("com.bifrost.g{s}")
}

fn xml_escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;").replace('\'', "&apos;")
}

pub fn manifest(package: &str, label: &str) -> String {
    format!(
        r#"<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}"
    android:versionCode="1" android:versionName="1.0">
  <uses-sdk android:minSdkVersion="{MIN_SDK}" android:targetSdkVersion="34"/>
  <application android:label="{label}" android:hasCode="false" android:extractNativeLibs="true">
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
    let output = cmd.output().with_context(|| format!("run {:?}", cmd.get_program()))?;
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
    let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE")).map(PathBuf::from).unwrap_or_else(|| sdk.sdk.clone());
    let dir = home.join(".bifrost");
    fs::create_dir_all(&dir)?;
    let ks = dir.join("debug.keystore");
    if !ks.is_file() {
        let keytool = std::env::var_os("JAVA_HOME").map(|j| PathBuf::from(j).join("bin").join(exe("keytool"))).unwrap_or_else(|| exe("keytool").into());
        run(Command::new(keytool).args(["-genkeypair", "-keystore"]).arg(&ks).args([
            "-storepass", "android", "-keypass", "android", "-alias", "bifrost", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000", "-dname", "CN=Bifrost Debug",
        ]))?;
    }
    Ok(ks)
}

/// Assemble, align and sign `<exe_name>.apk`. `libs` maps ABI → built `libbifrost.so`.
pub fn package(out: &Path, sdk: &AndroidSdk, exe_name: &str, libs: &[(String, PathBuf)], embed_romfs: bool) -> Result<PathBuf> {
    let dir = out.join("android");
    let assets = dir.join("assets");
    let _ = fs::remove_dir_all(&assets);
    fs::create_dir_all(&assets)?;
    link_or_copy(&out.join("guest_image.bin"), &assets.join("guest_image.bin"))?;
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
    let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
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
        let file = fs::OpenOptions::new().read(true).write(true).open(&unsigned)?;
        let mut zip = zip::ZipWriter::new_append(file)?;
        let opts = zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        for (abi, so) in libs {
            zip.start_file(format!("lib/{abi}/libbifrost.so"), opts)?;
            zip.write_all(&fs::read(so)?)?;
        }
        zip.finish()?;
    }
    let aligned = dir.join("aligned.apk");
    run(Command::new(sdk.tool("zipalign")).args(["-p", "-f", "4"]).arg(&unsigned).arg(&aligned))?;
    let apk = out.join(format!("{exe_name}.apk"));
    let ks = debug_keystore(sdk)?;
    run(Command::new(sdk.tool("apksigner"))
        .args(["sign", "--ks"])
        .arg(&ks)
        .args(["--ks-pass", "pass:android", "--key-pass", "pass:android", "--ks-key-alias", "bifrost", "--out"])
        .arg(&apk)
        .arg(&aligned))?;
    let _ = fs::remove_file(&unsigned);
    let _ = fs::remove_file(&aligned);
    let _ = fs::remove_dir_all(&assets);
    Ok(apk)
}
