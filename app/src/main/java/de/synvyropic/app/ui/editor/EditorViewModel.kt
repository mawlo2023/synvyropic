package de.synvyropic.app.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import de.synvyropic.app.data.ProjectRepository
import de.synvyropic.app.media.AudioDecoder
import de.synvyropic.app.media.FaceAnalyzer
import de.synvyropic.model.FitMode
import de.synvyropic.model.Framing
import de.synvyropic.app.media.MediaImporter
import de.synvyropic.audio.BeatDetector
import de.synvyropic.audio.Waveform
import de.synvyropic.model.AspectFormat
import de.synvyropic.model.AutoCut
import de.synvyropic.model.LengthMode
import de.synvyropic.model.MusicTrack
import de.synvyropic.model.Project
import de.synvyropic.model.Styles
import de.synvyropic.model.TimedClip
import de.synvyropic.model.Timeline
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import de.synvyropic.model.Transition
import de.synvyropic.model.TextOverlay
import de.synvyropic.model.TextStyle
import de.synvyropic.model.TitleCard
import de.synvyropic.model.Watermark
import de.synvyropic.model.ColorGrade
import de.synvyropic.model.CubeLut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class EditorState(
    val project: Project? = null,
    val importingImages: Int = 0,
    /** Anzahl Bilder, deren Gesichtserkennung noch läuft. */
    val analyzingFaces: Int = 0,
    val musicTitle: String? = null,
    val musicDurationMs: Long = 0,
    /** null = keine Analyse aktiv, sonst Fortschritt 0..1 */
    val analysisProgress: Float? = null,
    val timeline: List<TimedClip> = emptyList(),
    val error: String? = null,
)

/** Bitte an die Vorschau: Bereich [startMs, endMs] in Schleife abspielen. */
data class PreviewFocus(val startMs: Long, val endMs: Long)

class EditorViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {

    private val repo = ProjectRepository.get(app)
    private val importer = MediaImporter(app)
    private val projectId: String = checkNotNull(saved["projectId"])

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()
    private var analysisJob: Job? = null

    private val _focus = MutableSharedFlow<PreviewFocus>(extraBufferCapacity = 4)
    val focus: SharedFlow<PreviewFocus> = _focus.asSharedFlow()
    private var focusJob: Job? = null

