package de.synvyropic.app.render

import android.opengl.GLES30
import de.synvyropic.model.ClipEffectType
import android.util.Half
import de.synvyropic.model.ClipState
import de.synvyropic.model.ColorGrade
import de.synvyropic.model.ColorLooks
import de.synvyropic.model.ColorParams
import de.synvyropic.model.CubeLut
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import de.synvyropic.model.FitMode
import de.synvyropic.model.FrameResolver
import de.synvyropic.model.Project
import kotlin.math.min

/**
 * Zeichnet einen Frame der Timeline. Muss auf einem Thread mit aktivem GL-ES-3-Kontext
 * erzeugt und benutzt werden. Wird von Vorschau und Export gleichermaßen genutzt.
 *
 * @param width/height interne Renderauflösung (Export: Zielauflösung)
 */
class Compositor(val width: Int, val height: Int) {

    private val quad = FullscreenQuad()
    private val clipProgram = GlProgram(Shaders.VERTEX, Shaders.CLIP, "clip")
    private val postProgram = GlProgram(Shaders.VERTEX, Shaders.POST, "post")
    private val transitionProgram: GlProgram
    private val fallbackTransition: Boolean

    /** Gesetzt, wenn der Übergangs-Shader nicht kompiliert (dann nur Überblendungen). */
    var shaderWarning: String? = null
        private set

    private val fboA = Fbo(width, height)
    private val fboB = Fbo(width, height)
    private val fboC = Fbo(width, height)
    private val cache = TextureCache(maxSide = maxOf(width, height))
    private val black = ImageTexture.solid(0xFF000000.toInt())

    private var resolver: FrameResolver? = null

    // Farbe: Projekt-Look + 3D-LUT
    private var grade = ColorGrade()
    private var projectParams = ColorParams()
    private val lutTexture: Int = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
    private var lutSize = 2f
    private var loadedLutPath: String? = null
    private var lutLoaded = false

    /** Gesetzt, wenn die LUT-Datei nicht gelesen werden konnte. */
    var lutError: String? = null
        private set

    init {
        var prog: GlProgram
        var fallback = false
        try {
            prog = GlProgram(Shaders.VERTEX, Shaders.TRANSITION, "transition")
        } catch (e: ShaderException) {
            shaderWarning = e.message
            prog = GlProgram(Shaders.VERTEX, Shaders.TRANSITION_FALLBACK, "transition-fallback")
            fallback = true
        }
        transitionProgram = prog
        fallbackTransition = fallback
        uploadLut(CubeLut.identity(2))
    }

    fun setProject(project: Project) {
        val r = FrameResolver(project)
        resolver = r
        grade = project.color
        projectParams = ColorLooks.resolve(grade)
        if (grade.lutPath != loadedLutPath) loadLut(grade.lutPath)
        r.neededAround(0).forEach { cache.prefetch(r.timed[it].clip.imageUri) }
    }

    val durationMs: Long get() = resolver?.durationMs ?: 0L

