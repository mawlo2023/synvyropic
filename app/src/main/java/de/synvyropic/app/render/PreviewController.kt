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

    fun currentTime(): Long =
        if (isPlaying) basePos + (SystemClock.elapsedRealtime() - baseTime) else basePos

    fun setProject(p: Project) {
        val old = project
        project = p
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

    // Schleifenbereich (z. B. beim Bearbeiten eines Übergangs); -1 = keine Schleife
    private var loopStart = -1L
    private var loopEnd = -1L

    fun toggle() = if (isPlaying) pause() else play()

    /** Bereich in Schleife abspielen, bis der Nutzer pausiert, spult oder Play drückt. */
    fun playLoop(startMs: Long, endMs: Long) {
        val end = endMs.coerceAtMost(_duration.value)
        if (end <= startMs) return
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
        if (music != null) player?.let { mp ->
            mp.seekTo((music.startMs + basePos).toInt())
            applyVolume(basePos)
            mp.start()
        }
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        main.post(ticker)
    }

    fun pause() {
        loopStart = -1; loopEnd = -1
        if (!isPlaying) return
        basePos = currentTime().coerceAtMost(_duration.value)
        isPlaying = false
        _playing.value = false
        player?.takeIf { it.isPlaying }?.pause()
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
        if (isPlaying) project?.music?.let { player?.seekTo((it.startMs + t).toInt()) }
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
            val t = currentTime()
            if (loopEnd > 0 && t >= loopEnd) {
                seekInternal(loopStart)
                main.postDelayed(this, 40)
                return
            }
            if (t >= _duration.value) {
                pause()
                basePos = _duration.value
                _position.value = basePos
                return
            }
            _position.value = t
            applyVolume(t)
            main.postDelayed(this, 40)
        }
    }

    private fun applyVolume(t: Long) {
        val m = project?.music ?: return
        val g = gain(m, t, _duration.value)
        player?.setVolume(g, g)
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
