plugins {
    id("com.android.application")
}
android {
    namespace = "com.ejemplo.navegador"
    // GeckoView 159 exige compilar contra Android 37.2 (versión menor de API).
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    defaultConfig {
        applicationId = "com.ejemplo.navegador"
        minSdk = 26
        targetSdk = 34
        versionCode = 37
        versionName = "0.19.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
    packaging {
        jniLibs {
            // Como Firefox para Android: el motor Gecko (libxul.so, ~150 MB)
            // va comprimido dentro del APK. El APK pasa de ~216 MB a ~100 MB;
            // Android extrae las librerías una sola vez al instalar.
            useLegacyPackaging = true
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
androidComponents {
    // AGP 9 does not create this release test component by default. The release
    // workflow runs it explicitly, so keep those tests enabled for both variants.
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests.getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17")
        )
    }
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.10"))
    implementation("org.mozilla.geckoview:geckoview-omni:159.0.20261102144711")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.documentfile:documentfile:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
