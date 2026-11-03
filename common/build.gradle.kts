plugins {
    id("com.android.library")
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 17
        multiDexEnabled = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes.
        unitTests.all { it.maxHeapSize = "2g" }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(project(":shared"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    // OkHttp 3.12 is the last API 19-compatible line. Keep the legacy updater off
    // Android 4.4's system HttpsURLConnection, which can fall back to SSLv3.
    implementation("com.squareup.okhttp3:okhttp-urlconnection:3.12.13")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.jmdns)
    // Wi-Fi join QR code on the wireless-failure dialog (pure-Java ZXing encoder).
    // Local jar: Gradle's JVM cannot TLS through the proxy, so the Maven artifact is
    // vendored instead of resolved (com.google.zxing:core:3.5.3, SHA-256
    // 8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82).
    implementation(files("libs/zxing-core-3.5.3.jar"))
}
