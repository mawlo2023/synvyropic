package de.synvyropic.app.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import de.synvyropic.app.data.ProjectRepository
import de.synvyropic.app.media.AudioDecoder
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class EditorState(
    val project: Project? = null,
    val importingImages: Int = 0,
    val musicTitle: String? = null,
    val musicDurationMs: Long = 0,
    /** null = keine Analyse aktiv, sonst Fortschritt 0..1 */
    val analysisProgress: Float? = null,
    val timeline: List<TimedClip> = emptyList(),
    /** Timeline passt nicht mehr zu den Einstellungen → neu erstellen. */
    val timelineDirty: Boolean = true,
    val error: String? = null,
)

class EditorViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {

    private val repo = ProjectRepository.get(app)
    private val importer = MediaImporter(app)
    private val projectId: String = checkNotNull(saved["projectId"])

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()
    private var analysisJob: Job? = null

    init {
        viewModelScope.launch {
            val p = repo.load(projectId) ?: repo.newProject("Projekt").copy(id = projectId)
            _state.update {
                it.copy(
                    project = p,
                    timeline = Timeline.resolve(p),
                    timelineDirty = p.clips.isEmpty(),
                    musicTitle = p.music?.let { m -> importer.audioTitle(File(m.uri)) ?: File(m.uri).name },
                    musicDurationMs = p.music?.let { m -> importer.audioDurationMs(File(m.uri)) } ?: 0,
                )
            }
        }
    }

    private val project get() = _state.value.project

    private fun change(dirty: Boolean = true, f: (Project) -> Project) {
        val p = project ?: return
        val np = f(p).copy(updatedAt = System.currentTimeMillis())
        _state.update { it.copy(project = np, timelineDirty = it.timelineDirty || dirty) }
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
        }
    }

    fun moveImage(from: Int, to: Int) = change { p ->
        if (to !in p.images.indices) return@change p
        p.copy(images = p.images.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun removeImage(index: Int) = change { p ->
        File(p.images[index]).delete()
        p.copy(images = p.images.toMutableList().apply { removeAt(index) })
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

    fun setFades(fadeInMs: Long, fadeOutMs: Long) = change(dirty = false) { p ->
        p.copy(music = p.music?.copy(fadeInMs = fadeInMs, fadeOutMs = fadeOutMs))
    }

    fun setVolume(v: Float) = change(dirty = false) { p -> p.copy(music = p.music?.copy(volume = v)) }

    // ------------------------------------------------------------ Format & Stil

    fun setName(name: String) = change(dirty = false) { it.copy(name = name) }
    fun setFormat(f: AspectFormat) = change(dirty = false) { it.copy(format = f) }
    fun setStyle(id: String) = change { it.copy(styleId = id, beatsPerCut = null) }
    fun setBeatsPerCut(n: Int) = change { it.copy(beatsPerCut = n) }
    fun setLengthMode(m: LengthMode) = change { it.copy(lengthMode = m) }

    // ------------------------------------------------------------ Auto-Schnitt

    fun createCut(reshuffle: Boolean = false) {
        val p = project ?: return
        if (p.images.isEmpty()) {
            _state.update { it.copy(error = "Bitte zuerst Bilder hinzufügen") }
            return
        }
        val seeded = if (reshuffle) p.copy(seed = p.seed + 1) else p
        val cut = AutoCut.apply(seeded).copy(updatedAt = System.currentTimeMillis())
        _state.update { it.copy(project = cut, timeline = Timeline.resolve(cut), timelineDirty = false) }
        viewModelScope.launch { repo.save(cut) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun styles() = Styles.all
}