    /**
     * Vorschau nach einer Bearbeitung auf die betroffene Stelle setzen – erst, wenn ~0,7 s
     * nichts mehr geändert wurde (Tippen, Schieberegler), damit die Wiedergabe nicht flackert.
     */
    private fun focusAfterEdit(f: PreviewFocus) {
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            kotlinx.coroutines.delay(700)
            _focus.emit(f)
        }
    }

    /** Sofort springen (Clip antippen, „Stelle erneut abspielen“). */
    private fun focusNow(f: PreviewFocus) {
        focusJob?.cancel()
        _focus.tryEmit(f)
    }

    init {
        viewModelScope.launch {
            val p = repo.load(projectId) ?: repo.newProject("Projekt").copy(id = projectId)
            _state.update {
                it.copy(
                    project = p,
                    timeline = Timeline.resolve(p),
                    musicTitle = p.music?.let { m -> importer.audioTitle(File(m.uri)) ?: File(m.uri).name },
                    musicDurationMs = p.music?.let { m -> importer.audioDurationMs(File(m.uri)) } ?: 0,
                )
            }
            // Musik aus älteren Versionen: Wellenform nachträglich berechnen
            p.music?.takeIf { it.waveform.isEmpty() }?.let { m ->
                launch {
                    runCatching { AudioDecoder.decodeMono(File(m.uri)) }.getOrNull()?.let { pcm ->
                        val wave = Waveform.peaks(pcm.samples)
                        change(recut = false) { it.copy(music = it.music?.copy(waveform = wave)) }
                    }
                }
            }
            // Bilder aus älteren Versionen ohne Gesichtsanalyse nachträglich analysieren
            analyzeMissing()
        }
    }

    private suspend fun analyzeMissing() {
        val missing = project?.let { p -> p.images.filter { it !in p.imageInfo } }.orEmpty()
        if (missing.isEmpty()) return
        _state.update { it.copy(analyzingFaces = it.analyzingFaces + missing.size) }
        for (path in missing) {
            val info = runCatching { FaceAnalyzer.analyze(File(path)) }.getOrNull()
            if (info != null) change { it.copy(imageInfo = it.imageInfo + (path to info)) }
            _state.update { it.copy(analyzingFaces = it.analyzingFaces - 1) }
        }
    }

    /** Darstellungsmodus eines Bildes durchschalten: Auto → Füllen → Blur → Schwenk → Auto. */
    fun cycleFit(path: String) {
        val info = project?.imageInfo?.get(path) ?: return
        val next = when (info.fit) {
            FitMode.AUTO -> FitMode.FILL
            FitMode.FILL -> FitMode.BLUR
            FitMode.BLUR -> FitMode.PAN
            FitMode.PAN -> FitMode.AUTO
        }
        setFit(path, next)
    }

    fun setFit(path: String, mode: FitMode) {
        change { p ->
            val info = p.imageInfo[path] ?: return@change p
            p.copy(imageInfo = p.imageInfo + (path to info.copy(fit = mode)))
        }
        // Vorschau: ersten Clip mit diesem Bild zeigen
        _state.value.timeline.firstOrNull { it.clip.imageUri == path }?.let { focusClip(it) }
    }

    // ------------------------------------------------------------ Clip-Bearbeitung

    /** Übergang in Clip [index] setzen; null = wieder automatisch. */
    fun setTransition(index: Int, transition: Transition?) {
        if (index <= 0) return
        change { p ->
            val o = if (transition == null) p.transitionOverrides - index else p.transitionOverrides + (index to transition)
            p.copy(transitionOverrides = o)
        }
        cutFocus(index)?.let(::focusAfterEdit)
    }

    fun isTransitionManual(index: Int) = project?.transitionOverrides?.containsKey(index) == true

    /** Dauer eines Beats in ms (500 ms ohne Musik). */
    fun beatMs(): Float = project?.music?.beats?.bpm?.takeIf { it > 0f }?.let { 60000f / it } ?: 500f

    /** Vorschau auf die Schnittstelle vor Clip [index] setzen (Schleife ±1,2 s). */
    fun focusCut(index: Int) { cutFocus(index)?.let(::focusNow) }

    private fun cutFocus(index: Int): PreviewFocus? {
        val t = _state.value.timeline.getOrNull(index) ?: return null
        return PreviewFocus((t.startMs - 1200).coerceAtLeast(0), t.startMs + 1200)
    }

    private fun focusClip(t: TimedClip) {
        focusAfterEdit(PreviewFocus((t.startMs - 300).coerceAtLeast(0), t.endMs + 300))
    }

    /** Tatsächlich verwendeter Modus (AUTO aufgelöst) für die Anzeige. */
    fun resolvedFit(path: String): FitMode? {
        val p = project ?: return null
        val info = p.imageInfo[path] ?: return null
        return Framing.frame(info, p.format, 0, 0.1f, kotlin.random.Random(0)).fit
    }

    private val project get() = _state.value.project

    /**
     * Ändert das Projekt. Bei [recut] wird der Auto-Schnitt sofort neu berechnet,
     * damit Timeline und Vorschau immer den aktuellen Stand zeigen.
     */
    private fun change(recut: Boolean = true, f: (Project) -> Project) {
        val p = project ?: return
        var np = f(p).copy(updatedAt = System.currentTimeMillis())
        if (recut) np = if (np.images.isEmpty()) np.copy(clips = emptyList()) else AutoCut.apply(np)
        _state.update { it.copy(project = np, timeline = Timeline.resolve(np)) }
        viewModelScope.launch { repo.save(np) }
    }

    // ------------------------------------------------------------ Bilder

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val dir = repo.mediaDir(projectId)
        _state.update { it.copy(importingImages = it.importingImages + uris.size) }
        viewModelScope.launch {
            val files = mutableListOf<String>()
            var failed = 0
            for (u in uris) {
                runCatching { importer.importImage(u, dir) }
                    .onSuccess { files += it.absolutePath }
                    .onFailure { failed++ }
                _state.update { it.copy(importingImages = it.importingImages - 1) }
            }
            change { it.copy(images = it.images + files) }
            if (failed > 0) _state.update { it.copy(error = "$failed Bild(er) konnten nicht geladen werden") }
            analyzeMissing()
        }
    }

    fun moveImage(from: Int, to: Int) = change { p ->
        if (to !in p.images.indices) return@change p
        p.copy(images = p.images.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun removeImage(index: Int) = change { p ->
        val path = p.images[index]
        File(path).delete()
        p.copy(images = p.images.toMutableList().apply { removeAt(index) }, imageInfo = p.imageInfo - path)
    }

    fun shuffleImages() = change { it.copy(images = it.images.shuffled()) }

    // ------------------------------------------------------------ Musik

    fun setMusic(uri: Uri) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _state.update { it.copy(analysisProgress = 0f, error = null) }
            try {
                val file = importer.importAudio(uri, repo.mediaDir(projectId))
                val duration = importer.audioDurationMs(file)
                _state.update {
                    it.copy(musicTitle = importer.audioTitle(file) ?: importer.displayName(uri) ?: file.name, musicDurationMs = duration)
                }
                val pcm = AudioDecoder.decodeMono(file) { p -> _state.update { it.copy(analysisProgress = p * 0.5f) } }
                val grid = BeatDetector().analyze(pcm.samples, pcm.sampleRate) { p ->
                    _state.update { it.copy(analysisProgress = 0.5f + p * 0.5f) }
                }
                val wave = Waveform.peaks(pcm.samples)
                change { it.copy(music = MusicTrack(uri = file.absolutePath, endMs = duration.takeIf { d -> d > 0 }, beats = grid, waveform = wave)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Musik konnte nicht analysiert werden: ${e.message}") }
            } finally {
                _state.update { it.copy(analysisProgress = null) }
            }
        }
    }

    fun setMusicRange(startMs: Long, endMs: Long) = change { p ->
        p.copy(music = p.music?.copy(startMs = startMs, endMs = endMs))
    }

    /** Start des Ausschnitts um einen Beat verschieben (dir = -1 / +1). Startet immer auf einem Beat. */
    fun nudgeStart(dir: Int) = change { p ->
        val m = p.music ?: return@change p
        val beats = m.beats?.beatsMs ?: return@change p
        val cur = beats.indexOfFirst { it >= m.startMs }.let { if (it < 0) beats.lastIndex else it }
        val idx = (cur + dir).coerceIn(0, beats.lastIndex)
        val end = m.endMs ?: Long.MAX_VALUE
        if (beats[idx] >= end) p else p.copy(music = m.copy(startMs = beats[idx]))
    }

    /** Ende des Ausschnitts um einen Beat verschieben. */
    fun nudgeEnd(dir: Int) = change { p ->
        val m = p.music ?: return@change p
        val beats = m.beats?.beatsMs ?: return@change p
        val end = m.endMs ?: (beats.lastOrNull() ?: return@change p)
        val cur = beats.indexOfLast { it <= end }.coerceAtLeast(0)
        val idx = (cur + dir).coerceIn(0, beats.lastIndex)
        if (beats[idx] <= m.startMs) p else p.copy(music = m.copy(endMs = beats[idx]))
    }

    fun setFades(fadeInMs: Long, fadeOutMs: Long) = change(recut = false) { p ->
        p.copy(music = p.music?.copy(fadeInMs = fadeInMs, fadeOutMs = fadeOutMs))
    }

    fun setVolume(v: Float) = change(recut = false) { p -> p.copy(music = p.music?.copy(volume = v)) }

    // ------------------------------------------------------------ Format & Stil

    fun setName(name: String) = change(recut = false) { it.copy(name = name) }
    fun setFormat(f: AspectFormat) = change { it.copy(format = f) }
    /** Stil wählen; der zum Stil passende Look wird mit übernommen (danach frei änderbar). */
    fun setStyle(id: String) = change {
        it.copy(styleId = id, beatsPerCut = null, color = it.color.copy(presetId = Styles.byId(id).colorPresetId))
    }

    // ------------------------------------------------------------ Farbe

    fun updateColor(f: (ColorGrade) -> ColorGrade) = change(recut = false) { it.copy(color = f(it.color)) }

    fun resetColor() = updateColor { ColorGrade(lutPath = it.lutPath, lutName = it.lutName, lutIntensity = it.lutIntensity) }

    /** .cube-Datei ins Projekt kopieren und prüfen. */
    fun importLut(uri: Uri) {
        viewModelScope.launch {
            try {
                val name = importer.displayName(uri) ?: "LUT"
                val file = withContext(Dispatchers.IO) {
                    val target = File(repo.mediaDir(projectId), "lut_" + System.currentTimeMillis() + ".cube")
                    getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Datei kann nicht geöffnet werden" }
                        target.outputStream().use { input.copyTo(it) }
                    }
                    target
                }
                val lut = withContext(Dispatchers.Default) { CubeLut.parse(file.readText()) }
                val old = project?.color?.lutPath
                updateColor { it.copy(lutPath = file.absolutePath, lutName = lut.title?.takeIf { t -> t.isNotBlank() } ?: name.removeSuffix(".cube"), lutIntensity = 1f) }
                old?.let { File(it).delete() }
            } catch (e: Exception) {
                _state.update { it.copy(error = "LUT ungültig: ${e.message}") }
            }
        }
    }

    fun removeLut() {
        val old = project?.color?.lutPath
        updateColor { it.copy(lutPath = null, lutName = null) }
        old?.let { File(it).delete() }
    }

    // ------------------------------------------------------------ Text & Branding

    private val duration get() = _state.value.timeline.lastOrNull()?.endMs ?: 0L

    fun setIntro(card: TitleCard?) {
        change(recut = false) { it.copy(intro = card) }
        card?.let { focusAfterEdit(PreviewFocus(0, it.durationMs.coerceAtMost(duration))) }
    }

    fun setOutro(card: TitleCard?) {
        change(recut = false) { it.copy(outro = card) }
        card?.let { focusAfterEdit(PreviewFocus((duration - it.durationMs - 300).coerceAtLeast(0), duration)) }
    }

    fun setCaption(path: String, text: String) = change(recut = false) { p ->
        val info = p.imageInfo[path] ?: return@change p
        p.copy(imageInfo = p.imageInfo + (path to info.copy(caption = text.ifBlank { null })))
    }

    fun setCaptionStyle(style: TextStyle) {
        change(recut = false) { it.copy(captionStyle = style) }
        // ersten Clip mit Untertitel zeigen
        val p = project ?: return
        _state.value.timeline.firstOrNull { p.imageInfo[it.clip.imageUri]?.caption != null }?.let { focusClip(it) }
    }

    /** Neuen Text an Position [atMs] anlegen; liefert seine ID. */
    fun addText(atMs: Long): String {
        val id = java.util.UUID.randomUUID().toString()
        val start = atMs.coerceIn(0, (duration - 500).coerceAtLeast(0))
        val end = (start + 2500).coerceAtMost(duration.coerceAtLeast(start + 500))
        change(recut = false) { it.copy(texts = it.texts + TextOverlay(id, "Dein Text", start, end)) }
        focusAfterEdit(PreviewFocus(start, end))
        return id
    }

    fun updateText(id: String, f: (TextOverlay) -> TextOverlay) {
        var updated: TextOverlay? = null
        change(recut = false) { p -> p.copy(texts = p.texts.map { if (it.id == id) f(it).also { n -> updated = n } else it }) }
        updated?.let { focusAfterEdit(PreviewFocus(it.startMs, it.endMs)) }
    }

    fun removeText(id: String) = change(recut = false) { p -> p.copy(texts = p.texts.filterNot { it.id == id }) }

    fun setWatermark(w: Watermark?) = change(recut = false) { it.copy(watermark = w) }

    /** Logo fürs Wasserzeichen importieren (Transparenz bleibt als PNG erhalten). */
    fun importWatermarkLogo(uri: Uri) {
        viewModelScope.launch {
            try {
                val file = importer.importLogo(uri, repo.mediaDir(projectId))
                val old = project?.watermark?.imageUri
                change(recut = false) { it.copy(watermark = (it.watermark ?: Watermark()).copy(imageUri = file.absolutePath)) }
                old?.let { File(it).delete() }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Logo konnte nicht geladen werden: ${e.message}") }
            }
        }
    }

    fun removeWatermarkLogo() {
        val old = project?.watermark?.imageUri
        change(recut = false) { it.copy(watermark = it.watermark?.copy(imageUri = null)) }
        old?.let { File(it).delete() }
    }

    /** Farbkorrektur nur für ein Bild (null = keine). */
    fun setImageColor(path: String, color: ColorGrade?) {
        change { p ->
            val info = p.imageInfo[path] ?: return@change p
            p.copy(imageInfo = p.imageInfo + (path to info.copy(color = color?.takeUnless { it.isNeutral })))
        }
    }
    fun setBeatsPerCut(n: Int) = change { it.copy(beatsPerCut = n) }
    fun setLengthMode(m: LengthMode) = change { it.copy(lengthMode = m) }
    fun setExportSettings(e: de.synvyropic.model.ExportSettings) = change(recut = false) { it.copy(export = e) }

    // ------------------------------------------------------------ Auto-Schnitt

    /** Neu würfeln: andere zufällige Übergänge/Kamerafahrten (von Hand gewählte bleiben). */
    fun reshuffle() = change { it.copy(seed = it.seed + 1) }

    /** Alle von Hand gewählten Übergänge zurücksetzen. */
    fun resetManualTransitions() = change { it.copy(transitionOverrides = emptyMap()) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun styles() = Styles.all
}
