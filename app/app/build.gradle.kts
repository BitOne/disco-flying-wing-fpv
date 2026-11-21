import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Parrot Ground SDK (Unified GroundSdk fork) as AARs built by `make sdk-aar`.
val sdkAarDir = rootProject.file(
    (findProperty("sdkAarDir") as String?) ?: "../out/sdk-aar",
)

// Release signing (make release): a properties file outside the repository,
// -PreleaseSigning=<path>, with storeFile, storePassword, keyAlias and
// keyPassword (make release-key creates one in ~/.config/disco-fpv-release).
val releaseSigning = (findProperty("releaseSigning") as String?)?.let { path ->
    Properties().apply { file(path).inputStream().use { load(it) } }
}

android {
    namespace = "io.github.bitone.discofpv"
    compileSdk = 35
    buildToolsVersion = "35.0.0" // installed in docker/app-build

    defaultConfig {
        applicationId = "io.github.bitone.discofpv"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
        // libsdkcore.so exists for these ABIs only (emulator, phones).
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseSigning?.let { p ->
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // the debug bridge's controller: the emulator's host by default; a
        // phone on the same network as the PC running fake-sc2: its address
        debug {
            resValue("string", "arsdk_debug_usb_bridge_address",
                (findProperty("debugBridgeAddress") as String?) ?: "10.0.2.2")
            resValue("integer", "arsdk_debug_usb_bridge_port",
                (findProperty("debugBridgePort") as String?) ?: "4321")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        // Debug build without the debug USB bridge (src/debug): reaches the
        // controller through Android's USB accessory path only, for the
        // emulator's emulated USB (rig/scripts/emu-usb-setup.sh) and phones.
        create("usb") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
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
    implementation(files(sdkAarDir.resolve("sdkcore.aar")))
    implementation(files(sdkAarDir.resolve("groundsdk.aar")))
    implementation(files(sdkAarDir.resolve("arsdkengine.aar")))

    // The SDK's own dependencies (local AARs carry no POM), at the versions
    // the SDK was built against, for runtime compatibility.
    implementation("androidx.annotation:annotation:1.1.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.0.0")
    implementation("com.google.android.gms:play-services-location:17.0.0")
    implementation("com.squareup.retrofit2:retrofit:2.6.1")
    implementation("com.squareup.retrofit2:converter-gson:2.6.1")
    implementation("com.squareup.retrofit2:converter-scalars:2.6.1")
    implementation("com.squareup.okhttp3:logging-interceptor:4.2.0")
    implementation("com.google.code.gson:gson:2.8.5")
    implementation("org.conscrypt:conscrypt-android:2.2.1")
    implementation("com.google.firebase:firebase-crashlytics:17.1.0")

    // Offline maps: Mapsforge (LGPL-3.0, see NOTICE) renders .map files.
    implementation("org.mapsforge:mapsforge-map-android:0.25.0")
    implementation("org.mapsforge:mapsforge-themes:0.25.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(files(sdkAarDir.resolve("sdkcore-testing.aar")))
    androidTestImplementation("org.hamcrest:hamcrest:2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// junit 4.13 brings hamcrest-core 1.3, merged into hamcrest 2.1 (the SDK's
// testing AAR): keep only the latter in instrumented tests.
configurations.named("androidTestImplementation") {
    exclude(group = "org.hamcrest", module = "hamcrest-core")
}
