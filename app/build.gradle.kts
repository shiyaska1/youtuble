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
        isCoreLibraryDesugaringEnabled = true
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
    // Detects when the whole app goes to the background/foreground (used by
    // the privacy app-lock to know when to re-lock).
    implementation("androidx.lifecycle:lifecycle-process:2.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Privacy app-lock: fingerprint/face/PIN/pattern/password prompt shown
    // on launch and whenever the app returns from the background.
    implementation("androidx.biometric:biometric:1.1.0")

    // In-app camera for the page-scan capture screen (one tap per page).
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // .await() on the ListenableFuture that MediaController.Builder returns
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")

    // Room (caption/name + metadata database)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // YouTube stream extraction (open-source, same engine NewPipe uses).
    // Using a locally patched copy of the jar instead of the Maven coordinate:
    // one class (Utils.class) was binary-patched to stop calling a
    // URLDecoder/URLEncoder overload that only exists on Android 13+, which
    // crashed extraction on older devices. Only that one method's bytecode
    // was changed; everything else is untouched. Its own transitive
    // dependencies (normally pulled in automatically) are declared explicitly
    // below since a local file() dependency doesn't carry a POM.
    implementation(files("libs/NewPipeExtractor-v0.26.5-patched.jar"))
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.protobuf:protobuf-javalite:4.35.1")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.mozilla:rhino-engine:1.8.1")

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

    // Backports newer java.* APIs (e.g. URLDecoder.decode(String, Charset),
    // used internally by NewPipeExtractor) to devices below API 33.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
