import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("io.github.mdeclais.papote:core")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.json:json:20250517")
    // Transcription hors ligne ; le jar contient libvosk pour Windows, Mac et Linux.
    implementation("com.alphacephei:vosk:0.3.45")
    implementation("net.java.dev.jna:jna-platform:5.18.1")
}

compose.desktop {
    application {
        mainClass = "io.github.mdeclais.papote.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Papote"
            packageVersion = "1.1.0"
            description = "Interviews horodatées et transcrites"
            vendor = "mdeclais"
            copyright = "MIT"
            modules("java.instrument", "java.prefs", "jdk.unsupported")
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "Papote"
                perUserInstall = true
                dirChooser = true
                shortcut = true
                // Identifiant fixe : une nouvelle version remplace l'ancienne.
                upgradeUuid = "5b0c5f3e-8c7a-4d2b-9a51-2f6d0e3c7a14"
            }
            macOS { iconFile.set(project.file("icon.icns")) }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Rendus d'écran pour vérification visuelle : build/screens/*.png
    systemProperty("papote.screens", layout.buildDirectory.dir("screens").get().asFile.path)
    systemProperty("papote.speech", System.getProperty("papote.speech") ?: "")
    systemProperty("papote.speechLang", System.getProperty("papote.speechLang") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
