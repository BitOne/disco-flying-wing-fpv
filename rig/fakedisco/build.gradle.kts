// Test helper (not shipped): announces the fake Disco running inside the
// emulator over mDNS, the way a real Disco announces itself on its Wi-Fi,
// so FreeFlight Pro can discover it. Only an app can register NSD services.
plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
}

android {
    namespace = "io.github.bitone.discofpv.fakedisco"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "io.github.bitone.discofpv.fakedisco"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
