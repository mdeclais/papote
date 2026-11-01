package io.github.mdeclais.papote

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File

data class AudioInput(val id: Int, val name: String)

/** État de l'app et logique d'enregistrement. Toutes les méthodes s'appellent sur le thread principal. */
object Recorder {
    enum class Phase { Idle, Preparing, Recording, Finishing }

    lateinit var app: Context
        private set
    lateinit var store: Store
        private set
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
    var localeID by mutableStateOf("fr-FR")
    var fps by mutableStateOf(25)
    /** "" = sans projet. */
    var project by mutableStateOf("")
    var speakersEnabled by mutableStateOf(true)
    var speakerNames by mutableStateOf(listOf("Intervieweur", "Invité"))
    /** 0 = micro par défaut. */
    var inputId by mutableStateOf(0)
    var stereo by mutableStateOf(false)
    /** 16 bits par défaut : un tiers plus léger que 24 bits, largement assez pour la voix. */
    var bitDepth by mutableStateOf(16)
    var speakerNamesInSRT by mutableStateOf(true)
    var autoBackup by mutableStateOf(true)
    var backupTree by mutableStateOf<Uri?>(null)

    private var engine: AudioEngine? = null
    private val loadedModels = mutableMapOf<String, Model>()
    private var speakerChanges = mutableListOf<Pair<Double, Int>>()
    private val autosave = object : Runnable {
        override fun run() {
            autosaveNow()
            main.postDelayed(this, 5_000)
        }
    }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        store = Store(File(app.getExternalFilesDir(null) ?: app.filesDir, "Interviews"))
        prefs = app.getSharedPreferences("papote", Context.MODE_PRIVATE)
        localeID = prefs.getString("localeID", "fr-FR")!!
        fps = prefs.getInt("fps", 25)
        project = prefs.getString("project", "")!!
        speakersEnabled = prefs.getBoolean("speakersEnabled", true)
        speakerNames = prefs.getString("speakerNames", null)?.split("\n") ?: speakerNames
        inputId = prefs.getInt("inputId", 0)
        stereo = prefs.getBoolean("stereo", false)
        bitDepth = prefs.getInt("bitDepth", 16)
        speakerNamesInSRT = prefs.getBoolean("speakerNamesInSRT", true)
        autoBackup = prefs.getBoolean("autoBackup", true)
        backupTree = prefs.getString("backupTree", null)?.let(Uri::parse)
        refreshProjects()
        refreshInputs()
    }

    fun savePrefs() {
        prefs.edit()
            .putString("localeID", localeID).putInt("fps", fps).putString("project", project)
            .putBoolean("speakersEnabled", speakersEnabled).putString("speakerNames", speakerNames.joinToString("\n"))
            .putInt("inputId", inputId).putBoolean("stereo", stereo).putInt("bitDepth", bitDepth)
            .putBoolean("speakerNamesInSRT", speakerNamesInSRT).putBoolean("autoBackup", autoBackup)
            .putString("backupTree", backupTree?.toString())
            .apply()
    }

    val language get() = VoskModels.language(localeID)
    fun modelInstalled(lang: Language) = VoskModels.isInstalled(app.filesDir, lang)

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
        val am = app.getSystemService(AudioManager::class.java)
        inputs.clear()
        am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX && it.type != AudioDeviceInfo.TYPE_FM_TUNER }
            .forEach { d -> inputs += AudioInput(d.id, deviceName(d)) }
        if (inputId != 0 && inputs.none { it.id == inputId }) inputId = 0
    }

    private fun deviceName(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micro intégré"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Casque filaire"
            else -> "Entrée"
        }
        val address = if (Build.VERSION.SDK_INT >= 28 && d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && d.address.isNotEmpty()) " (${d.address})" else ""
        val product = d.productName?.toString().orEmpty()
        return if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC || product.isEmpty() || product == Build.MODEL) "$kind$address" else "$kind · $product"
    }

    val currentInputName get() = inputs.firstOrNull { it.id == inputId }?.name ?: "Micro par défaut"

    // Langues

    fun downloadModel(lang: Language) {
        if (downloading != null) return
        downloading = lang.id to 0f
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    VoskModels.download(app.filesDir, lang) { p -> main.post { downloading = lang.id to p } }
                }.exceptionOrNull()
            }
            downloading = null
            modelsVersion++
            if (error != null) errorMessage = "Téléchargement de la langue impossible : ${error.message}"
        }
    }

    fun deleteModel(lang: Language) {
        loadedModels.remove(lang.model)?.close()
        VoskModels.delete(app.filesDir, lang)
        modelsVersion++
    }

    // Enregistrement

    /** Efface l'écran ; les fichiers de l'interview restent dans la liste. */
    fun clearScreen() {
        if (phase != Phase.Idle) return
        session = null
        volatileText = ""
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
            val model: Model? = if (modelInstalled(lang)) {
                status = "Préparation de la transcription…"
                withContext(Dispatchers.IO) {
                    runCatching { loadedModels.getOrPut(lang.model) { Model(VoskModels.dir(app.filesDir, lang).path) } }
                        .onFailure { main.post { errorMessage = "Transcription désactivée : ${it.message}\nL'audio est enregistré normalement." } }
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

    private fun begin(model: Model?) {
        try {
            val project = project.ifEmpty { null }
            val now = System.currentTimeMillis()
            val id = Store.makeID(now, project)
            store.createFolder(id, project)
            val template = Session(id = id, startDateMs = now, startTOD = 0.0, sampleRate = 0.0, fps = fps,
                                   localeID = localeID, project = project,
                                   speakers = if (speakersEnabled) speakerNames else null)
            speakerChanges = mutableListOf(0.0 to currentSpeaker)
            val device = app.getSystemService(AudioManager::class.java)
                .getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == inputId }
            val engine = AudioEngine(store.audio(template), "Interview $id" + (project?.let { " · $it" } ?: ""),
                                     if (stereo) 2 else 1, bitDepth, device, model)
            engine.onStart = { info ->
                main.post {
                    val s = template.copy(startDateMs = info.dateMs, startTOD = info.timeReference.toDouble() / info.sampleRate,
                                          sampleRate = info.sampleRate.toDouble(), channels = info.channels, bitDepth = bitDepth)
                    session = s
                    store.save(s)
                }
            }
            engine.onLevel = { l -> main.post { level = l } }
            engine.onError = { message -> main.post { errorMessage = message; stop() } }
            engine.onPartial = { text -> main.post { volatileText = text } }
            engine.onWords = { words -> main.post { addWords(words) } }
            RecordingService.start(app)
            engine.start()
            this.engine = engine
            phase = Phase.Recording
            main.postDelayed(autosave, 5_000)
        } catch (t: Throwable) {
            RecordingService.stop(app)
            errorMessage = t.message ?: t.toString()
            phase = Phase.Idle
        }
    }

    fun stop() {
        val engine = engine ?: return
        if (phase != Phase.Recording) return
        phase = Phase.Finishing
        status = "Finalisation…"
        main.removeCallbacks(autosave)
        scope.launch {
            val duration = withContext(Dispatchers.IO) { engine.stop() }
            // Les derniers mots sont postés par le thread de transcription : on les laisse arriver.
            main.post {
                openIn?.let { closeExtract(it, duration) }
                volatileText = ""
                session?.let { s0 ->
                    val s = s0.copy(duration = duration, finished = true,
                                    backupPending = autoBackup && backupTree != null)
                    session = s
                    store.save(s)
                    store.writeExports(s, speakerNamesInSRT)
                    if (s.backupPending) backUp(s)
                }
                Recorder.engine = null
                level = 0f
                status = null
                RecordingService.stop(app)
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
        if (index == currentSpeaker) return
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
        val recovered = store.recoverInterrupted(speakerNamesInSRT, backupTree != null)
        val last = recovered.maxByOrNull { it.startDateMs } ?: return
        session = last
        errorMessage = "L'interview ${last.id} avait été interrompue. Elle a été récupérée jusqu'à ${last.tc(last.duration)} (fin de l'audio sur le disque)."
    }

    // Sauvegarde

    /** Textes d'abord (quelques Ko, disponibles tout de suite), puis le WAV. */
    fun backUp(s: Session) {
        val tree = backupTree ?: run { backupStatus = "Aucun dossier de sauvegarde : choisis-en un dans les réglages."; return }
        backupRunning = true
        backupStatus = "Sauvegarde de ${s.id}…"
        scope.launch {
            val textError = withContext(Dispatchers.IO) { Backup.run(app, tree, s.project, store.textFiles(s)) }
            if (textError == null) backupStatus = "Textes sauvegardés · envoi de l'audio…"
            val audioError = withContext(Dispatchers.IO) { Backup.run(app, tree, s.project, listOf(store.audio(s)).filter { it.exists() }) }
            backupRunning = false
            val error = textError ?: audioError
            backupStatus = if (error == null) "Sauvegardé dans « ${Backup.folderName(app, tree)} »." else "Échec de la sauvegarde : $error"
            if (error == null) {
                val done = (store.loadAll().firstOrNull { it.id == s.id } ?: s).copy(backupPending = false)
                store.save(done)
                if (session?.id == s.id) session = session?.copy(backupPending = false)
            }
        }
    }

    /** Au lancement : relance les sauvegardes restées en échec. */
    fun retryPendingBackups() {
        if (backupTree == null) return
        store.loadAll().filter { it.backupPending && it.finished }.forEach { backUp(it) }
    }
}
