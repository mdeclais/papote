package io.github.mdeclais.papote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.prefs.Preferences

/** État de l'app et logique d'enregistrement. Toutes les méthodes s'appellent sur le thread de l'interface. */
object Recorder {
    enum class Phase { Idle, Preparing, Recording, Finishing }

    val store = Store(Desktop.interviewsRoot)
    private val prefs = Preferences.userRoot().node("io/github/mdeclais/papote")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun ui(block: () -> Unit) { scope.launch { block() } }

    var phase by mutableStateOf(Phase.Idle); private set
    var status by mutableStateOf<String?>(null); private set
    var errorMessage by mutableStateOf<String?>(null)
    /** Interview en cours, ou la dernière terminée tant qu'on n'a pas effacé l'écran. */
    var session by mutableStateOf<Session?>(null); private set
    var volatileText by mutableStateOf(""); private set
    var level by mutableStateOf(0f); private set
    val projects = mutableStateListOf<String>()
    val inputs = mutableStateListOf<AudioInput>()
    var currentSpeaker by mutableStateOf(0); private set
    /** Début de l'extrait en cours (IN posé, OUT pas encore), en secondes. */
    var openIn by mutableStateOf<Double?>(null); private set
    var backupStatus by mutableStateOf<String?>(null); private set
    var backupRunning by mutableStateOf(false); private set
    /** Langue en cours de téléchargement et avancement 0…1. */
    var downloading by mutableStateOf<Pair<String, Float>?>(null); private set
    /** Incrémenté quand un modèle est installé ou supprimé, pour rafraîchir les écrans. */
    var modelsVersion by mutableStateOf(0); private set

    // Réglages
    var localeID by mutableStateOf(prefs.get("localeID", "fr-FR"))
    var fps by mutableStateOf(prefs.getInt("fps", 25))
    /** "" = sans projet. */
    var project by mutableStateOf(prefs.get("project", ""))
    var speakersEnabled by mutableStateOf(prefs.getBoolean("speakersEnabled", true))
    /** Index 0 : l'intervieweur ; ensuite 1 à [MAX_GUESTS] invités. */
    var speakerNames by mutableStateOf(
        prefs.get("speakerNames", null)?.split("\n")?.takeIf { it.size >= 2 }?.take(6) ?: listOf("Intervieweur", "Invité"))
    const val MAX_GUESTS = 5
    val guestCount get() = speakerNames.size - 1
    /** "" = entrée par défaut de Windows. */
    var inputId by mutableStateOf(prefs.get("inputId", ""))
    var stereo by mutableStateOf(prefs.getBoolean("stereo", false))
    /** 16 bits par défaut : un tiers plus léger que 24 bits, largement assez pour la voix. */
    var bitDepth by mutableStateOf(prefs.getInt("bitDepth", 16))
    var speakerNamesInSRT by mutableStateOf(prefs.getBoolean("speakerNamesInSRT", true))
    var autoBackup by mutableStateOf(prefs.getBoolean("autoBackup", true))
    /** Dossier de sauvegarde : OneDrive, Google Drive pour ordinateur, disque externe… */
    var backupDir by mutableStateOf(prefs.get("backupDir", null)?.let(::File))

    private var engine: AudioEngine? = null
    private var autosaveJob: Job? = null
    private val loadedModels = mutableMapOf<String, VoskModel>()
    private var speakerChanges = mutableListOf<Pair<Double, Int>>()

    init {
        refreshProjects()
        refreshInputs()
    }

    fun setGuestCount(count: Int) {
        val n = count.coerceIn(1, MAX_GUESTS)
        val names = speakerNames.take(n + 1).toMutableList()
        while (names.size - 1 < n) names += "Invité ${names.size}"
        // Un seul invité : « Invité » tout court.
        if (n > 1 && names[1] == "Invité") names[1] = "Invité 1"
        if (n == 1 && names[1] == "Invité 1") names[1] = "Invité"
        speakerNames = names
        if (currentSpeaker > n) currentSpeaker = 0
        savePrefs()
    }

    fun savePrefs() {
        prefs.put("localeID", localeID); prefs.putInt("fps", fps); prefs.put("project", project)
        prefs.putBoolean("speakersEnabled", speakersEnabled); prefs.put("speakerNames", speakerNames.joinToString("\n"))
        prefs.put("inputId", inputId); prefs.putBoolean("stereo", stereo); prefs.putInt("bitDepth", bitDepth)
        prefs.putBoolean("speakerNamesInSRT", speakerNamesInSRT); prefs.putBoolean("autoBackup", autoBackup)
        backupDir?.let { prefs.put("backupDir", it.path) } ?: prefs.remove("backupDir")
        runCatching { prefs.flush() }
    }

    val language get() = VoskModels.language(localeID)
    fun modelInstalled(lang: Language) = VoskModels.isInstalled(Desktop.dataDir, lang)

    // Projets

