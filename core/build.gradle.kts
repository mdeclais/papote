// Code commun à Android et Windows : timecode, WAV BWF, exports SRT/EDL/texte,
// découpage des sous-titres, rangement des interviews, modèles Vosk.
plugins {
    kotlin("jvm") version "2.4.21"
}

group = "io.github.mdeclais.papote"

kotlin { jvmToolchain(17) }

dependencies {
    // Fourni par Android ; ajouté par l'app Windows.
    compileOnly("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
