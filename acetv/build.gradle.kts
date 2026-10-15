plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Nuvio Live: a small TV app with only the Ace Stream channels, played through the Ace Stream
// engine installed on the device. Separate from Nuvio (its own id), sharing no code with it.
android {
    namespace = "com.nuvio.acetv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nuvio.acetv"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    val storeFile = providers.environmentVariable("NUVIO_RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (storeFile != null) {
            create("ci") {
                this.storeFile = file(storeFile)
                storePassword = providers.environmentVariable("NUVIO_RELEASE_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("NUVIO_RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("NUVIO_RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
}
