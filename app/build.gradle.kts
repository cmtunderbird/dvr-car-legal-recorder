plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace   = "com.dashcam.dvr"
    compileSdk  = 34

    defaultConfig {
        applicationId   = "com.dashcam.dvr"
        minSdk          = 26          // Android 8 â€” required for Foreground Service notifications
        targetSdk       = 34
        versionCode     = 1
        versionName     = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signing config applied via CI environment variables â€” see GitHub Actions workflow
        }
        debug {
            isDebuggable     = true
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-DEBUG"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.ExperimentalStdlibApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    // Split APKs per ABI to minimise distribution size
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.androidx.constraintlayout)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime.ktx)

    // CameraX (wraps Camera2; physical ID binding done via Camera2Interop)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)

    // JSON serialisation
    implementation(libs.gson)

    // Security / Android Keystore wrapper
    implementation(libs.security.crypto)

    // WorkManager
    implementation(libs.workmanager.ktx)

    // Fused Location Provider (GPS)
    implementation(libs.play.services.location)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}


