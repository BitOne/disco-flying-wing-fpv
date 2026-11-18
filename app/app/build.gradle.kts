plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Parrot Ground SDK (Unified GroundSdk fork) as AARs built by `make sdk-aar`.
val sdkAarDir = rootProject.file(
    (findProperty("sdkAarDir") as String?) ?: "../out/sdk-aar",
)

android {
    namespace = "io.github.bitone.discofpv"
    compileSdk = 35
    buildToolsVersion = "35.0.0" // installed in docker/app-build

    defaultConfig {
        applicationId = "io.github.bitone.discofpv"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // libsdkcore.so exists for these ABIs only (emulator, phones).
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(files(sdkAarDir.resolve("sdkcore-testing.aar")))
    androidTestImplementation("org.hamcrest:hamcrest:2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