    /**
     * Rendert Zeitpunkt [tMs] in den aktuell gebundenen Default-Framebuffer (Viewport [vw]×[vh]).
     * @param blocking true = auf Texturen warten (Export), false = fehlende Bilder schwarz (Vorschau)
     * @return true, wenn alle benötigten Bilder geladen waren
     */
    fun render(tMs: Long, vw: Int, vh: Int, blocking: Boolean): Boolean {
        val r = resolver
        val spec = r?.at(tMs)
        if (r == null || spec == null) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, vw, vh)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return true
        }

        // Vorladen der nächsten Bilder
        val needed = r.neededAround(tMs).map { r.timed[it].clip.imageUri }
        needed.forEach(cache::prefetch)
        cache.evict(needed.toSet())

        var complete = true
        fun tex(path: String): ImageTexture {
            val t = if (blocking) cache.getBlocking(path) else cache.getIfReady(path)
            if (t == null) complete = false
            return t ?: black
        }

        val env = spec.beatEnvelope
        val effect = spec.effect
        val pulseZoom = if (effect?.type == ClipEffectType.BEAT_PULSE) 1f + 0.05f * env * effect.intensity * 2f else 1f

        drawClip(tex(spec.a.imageUri), spec.a, fboA, pulseZoom)
        var source = fboA
        val b = spec.b
        val tr = spec.transition
        if (b != null && tr != null) {
            drawClip(tex(b.imageUri), b, fboB, pulseZoom)
            fboC.bind()
            transitionProgram.use()
            bindTexture(0, fboA.texture)
            bindTexture(1, fboB.texture)
            transitionProgram.set1i("uA", 0)
            transitionProgram.set1i("uB", 1)
            transitionProgram.set1f("uP", spec.progress)
            if (!fallbackTransition) {
                transitionProgram.set1i("uType", tr.type.ordinal)
                val intensity = if (tr.beatReactive) min(1f, tr.intensity * (0.75f + 0.5f * env)) else tr.intensity
                transitionProgram.set1f("uIntensity", intensity)
                transitionProgram.set1f("uTime", tMs / 1000f)
                transitionProgram.set2f("uRes", width.toFloat(), height.toFloat())
                val c = tr.colorArgb
                transitionProgram.set3f(
                    "uColor",
                    ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f,
                )
                transitionProgram.set1f("uAngle", Math.toRadians(tr.angleDeg.toDouble()).toFloat())
                transitionProgram.set1f("uEnv", env)
            }
            quad.draw()
            source = fboC
        }

        // Post-Pass → Bildschirm/Encoder
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, vw, vh)
        postProgram.use()
        bindTexture(0, source.texture)
        postProgram.set1i("uTex", 0)
        postProgram.set2f("uRes", width.toFloat(), height.toFloat())
        postProgram.set1f("uTime", tMs / 1000f)
        val intensity = effect?.intensity ?: 0f
        postProgram.set1f("uRgbPulse", if (effect?.type == ClipEffectType.RGB_SHIFT_PULSE) min(1f, env * intensity * 2f) else 0f)
        postProgram.set1f("uBright", if (effect?.type == ClipEffectType.BEAT_PULSE) env * intensity else 0f)
        postProgram.set1f("uVhs", if (effect?.type == ClipEffectType.VHS_OVERLAY) intensity else 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        postProgram.set1i("uLut", 1)
        postProgram.set1f("uLutSize", lutSize)
        postProgram.set1f("uLutAmount", if (lutLoaded) grade.lutIntensity.coerceIn(0f, 1f) else 0f)
        postProgram.set1f("uVignette", projectParams.vignette)
        quad.draw()
        return complete
    }

    /** Bild „cover“ in das Format einpassen, Ken-Burns-Zoom/Schwenk anwenden. */
    private fun drawClip(tex: ImageTexture, state: ClipState, target: Fbo, extraZoom: Float) {
        target.bind()
        clipProgram.use()
        bindTexture(0, tex.id)
        clipProgram.set1i("uTex", 0)
        val imageAspect = tex.width.toFloat() / tex.height
        val outAspect = width.toFloat() / height
        // „Cover“: Bild füllt das Format (Span ≤ 1 je Achse)
        val coverX: Float
        val coverY: Float
        if (imageAspect > outAspect) { coverX = outAspect / imageAspect; coverY = 1f } else { coverX = 1f; coverY = imageAspect / outAspect }
        val zoom = (state.scale * extraZoom).coerceAtLeast(1f)
        val c = if (state.color == null) projectParams else ColorLooks.resolve(grade, state.color)
        clipProgram.set4f("uG1", c.brightness, c.contrast, c.saturation, c.temperature)
        clipProgram.set3f("uG2", c.tint, c.fade, if (c.isIdentity) 0f else 1f)
        clipProgram.set3f("uShadow", c.shadowTint.r, c.shadowTint.g, c.shadowTint.b)
        clipProgram.set3f("uHigh", c.highlightTint.r, c.highlightTint.g, c.highlightTint.b)
        if (state.fit == FitMode.BLUR) {
            // „Contain“: ganzes Bild sichtbar (Span ≥ 1), Rand = unscharfer Hintergrund
            val containX = coverX / minOf(coverX, coverY)
            val containY = coverY / minOf(coverX, coverY)
            clipProgram.set1i("uMode", 1)
            clipProgram.set2f("uCenter", 0.5f, 0.5f)
            clipProgram.set2f("uSpan", containX / zoom, containY / zoom)
            clipProgram.set2f("uBgSpan", coverX * 0.9f, coverY * 0.9f)
        } else {
            val spanX = coverX / zoom
            val spanY = coverY / zoom
            val cx = if (spanX >= 1f) 0.5f else state.centerX.coerceIn(spanX / 2, 1f - spanX / 2)
            val cy = if (spanY >= 1f) 0.5f else state.centerY.coerceIn(spanY / 2, 1f - spanY / 2)
            clipProgram.set1i("uMode", 0)
            clipProgram.set2f("uCenter", cx, cy)
            clipProgram.set2f("uSpan", spanX, spanY)
        }
        quad.draw()
    }

    private fun loadLut(path: String?) {
        loadedLutPath = path
        lutLoaded = false
        lutError = null
        if (path == null) return
        try {
            uploadLut(CubeLut.parse(File(path).readText()))
            lutLoaded = true
        } catch (e: Exception) {
            lutError = "LUT konnte nicht geladen werden: ${e.message}"
            uploadLut(CubeLut.identity(2))
        }
    }

    /** 3D-LUT als RGB16F-Textur hochladen. */
    private fun uploadLut(lut: CubeLut) {
        val buf = ByteBuffer.allocateDirect(lut.data.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (v in lut.data) buf.put(Half.toHalf(v))
        buf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, lut.size, lut.size, lut.size, 0,
            GLES30.GL_RGB, GLES30.GL_HALF_FLOAT, buf,
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        lutSize = lut.size.toFloat()
    }

    private fun bindTexture(unit: Int, id: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
    }

    fun release() {
        cache.release()
        black.release()
        fboA.release(); fboB.release(); fboC.release()
        GLES30.glDeleteTextures(1, intArrayOf(lutTexture), 0)
        clipProgram.release(); postProgram.release(); transitionProgram.release()
        quad.release()
    }
}
