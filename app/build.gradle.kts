plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.ytsaver.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ytsaver.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    debugImplementation("androidx.compose.ui:ui-tooling")

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
    // DocumentFile API for the backup/restore folder picker (SAF)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Camera scan-to-PDF: Google Play Services' own scanning UI (edge detection,
    // crop, multi-page capture) instead of a hand-rolled CameraX flow.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
}
