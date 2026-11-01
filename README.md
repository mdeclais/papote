# Papote

App d'interview pour **iPhone, Mac, Windows et Android**.

- [Installer sur Android](#android) : un fichier à télécharger, sans ordinateur.
- [Installer sur iPhone](#iphone) : avec un Mac et Xcode.
- [Installer sur Mac](#mac) : avec Xcode, même projet que l'iPhone.
- [Installer sur Windows](#windows) : un installeur à télécharger.

Papote enregistre une interview avec le timecode horaire local et la
transcrit en direct. Tu indiques qui parle d'une touche : un intervieweur et
jusqu'à 5 invités, dont les noms apparaissent dans les sous-titres. Elle produit :

- un WAV BWF qui porte le timecode, lisible par DaVinci Resolve ;
- des sous-titres SRT ;
- des marqueurs EDL ;
- un texte horodaté.

## Android

1. Sur le téléphone, ouvre la page
   [Releases](https://github.com/mdeclais/papote/releases/latest) et
   touche **Papote-android.apk** pour le télécharger.
2. Ouvre le fichier téléchargé. La première fois, Android demande
   d'autoriser ton navigateur à « installer des applis inconnues » :
   accepte, puis reviens en arrière et touche **Installer**.
3. Ouvre Papote. Touche la pastille de langue puis **Français** pour
   télécharger la transcription (41 Mo, une seule fois). Elle fonctionne
   ensuite sans Internet.

Android 8 ou plus récent. La transcription utilise
[Vosk](https://alphacephei.com/vosk/), directement sur le téléphone. Elle est
un peu moins précise que celle de l'iPhone et ne met pas de ponctuation. Les
fichiers produits sont les mêmes que sur iPhone : WAV BWF au timecode
horaire, SRT, EDL et texte.

Pour récupérer les fichiers, touche **Partager** à la fin d'une interview :
Drive, Gmail, WeTransfer… Tu peux aussi choisir un dossier de sauvegarde
automatique dans les réglages.

Le code Android est dans le dossier `android/` : Kotlin et Jetpack Compose,
compilable avec Android Studio. Il partage le dossier `core/` avec la version
Windows (timecode, WAV BWF, exports, rangement des interviews).

## Windows

1. Ouvre la page [Releases](https://github.com/mdeclais/papote/releases) et
   télécharge **Papote-windows.msi** (version « pour Windows »).
2. Ouvre le fichier. Windows affiche « Windows a protégé votre ordinateur »
   parce que l'installeur n'est pas signé : clique sur **Informations
   complémentaires**, puis **Exécuter quand même**.
3. Lance Papote depuis le menu Démarrer. Clique sur la pastille de langue
   puis sur **Français** pour télécharger la transcription (41 Mo, une fois).

Windows 10 ou 11, 64 bits. Comme sur Android, la transcription utilise Vosk,
sans Internet et sans ponctuation.

- Les interviews sont rangées dans **Documents\Papote**, un dossier par projet.
- Pendant l'enregistrement, les touches **1** à **6** désignent la personne
  qui parle, **I** et **O** posent le début et la fin d'un extrait.
- Le PC ne se met pas en veille pendant un enregistrement.
- Dans les réglages, choisis un dossier de sauvegarde dans OneDrive, Google
  Drive (avec l'app pour ordinateur) ou sur un disque externe.
- Si aucun son n'arrive : Paramètres > Confidentialité et sécurité >
  Microphone, autorise les applications de bureau.

Le code est dans `windows/` (Kotlin et Compose Desktop). L'installeur est
fabriqué par GitHub sur une machine Windows (`.github/workflows/windows.yml`).

## iPhone

### Ce qu'il te faut

- Un Mac avec **Xcode 26 ou plus récent**, gratuit sur le Mac App Store.
- Un iPhone sous **iOS 26 ou plus récent** et son câble.
- Un **identifiant Apple**. Un compte gratuit suffit.

### Installation (environ 10 minutes)

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

### À savoir

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

### Fichiers (iPhone et Mac)

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
| `Platform.swift` | ce qui diffère entre iPhone et Mac (entrées audio du Mac, veille) |

## Mac

La version Mac est la même app que celle de l'iPhone, dans le même projet
Xcode. Il faut **macOS 26 ou plus récent** et **Xcode 26 ou plus récent**.

1. Récupère le projet (« Code » > « Download ZIP ») et ouvre
   `InterviewTC.xcodeproj`.
2. Dans **Signing & Capabilities**, choisis ta Team (Personal Team) ou,
   pour le Mac seulement, « Sign to Run Locally ».
3. En haut de la fenêtre, choisis **My Mac** comme destination, puis ▶︎.
4. Pour garder l'app : Product > Show Build Folder in Finder, puis glisse
   `Products/Debug/Papote.app` dans ton dossier Applications.

Sur Mac :

- les interviews sont rangées dans `Documents/Papote/`, un dossier par projet ;
- l'entrée audio (micro du Mac, interface USB, micro Bluetooth) se choisit
  dans les réglages ;
- les touches **1** à **6** désignent la personne qui parle (1 =
  l'intervieweur) ;
- le Mac ne se met pas en veille pendant un enregistrement ;
- la sauvegarde Google Drive passe par l'app Google Drive pour ordinateur.

## Licence

MIT. Tu peux utiliser, modifier et redistribuer Papote librement, à
condition de garder la mention de licence (voir `LICENSE`).
