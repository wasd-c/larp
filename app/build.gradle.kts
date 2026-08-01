plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val llamaCppRelease = "b9637"
val llamaCppArchiveName = "llama-$llamaCppRelease-bin-android-arm64.tar.gz"
val llamaCppArchive = layout.buildDirectory.file("downloads/$llamaCppArchiveName")
val rawQwenRuntimeDirectory = layout.buildDirectory.dir("generated/qwen-runtime-raw/jniLibs")
val qwenRuntimeDirectory = layout.buildDirectory.dir("generated/qwen-runtime/jniLibs")
val ndkHostDirectory = when {
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "darwin-x86_64"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows-x86_64"
    else -> "linux-x86_64"
}
val stripExecutableName = if (ndkHostDirectory.startsWith("windows")) {
    "llvm-strip.exe"
} else {
    "llvm-strip"
}

val downloadQwenRuntime by tasks.registering(PinnedDownloadTask::class) {
    sourceUrl.set(
        "https://github.com/ggml-org/llama.cpp/releases/download/" +
            "$llamaCppRelease/$llamaCppArchiveName"
    )
    expectedSha256.set("66068af2400dbaaadb4dc3e4042d120c6633f115ecd2fe1a8979fb55e0648e4d")
    destination.set(llamaCppArchive)
}

val prepareQwenRuntime by tasks.registering(Copy::class) {
    dependsOn(downloadQwenRuntime)
    from({ tarTree(resources.gzip(llamaCppArchive.get().asFile)) }) {
        include(
            "llama-$llamaCppRelease/llama-server",
            "llama-$llamaCppRelease/libllama-server-impl.so",
            "llama-$llamaCppRelease/libllama-common.so",
            "llama-$llamaCppRelease/libmtmd.so",
            "llama-$llamaCppRelease/libllama.so",
            "llama-$llamaCppRelease/libggml.so",
            "llama-$llamaCppRelease/libggml-base.so",
            "llama-$llamaCppRelease/libggml-cpu-android_*.so"
        )
        eachFile {
            path = "arm64-v8a/" + if (name == "llama-server") {
                "libllama-qwen-server.so"
            } else {
                name
            }
        }
        includeEmptyDirs = false
    }
    into(rawQwenRuntimeDirectory)
}

val stripQwenRuntime by tasks.registering(StripNativeRuntimeTask::class) {
    dependsOn(prepareQwenRuntime)
    sourceDirectory.set(rawQwenRuntimeDirectory)
    stripExecutable.set(
        androidComponents.sdkComponents.ndkDirectory.map { ndk ->
            ndk.file("toolchains/llvm/prebuilt/$ndkHostDirectory/bin/$stripExecutableName")
        }
    )
    destinationDirectory.set(qwenRuntimeDirectory)
}

android {
    namespace = "com.anis.larp"
    ndkVersion = "26.1.10909125"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.anis.larp"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    sourceSets.getByName("main").jniLibs.srcDir(qwenRuntimeDirectory.get().asFile)
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

tasks.named("preBuild").configure {
    dependsOn(stripQwenRuntime)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.mlkit.genai.speech.recognition)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.litert.lm.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.youtube.transcript.api)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
