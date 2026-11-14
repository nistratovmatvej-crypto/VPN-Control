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
        versionCode = 8
        versionName = "0.4.5"
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
