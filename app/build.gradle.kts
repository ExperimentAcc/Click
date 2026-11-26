plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "zx.offical.click"
    compileSdk = 34

    defaultConfig {
        applicationId = "zx.offical.click"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    // Signing: the project ships a demo keystore so `assembleRelease` produces a
    // directly installable APK out of the box. For a real release, generate your
    // own keystore and replace these credentials — never ship production keys.
    signingConfigs {
        create("release") {
            keyAlias = "click"
            keyPassword = "click123"
            storeFile = file("keystore/click-release.jks")
            storePassword = "click123"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // Ultra-lightweight release build: R8 minification + resource shrinking.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // Lightweight AndroidX essentials (no Material components, no icon packs).
    implementation("androidx.core:core-ktx:1.13.1")

    // Jetpack CameraX — exactly the five requested artifacts.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
}
