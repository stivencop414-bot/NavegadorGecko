plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ejemplo.navegador"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ejemplo.navegador"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "0.7.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    buildFeatures {
        compose = false
        viewBinding = false
        buildConfig = false
    }
}
dependencies {
    implementation("org.mozilla.geckoview:geckoview-omni:153.0.20260715202819")
    implementation("androidx.core:core:1.13.1")
}
