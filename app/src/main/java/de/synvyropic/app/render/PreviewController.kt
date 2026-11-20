package de.synvyropic.app.render

import android.content.Context
import android.media.MediaPlayer
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.synvyropic.model.AspectFormat
import de.synvyropic.model.MusicTrack
import de.synvyropic.model.Project
import de.synvyropic.model.Timeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Echtzeit-Vorschau: GLSurfaceView + Musik. Die Uhr läuft über SystemClock;
 * die Musik wird beim Start/Seek auf dieselbe Position gesetzt.
 * Öffentliche Methoden nur vom Main-Thread aufrufen.
 */
class PreviewController(context: Context) {

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Uhr (vom GL-Thread gelesen)
    @Volatile private var basePos = 0L
    @Volatile private var baseTime = 0L
    @Volatile private var isPlaying = false

    private var project: Project? = null
    private var player: MediaPlayer? = null
    private var playerPath: String? = null
    private val main = Handler(Looper.getMainLooper())

    private val renderer = Renderer()

    val view: GLSurfaceView = GLSurfaceView(context).apply {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    // Audio als Uhr: Zeitstempel des Musik-Players (was gerade aus dem Lautsprecher kommt)
    @Volatile private var clockMusicStart = -1L   // -1 = keine Musik-Uhr
    @Volatile private var anchorMediaUs = 0L
    @Volatile private var anchorNanos = 0L       // 0 = noch kein gültiger Zeitstempel
    @Volatile private var clockRate = 1f

    private val _beat = MutableStateFlow(0)
    /** 0 = kein Beat, 1 = Beat, 2 = Takt-Eins (leuchtet ~120 ms). */
    val beat: StateFlow<Int> = _beat.asStateFlow()

    fun currentTime(): Long {
        if (!isPlaying) return basePos
        val ms = clockMusicStart
        if (ms >= 0) {
            val ns = anchorNanos
            // Musik läuft noch nicht hörbar → Bild wartet, statt vorzulaufen
            if (ns == 0L) return basePos
            val media = anchorMediaUs + ((System.nanoTime() - ns) / 1000 * clockRate).toLong()
            return (media / 1000 - ms).coerceIn(0, _duration.value)
        }
        return basePos + (SystemClock.elapsedRealtime() - baseTime)
    }

    /** Zeitstempel des Players übernehmen (nur plausible, frische Werte). */
    private fun syncAudioClock() {
        val mp = player ?: return
        if (clockMusicStart < 0) return
        val ts = runCatching { mp.timestamp }.getOrNull() ?: return
        if (ts.mediaClockRate <= 0f) return
        val expected = (clockMusicStart + basePos) * 1000
        // Nach Start/Spulen erst einen Zeitstempel nahe der Zielposition akzeptieren
        if (anchorNanos == 0L && kotlin.math.abs(ts.anchorMediaTimeUs - expected) > 600_000) return
        anchorMediaUs = ts.anchorMediaTimeUs
        anchorNanos = ts.anchorSystemNanoTime
        clockRate = ts.mediaClockRate
    }

    /** Zur Systemuhr wechseln (keine Musik oder Musik zu Ende). */
    private fun useSystemClock() {
        val t = currentTime()
        clockMusicStart = -1
        anchorNanos = 0
        basePos = t
        baseTime = SystemClock.elapsedRealtime()
    }

    fun setProject(p: Project) {
        val old = project
        project = p
        resolver = de.synvyropic.model.FrameResolver(p)
        stopClipAudio()
        _duration.value = Timeline.durationMs(p)
        renderer.pending.set(p)
        val music = p.music
        if (music?.uri != playerPath) {
            releasePlayer()
            if (music != null) {
                player = runCatching {
                    MediaPlayer().apply { setDataSource(music.uri); prepare() }
                }.onFailure { _error.value = "Musik kann nicht abgespielt werden" }.getOrNull()
                playerPath = music.uri
            }
        }
        if (old == null || basePos > _duration.value) seekTo(0) else view.requestRender()
    }

    // Abspielbereich (z. B. beim Bearbeiten eines Übergangs): wird einmal gespielt, dann Pause; -1 = keiner
    private var loopStart = -1L
    private var loopEnd = -1L

    fun toggle() = if (isPlaying) pause() else play()

    /**
     * Bereich einmal abspielen und am Ende anhalten. Läuft genau dieser Bereich bereits,
     * passiert nichts – so springt die Vorschau bei schnellen Änderungen nicht ständig zurück.
     */
    fun playLoop(startMs: Long, endMs: Long) {
        val end = endMs.coerceAtMost(_duration.value)
        if (end <= startMs) return
        if (isPlaying && startMs == loopStart && end == loopEnd) return
        loopStart = startMs
        loopEnd = end
        seekInternal(startMs)
        if (!isPlaying) startPlayback()
    }

    fun play() {
        loopStart = -1; loopEnd = -1
        startPlayback()
    }

    private fun startPlayback() {
        val p = project ?: return
        if (p.clips.isEmpty()) return
        if (basePos >= _duration.value - 50) basePos = 0
        baseTime = SystemClock.elapsedRealtime()
        isPlaying = true
        _playing.value = true
        val music = p.music
        anchorNanos = 0
        clockMusicStart = -1
        if (music != null) player?.let { mp ->
            mp.seekTo((music.startMs + basePos).toLong(), MediaPlayer.SEEK_CLOSEST)
            applyVolume(basePos)
            mp.start()
            // Ab jetzt gibt die Musik den Takt vor
            clockMusicStart = music.startMs
        }
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        main.post(ticker)
    }

    fun pause() {
        loopStart = -1; loopEnd = -1
        if (!isPlaying) return
        basePos = currentTime().coerceAtMost(_duration.value)
        isPlaying = false
        clockMusicStart = -1
        anchorNanos = 0
        _beat.value = 0
        _playing.value = false
        player?.takeIf { it.isPlaying }?.pause()
        stopClipAudio()
        view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        main.removeCallbacks(ticker)
        _position.value = basePos
        view.requestRender()
    }

    fun seekTo(ms: Long) {
        loopStart = -1; loopEnd = -1
        seekInternal(ms)
    }

    private fun seekInternal(ms: Long) {
        val t = ms.coerceIn(0, _duration.value)
        basePos = t
        baseTime = SystemClock.elapsedRealtime()
        _position.value = t
        if (isPlaying) project?.music?.let { m ->
            player?.seekTo((m.startMs + t), MediaPlayer.SEEK_CLOSEST)
            anchorNanos = 0 // neuen Zeitstempel abwarten
        }
        stopClipAudio() // wird im nächsten Takt passend neu gestartet
        view.requestRender()
    }

    fun onHostPause() {
        pause()
        view.onPause()
    }

    fun onHostResume() = view.onResume()

    fun release() {
        pause()
        releasePlayer()
        stopClipAudio()
        view.queueEvent { renderer.release() }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        playerPath = null
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            syncAudioClock()
            // Musik zu Ende, Video läuft weiter → Systemuhr
            if (clockMusicStart >= 0 && anchorNanos != 0L && player?.isPlaying == false) useSystemClock()
            val t = currentTime()
            if (loopEnd > 0 && t >= loopEnd) {
                // Bereich fertig: am Ende stehen bleiben
                val end = loopEnd
                pause()
                seekInternal(end - 1)
                return
            }
            if (t >= _duration.value) {
                pause()
                basePos = _duration.value
                _position.value = basePos
                return
            }
            _position.value = t
            _beat.value = resolver?.sinceBeat(t)?.let { (since, down) -> if (since < 120) (if (down) 2 else 1) else 0 } ?: 0
            updateClipAudio(t)
            applyVolume(t)
            main.postDelayed(this, 20)
        }
    }

