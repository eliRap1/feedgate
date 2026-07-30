plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.eli.feedgate"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.eli.feedgate"
        // 28+: sans-serif-condensed-medium exists in platform fonts.xml only
        // from Pie; below that the display face silently degrades to default.
        minSdk = 28
        // targetSdk stays 34 on purpose: 35+ enforces edge-to-edge and this
        // personal app has no Play Store target-API requirement.
        targetSdk = 34
        versionCode = 17
        versionName = "1.17"
    }

    buildFeatures {
        buildConfig = true
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}
