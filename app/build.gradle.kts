plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mobicareapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mobicareapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // FFmpeg's native libraries are the bulk of the app's size. arm64-v8a alone covers
        // effectively every phone sold in the last ~8 years; dropping armeabi-v7a and the
        // emulator-only x86/x86_64 ABIs keeps this test build small enough to deliver.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    // ProcessLifecycleOwner, so app-lock can tell "backgrounded" apart from "just rotated"
    implementation("androidx.lifecycle:lifecycle-process:2.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // App lock: fingerprint/face unlock with the device's own PIN/pattern/password as fallback
    implementation("androidx.biometric:biometric:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // .await() on the ListenableFuture that MediaController.Builder returns
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")
    // .await() on the Task that the document scanner's getStartScanIntent() returns
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Room (caption/name + metadata database)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // YouTube stream extraction (open-source, same engine NewPipe uses)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    // Playback (video + audio) for offline files. media3-session powers a
    // background-capable MediaSessionService so audio keeps playing when the
    // screen locks or the app is backgrounded.
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")

    // Thumbnails
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    // Lets the in-app Browse WebView suppress the X-Requested-With header, which is what
    // makes Google block "Sign in with Google" inside a plain WebView.
    implementation("androidx.webkit:webkit:1.12.1")
    // DocumentFile API for the backup/restore folder picker (SAF)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Camera scan-to-PDF: Google Play Services' own scanning UI (edge detection,
    // crop, multi-page capture) instead of a hand-rolled CameraX flow.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")

    // Video conversion for formats Android's own MediaCodec can't decode at all (the same
    // situation MX Player handles by bundling its own decoders rather than relying on the OS).
    // The original FFmpegKit (com.arthenica:ffmpeg-kit-full-gpl) was retired and pulled from
    // Maven Central in 2025 — this is a community-maintained continuation of the same project
    // (same com.arthenica.ffmpegkit.* API), republished after the original disappeared.
    implementation("com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1")
}
