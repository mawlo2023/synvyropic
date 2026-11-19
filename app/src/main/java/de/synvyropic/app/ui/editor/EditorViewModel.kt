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
        focusCut(index)
    }

    fun isTransitionManual(index: Int) = project?.transitionOverrides?.containsKey(index) == true

    /** Dauer eines Beats in ms (500 ms ohne Musik). */
    fun beatMs(): Float = project?.music?.beats?.bpm?.takeIf { it > 0f }?.let { 60000f / it } ?: 500f

    /** Vorschau auf die Schnittstelle vor Clip [index] setzen (Schleife ±1,2 s). */
    fun focusCut(index: Int) {
        val t = _state.value.timeline.getOrNull(index) ?: return
        _focus.tryEmit(PreviewFocus((t.startMs - 1200).coerceAtLeast(0), t.startMs + 1200))
    }

    private fun focusClip(t: TimedClip) {
        _focus.tryEmit(PreviewFocus((t.startMs - 300).coerceAtLeast(0), t.endMs + 300))
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
                change { it.copy(music = MusicTrack(uri = file.absolutePath, endMs = duration.takeIf { d -> d > 0 }, beats = grid)) }
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

    fun setFades(fadeInMs: Long, fadeOutMs: Long) = change(recut = false) { p ->
        p.copy(music = p.music?.copy(fadeInMs = fadeInMs, fadeOutMs = fadeOutMs))
    }

    fun setVolume(v: Float) = change(recut = false) { p -> p.copy(music = p.music?.copy(volume = v)) }

    // ------------------------------------------------------------ Format & Stil

    fun setName(name: String) = change(recut = false) { it.copy(name = name) }
    fun setFormat(f: AspectFormat) = change { it.copy(format = f) }
    fun setStyle(id: String) = change { it.copy(styleId = id, beatsPerCut = null) }
    fun setBeatsPerCut(n: Int) = change { it.copy(beatsPerCut = n) }
    fun setLengthMode(m: LengthMode) = change { it.copy(lengthMode = m) }

    // ------------------------------------------------------------ Auto-Schnitt

    /** Neu würfeln: andere zufällige Übergänge/Kamerafahrten (von Hand gewählte bleiben). */
    fun reshuffle() = change { it.copy(seed = it.seed + 1) }

    /** Alle von Hand gewählten Übergänge zurücksetzen. */
    fun resetManualTransitions() = change { it.copy(transitionOverrides = emptyMap()) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun styles() = Styles.all
}
