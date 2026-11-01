# Papote — installer l'app sur ton iPhone

Papote enregistre une interview sur iPhone avec le timecode horaire local
et la transcrit en direct. Elle produit :

- un WAV BWF qui porte le timecode, lisible par DaVinci Resolve ;
- des sous-titres SRT ;
- des marqueurs EDL ;
- un texte horodaté.

## Ce qu'il te faut

- Un Mac avec **Xcode 26 ou plus récent**, gratuit sur le Mac App Store.
- Un iPhone sous **iOS 26 ou plus récent** et son câble.
- Un **identifiant Apple**. Un compte gratuit suffit.

## Installation (environ 10 minutes)

1. **Récupère le projet.** Sur cette page GitHub, clique sur « Code » puis
   « Download ZIP ». Dézippe le dossier, puis double-clique sur
   `InterviewTC.xcodeproj`.
2. **Ajoute ton compte.** Dans Xcode > Réglages > Comptes (Accounts),
   clique sur « + » puis connecte ton identifiant Apple.
3. **Règle la signature.**
   - Dans la colonne de gauche, clique sur le projet **InterviewTC**
     (l'icône bleue), puis sur la cible **InterviewTC**.
   - Ouvre l'onglet **Signing & Capabilities**.
   - **Team** : choisis ton nom, avec la mention « (Personal Team) ».
   - **Bundle Identifier** : remplace `com.example.papote` par un nom
     unique, par exemple `fr.tonnom.papote`.
4. **Prépare l'iPhone.** Branche-le, déverrouille-le et accepte « Faire
   confiance à cet ordinateur ». La première fois, active aussi le
   **mode développeur** : Réglages > Confidentialité et sécurité > Mode
   développeur, puis redémarre l'iPhone.
5. **Lance l'installation.**
   - En haut de la fenêtre Xcode, choisis ton iPhone comme destination.
   - Appuie sur le bouton ▶︎ (ou ⌘R).
6. **Autorise l'app sur l'iPhone.** Au premier lancement, l'iPhone refuse
   d'ouvrir une app de « développeur non approuvé ». Va dans Réglages >
   Général > VPN et gestion de l'appareil, touche ton identifiant Apple,
   puis « Faire confiance ».

## À savoir

- **Avec un compte gratuit, l'app cesse de s'ouvrir au bout de 7 jours.**
  Tes enregistrements restent sur l'iPhone. Pour la relancer, rebranche
  l'iPhone au Mac et refais ▶︎ dans Xcode. Avec le programme Apple
  Developer (99 € par an), la signature tient un an.
- **Première utilisation.**
  - Autorise le micro et la reconnaissance vocale.
  - Choisis la langue de transcription. Le modèle de langue se télécharge
    une fois, il faut donc une connexion Internet ce jour-là.
  - Ensuite, la transcription se fait entièrement sur l'iPhone.
- **Sauvegarde cloud** : dans Réglages > iCloud Drive ou Google Drive,
  touche « Se connecter… » et choisis un dossier. Pour Google Drive, l'app
  Google Drive doit être installée et activée dans l'app Fichiers.
- **Dans DaVinci Resolve** : chaque export existe en deux versions.
  - Les fichiers **sans** suffixe sont calés sur une timeline qui commence
    à 01:00:00:00, le WAV posé au début.
  - Les fichiers `_TC-horaire` utilisent l'heure réelle de l'enregistrement.
  - Choisis dans Papote la même cadence (i/s) que ta timeline.
  - Pour importer les marqueurs : clic droit sur la timeline > Timelines >
    Import > Timeline Markers from EDL.

## Fichiers

| Fichier | Rôle |
|---|---|
| `ContentView.swift` | écran principal, enregistrement |
| `RecorderModel.swift` | logique d'enregistrement, marqueurs IN/OUT, locuteurs |
| `AudioPipeline.swift`, `BWFWriter.swift` | capture audio et écriture du WAV BWF |
| `LiveTranscriber.swift` | transcription sur l'appareil (SpeechAnalyzer) |
| `SessionStore.swift`, `Timecode.swift` | interviews, projets, exports SRT, EDL et texte |
| `SessionsView.swift` | liste des interviews et correction du texte |
| `SettingsView.swift`, `Backup.swift` | réglages, sauvegarde iCloud et Google Drive |
| `Theme.swift` | couleurs et style |

## Licence

MIT. Tu peux utiliser, modifier et redistribuer Papote librement, à
condition de garder la mention de licence (voir `LICENSE`).
