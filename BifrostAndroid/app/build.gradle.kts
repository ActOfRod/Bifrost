plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseKeystore = System.getenv("BIFROST_ANDROID_KEYSTORE")
val appVersionCode = System.getenv("BIFROST_VERSION_CODE")?.toInt() ?: 1
val appVersionName = System.getenv("BIFROST_VERSION_NAME") ?: "0.1.0-dev"
val winlatorSource = file("../third_party/winlator-app")
val winlatorOutput = layout.buildDirectory.dir("winlator")
val winlatorStamp = file("build/winlator/.source-sha")
val winlatorRevisionProcess = ProcessBuilder(
    "git",
    "-C",
    winlatorSource.absolutePath,
    "rev-parse",
    "HEAD",
).redirectErrorStream(true).start()
val winlatorRevision = winlatorRevisionProcess.inputStream.bufferedReader().readText().trim()
if (winlatorRevisionProcess.waitFor() != 0) {
    throw GradleException("Unable to read the Winlator submodule revision")
}
if (!winlatorStamp.isFile || winlatorStamp.readText().trim() != winlatorRevision) {
    throw GradleException("Prepared Winlator sources are missing or stale; run python3 scripts/prepare_winlator.py from BifrostAndroid")
}

val prepareWinlator by tasks.registering(org.gradle.api.tasks.Exec::class) {
    inputs.dir(winlatorSource)
    inputs.file(file("../scripts/prepare_winlator.py"))
    outputs.dir(winlatorOutput)
    commandLine("python3", file("../scripts/prepare_winlator.py").absolutePath)
}

android {
    namespace = "com.winlator"
    compileSdk = 35
    ndkVersion = "24.0.8215888"

    defaultConfig {
        applicationId = "com.actofrod"
        minSdk = 26
        // Stage 2 executes Wine/Box64 from app storage; target SDK 29+ blocks this with W^X.
        targetSdk = 28
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("BIFROST_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BIFROST_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("BIFROST_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("build/winlator/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir(winlatorOutput.map { it.dir("java") })
            res.srcDir(winlatorOutput.map { it.dir("res") })
            assets.srcDir(winlatorOutput.map { it.dir("assets") })
            jniLibs.srcDir(winlatorOutput.map { it.dir("jniLibs") })
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

tasks.matching {
    it.name == "preBuild" ||
        it.name.startsWith("preBuild") ||
        it.name.startsWith("configureCMake") ||
        it.name.startsWith("buildCMake")
}.configureEach {
    dependsOn(prepareWinlator)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.4.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.google.android.material:material:1.4.0")
    implementation("com.github.luben:zstd-jni:1.5.2-3@aar")
    implementation("org.tukaani:xz:1.7")
    implementation("org.apache.commons:commons-compress:1.20")
    implementation("androidx.lifecycle:lifecycle-process:2.5.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}
