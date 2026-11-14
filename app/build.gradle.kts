plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vpncontrol.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vpncontrol.app"
        minSdk = 26
        targetSdk = 34
        ndk { abiFilters += listOf("arm64-v8a") }
        versionCode = 11
        versionName = "0.4.8"
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = rootProject.file("signing/vpn-control-debug.keystore")
            storePassword = "vpncontrol-debug-only"
            keyAlias = "vpncontrol"
            keyPassword = "vpncontrol-debug-only"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