    fun refreshProjects() {
        projects.clear(); projects.addAll(store.projects())
        if (project.isNotEmpty() && project !in projects) { project = ""; savePrefs() }
    }

    fun createProject(name: String) {
        runCatching { store.createProject(name) }
            .onSuccess { project = it; savePrefs(); refreshProjects() }
            .onFailure { errorMessage = "Nom de projet invalide." }
    }

    // Micro

    fun refreshInputs() {
        inputs.clear(); inputs.addAll(AudioEngine.inputs())
    }

    /** Une entrée débranchée garde son nom : elle reviendra quand on la rebranche. */
    val currentInputName get() = inputId.ifEmpty { "Micro par défaut" }

    // Langues

    fun downloadModel(lang: Language) {
        if (downloading != null) return
        downloading = lang.id to 0f
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    VoskModels.download(Desktop.dataDir, lang) { p -> ui { downloading = lang.id to p } }
                }.exceptionOrNull()
            }
            downloading = null
            modelsVersion++
            if (error != null) errorMessage = "Téléchargement de la langue impossible : ${error.message}"
        }
    }

    fun deleteModel(lang: Language) {
        loadedModels.remove(lang.model)?.close()
        VoskModels.delete(Desktop.dataDir, lang)
        modelsVersion++
    }

    // Enregistrement

    /** Efface l'écran ; les fichiers de l'interview restent dans la liste. */
    fun clearScreen() {
        if (phase != Phase.Idle) return
        session = null
        volatileText = ""
    }

    fun toggle() {
        when (phase) {
            Phase.Idle -> start()
            Phase.Recording -> stop()
            else -> {}
        }
    }

    fun start() {
        if (phase != Phase.Idle) return
        errorMessage = null
        session = null
        volatileText = ""
        openIn = null
        phase = Phase.Preparing
        val lang = language
        scope.launch {
            // Sans modèle de langue, on enregistre quand même l'audio horodaté.
            val model: VoskModel? = if (modelInstalled(lang)) {
                status = "Préparation de la transcription…"
                withContext(Dispatchers.IO) {
                    runCatching { loadedModels.getOrPut(lang.model) { VoskModel(VoskModels.dir(Desktop.dataDir, lang).path) } }
                        .onFailure { ui { errorMessage = "Transcription désactivée : ${it.message}\nL'audio est enregistré normalement." } }
                        .getOrNull()
                }
            } else {
                errorMessage = "La langue « ${lang.name} » n'est pas téléchargée : transcription désactivée, l'audio est enregistré normalement."
                null
            }
            status = null
            begin(model)
        }
    }

    private fun begin(model: VoskModel?) {
        try {
            val project = project.ifEmpty { null }
            val now = System.currentTimeMillis()
            val id = Store.makeID(now, project)
            store.createFolder(id, project)
            val template = Session(id = id, startDateMs = now, startTOD = 0.0, sampleRate = 0.0, fps = fps,
                                   localeID = localeID, project = project,
                                   speakers = if (speakersEnabled) speakerNames else null)
            speakerChanges = mutableListOf(0.0 to currentSpeaker)
            val engine = AudioEngine(store.audio(template), "Interview $id" + (project?.let { " · $it" } ?: ""),
                                     if (stereo) 2 else 1, bitDepth, inputId, model)
            engine.onStart = { info ->
                ui {
                    val s = template.copy(startDateMs = info.dateMs, startTOD = info.timeReference.toDouble() / info.sampleRate,
                                          sampleRate = info.sampleRate.toDouble(), channels = info.channels, bitDepth = bitDepth)
                    session = s
                    store.save(s)
                }
            }
            engine.onLevel = { l -> ui { level = l } }
            engine.onError = { message -> ui { errorMessage = message; stop() } }
            engine.onPartial = { text -> ui { volatileText = text } }
            engine.onWords = { words -> ui { addWords(words) } }
            engine.start()
            this.engine = engine
            Desktop.keepAwake(true)
            phase = Phase.Recording
            autosaveJob = scope.launch {
                while (isActive) {
                    delay(5_000)
                    autosaveNow()
                }
            }
        } catch (t: Throwable) {
            errorMessage = t.message ?: t.toString()
            phase = Phase.Idle
        }
    }

    fun stop() {
        val engine = engine ?: return
        if (phase != Phase.Recording) return
        phase = Phase.Finishing
        status = "Finalisation…"
        autosaveJob?.cancel()
        scope.launch {
            val duration = withContext(Dispatchers.IO) { engine.stop() }
            // Les derniers mots sont postés par le thread de transcription : on les laisse arriver.
            ui {
                openIn?.let { closeExtract(it, duration) }
                volatileText = ""
                session?.let { s0 ->
                    val s = s0.copy(duration = duration, finished = true,
                                    backupPending = autoBackup && backupDir != null)
                    session = s
                    store.save(s)
                    store.writeExports(s, speakerNamesInSRT)
                    if (s.backupPending) backUp(s)
                }
                Recorder.engine = null
                level = 0f
                status = null
                Desktop.keepAwake(false)
                phase = Phase.Idle
            }
        }
    }

    /** Toutes les 5 s : session.json, SRT, texte et EDL à jour. Le WAV remet ses tailles d'en-tête à jour au même rythme. */
    private fun autosaveNow() {
        if (phase != Phase.Recording) return
        val s = session?.copy(duration = now()) ?: return
        session = s
        store.save(s)
        store.writeExports(s, speakerNamesInSRT)
    }

    /** Position courante dans l'enregistrement, en secondes. */
    fun now(): Double = session?.let { (System.currentTimeMillis() - it.startDateMs) / 1000.0 } ?: 0.0

    /** IN : ouvre un extrait. Un second IN avant OUT le redémarre ici. */
    fun markIn() {
        if (phase != Phase.Recording || session == null) return
        openIn = now()
    }

    /** OUT : ferme l'extrait ouvert et l'enregistre comme marqueur avec durée. */
    fun markOut() {
        val start = openIn ?: return
        closeExtract(start, now())
    }

    private fun closeExtract(start: Double, end: Double) {
        openIn = null
        val s = session ?: return
        val marker = Marker(time = start, name = "Extrait ${s.markers.size + 1}",
                            duration = maxOf(end - start, 1.0 / s.fps))
        session = s.copy(markers = s.markers + marker).also { store.save(it) }
    }

    fun setSpeaker(index: Int) {
        if (index == currentSpeaker || index !in speakerNames.indices) return
        currentSpeaker = index
        if (phase == Phase.Recording) speakerChanges += now() to index
    }

    private fun speaker(at: Double): Int? {
        if (session?.speakers == null) return null
        return speakerChanges.lastOrNull { it.first <= at }?.second ?: speakerChanges.firstOrNull()?.second
    }

    private fun addWords(words: List<Word>) {
        val s = session ?: return
        val cues = CueBuilder.cues(words).map { it.copy(speaker = speaker(it.start)) }
        session = s.copy(segments = s.segments + cues).also { store.save(it) }
    }

    // Interviews enregistrées

    fun update(s: Session) {
        store.save(s)
        store.writeExports(s, speakerNamesInSRT)
        if (session?.id == s.id) session = s
    }

    fun delete(s: Session) {
        store.delete(s)
        if (session?.id == s.id) session = null
    }

    /** Au lancement : répare les interviews coupées par un plantage et réaffiche la plus récente. */
    fun recoverInterrupted() {
        if (phase != Phase.Idle) return
        val recovered = store.recoverInterrupted(speakerNamesInSRT, backupDir != null)
        val last = recovered.maxByOrNull { it.startDateMs } ?: return
        session = last
        errorMessage = "L'interview ${last.id} avait été interrompue. Elle a été récupérée jusqu'à ${last.tc(last.duration)} (fin de l'audio sur le disque)."
    }

    // Sauvegarde

    /** Copie bloquante dans <dossier>/<projet>/ ; renvoie un message d'erreur, ou null si tout est copié. */
    private fun copy(dir: File, project: String?, files: List<File>): String? = runCatching {
        if (!dir.isDirectory) error("Dossier « ${dir.path} » introuvable (disque débranché ?).")
        val folder = File(dir, project ?: "Sans projet").apply { mkdirs() }
        for (file in files) {
            val tmp = File(folder, ".${file.name}.partiel")
            Files.copy(file.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp.toPath(), File(folder, file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        null
    }.getOrElse { it.message ?: it.toString() }

    /** Textes d'abord (quelques Ko, disponibles tout de suite), puis le WAV. */
    fun backUp(s: Session) {
        val dir = backupDir ?: run { backupStatus = "Aucun dossier de sauvegarde : choisis-en un dans les réglages."; return }
        backupRunning = true
        backupStatus = "Sauvegarde de ${s.id}…"
        scope.launch {
            val textError = withContext(Dispatchers.IO) { copy(dir, s.project, store.textFiles(s)) }
            if (textError == null) backupStatus = "Textes sauvegardés · copie de l'audio…"
            val audioError = withContext(Dispatchers.IO) { copy(dir, s.project, listOf(store.audio(s)).filter { it.exists() }) }
            backupRunning = false
            val error = textError ?: audioError
            backupStatus = if (error == null) "Sauvegardé dans « ${dir.name} »." else "Échec de la sauvegarde : $error"
            if (error == null) {
                val done = (store.loadAll().firstOrNull { it.id == s.id } ?: s).copy(backupPending = false)
                store.save(done)
                if (session?.id == s.id) session = session?.copy(backupPending = false)
            }
        }
    }

    /** Au lancement : relance les sauvegardes restées en échec. */
    fun retryPendingBackups() {
        if (backupDir == null) return
        store.loadAll().filter { it.backupPending && it.finished }.forEach { backUp(it) }
    }
}
