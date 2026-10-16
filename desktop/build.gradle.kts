import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    id("org.jetbrains.compose") version "1.9.3"
}

kotlin {
    // Engine, catalogue, lists, subtitles and sync: the same code as the TV app.
    sourceSets["main"].kotlin.srcDir("../shared/src")
}

// The TV app's texts (English and Hebrew), read by the PC app at run time.
tasks.processResources {
    from("../acetv/src/main/res/values/strings.xml") { rename { "en.xml" }; into("strings") }
    from("../acetv/src/main/res/values-iw/strings.xml") { rename { "he.xml" }; into("strings") }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("uk.co.caprica:vlcj:4.8.3")
}

compose.desktop {
    application {
        mainClass = "app.livechannels.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx1g")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "LiveChannels"
            packageVersion = (System.getenv("APP_VERSION") ?: "1.0.0")
            description = "Live Channels"
            vendor = "Live Channels"
            // VLC (the player's engine) is put here by the build, see the workflow.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules("java.instrument", "java.management", "java.naming", "java.net.http", "java.prefs", "java.sql", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                menu = true
                menuGroup = "Live Channels"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "5b0e1c52-8f0e-4d3a-9a3c-2f7e6c1d4a91"
                iconFile.set(project.layout.projectDirectory.file("icon.ico"))
            }
        }
    }
}