    private fun applyVolume(t: Long) {
        val m = project?.music ?: return
        // Ducking: Musik leiser, solange Originalton eines Videos läuft
        val duck = if (clipPlayer != null) 0.35f else 1f
        val g = gain(m, t, _duration.value) * duck
        player?.setVolume(g, g)
    }

    // ---------------- Originalton von Videoclips
    private var resolver: de.synvyropic.model.FrameResolver? = null
    private var clipPlayer: MediaPlayer? = null
    private var clipIndex = -1

    private fun updateClipAudio(t: Long) {
        val r = resolver ?: return
        val p = project ?: return
        val i = r.indexAt(t)
        if (i == clipIndex) return
        stopClipAudio()
        clipIndex = i
        val tc = r.timed.getOrNull(i) ?: return
        val info = p.imageInfo[tc.clip.imageUri] ?: return
        val v = info.video ?: return
        if (!v.hasAudio || !v.keepAudio || info.ramp != de.synvyropic.model.SpeedRamp.NONE) return
        val spec = r.at(t)
        val src = when {
            spec == null -> v.trimStartMs
            spec.a.clipIndex == i -> spec.a.videoMs ?: v.trimStartMs
            else -> spec.b?.videoMs ?: v.trimStartMs
        }
        clipPlayer = runCatching {
            MediaPlayer().apply {
                setDataSource(tc.clip.imageUri)
                prepare()
                seekTo(src.toInt())
                setVolume(v.audioVolume, v.audioVolume)
                start()
            }
        }.getOrNull()
    }

    private fun stopClipAudio() {
        clipPlayer?.release()
        clipPlayer = null
        clipIndex = -1
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        val pending = AtomicReference<Project?>(null)
        private var compositor: Compositor? = null
        private var format: AspectFormat? = null
        private var current: Project? = null
        private var vw = 1
        private var vh = 1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Neuer Kontext → alte GL-Objekte sind ungültig
            compositor = null
            format = null
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            vw = width
            vh = height
        }

        override fun onDrawFrame(gl: GL10?) {
            val newProject = pending.getAndSet(null)
            if (newProject != null) current = newProject
            val p = current ?: return
            try {
                if (compositor == null || format != p.format) {
                    compositor?.release()
                    // Vorschau in halber Exportauflösung
                    compositor = Compositor(p.format.width / 2, p.format.height / 2).also { c ->
                        c.shaderWarning?.let { w -> main.post { _error.value = "Effekt-Shader: $w" } }
                    }
                    format = p.format
                    compositor!!.setProject(p)
                } else if (newProject != null) {
                    compositor!!.setProject(p)
                }
                if (newProject != null) compositor!!.lutError?.let { w -> main.post { _error.value = w } }
                val complete = compositor!!.render(currentTime(), vw, vh, blocking = false)
                if (!complete && !isPlaying) view.postDelayed({ view.requestRender() }, 50)
            } catch (e: ShaderException) {
                compositor = null
                current = null
                main.post { _error.value = e.message }
            }
        }

        fun release() {
            compositor?.release()
            compositor = null
        }
    }

    companion object {
        /** Lautstärke inkl. Fade-In/-Out bei Video-Zeit t. */
        fun gain(m: MusicTrack, t: Long, duration: Long): Float {
            var g = m.volume
            if (m.fadeInMs > 0 && t < m.fadeInMs) g *= t.toFloat() / m.fadeInMs
            val rest = duration - t
            if (m.fadeOutMs > 0 && rest < m.fadeOutMs) g *= (rest.coerceAtLeast(0).toFloat() / m.fadeOutMs)
            return g.coerceIn(0f, 1f)
        }
    }
}
