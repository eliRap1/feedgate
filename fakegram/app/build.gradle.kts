plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Test double for FeedGate's Instagram detectors: same package name,
// same view IDs / content-descriptions / visibility semantics as the
// real app (structure taken from on-device dumps, 2026-07-29).
android {
    namespace = "com.instagram.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.instagram.android"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "fake-1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
