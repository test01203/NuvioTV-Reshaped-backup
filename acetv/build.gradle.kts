plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Live Channels: a small TV app with only the Ace Stream channels, played through the Ace Stream
// engine installed on the device. A standalone app with its own id.
android {
    // Engine, catalogue, lists and subtitles, shared with the PC app.
    sourceSets["main"].java.srcDir("../shared/src")
    namespace = "app.livechannels"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.livechannels"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    val storeFile = providers.environmentVariable("LIVE_SIGNING_STORE_FILE").orNull
    signingConfigs {
        if (storeFile != null) {
            create("ci") {
                this.storeFile = file(storeFile)
                storePassword = providers.environmentVariable("LIVE_SIGNING_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("LIVE_SIGNING_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("LIVE_SIGNING_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // Off until the app is proven on devices: R8 removing something must not be a cause of failure.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    lint {
        // Media3's @UnstableApi calls are opted into in code; lint should not stop a build over them.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.tv.material)
    implementation(libs.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // FFmpeg sound decoders (AC3, E-AC3, DTS…), the same build the main app ships.
    implementation(files("../app/libs/lib-decoder-ffmpeg-release.aar"))
    implementation(libs.media3.decoder)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
}
