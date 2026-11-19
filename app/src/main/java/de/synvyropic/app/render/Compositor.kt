package de.synvyropic.app.render

import android.opengl.GLES30
import de.synvyropic.model.ClipEffectType
import de.synvyropic.model.ClipState
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
    }

    fun setProject(project: Project) {
        val r = FrameResolver(project)
        resolver = r
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
        var spanX: Float
        var spanY: Float
        if (imageAspect > outAspect) { spanX = outAspect / imageAspect; spanY = 1f } else { spanX = 1f; spanY = imageAspect / outAspect }
        val zoom = (state.scale * extraZoom).coerceAtLeast(1f)
        spanX /= zoom
        spanY /= zoom
        val cx = if (spanX >= 1f) 0.5f else state.centerX.coerceIn(spanX / 2, 1f - spanX / 2)
        val cy = if (spanY >= 1f) 0.5f else state.centerY.coerceIn(spanY / 2, 1f - spanY / 2)
        clipProgram.set2f("uCenter", cx, cy)
        clipProgram.set2f("uSpan", spanX, spanY)
        quad.draw()
    }

    private fun bindTexture(unit: Int, id: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
    }

    fun release() {
        cache.release()
        black.release()
        fboA.release(); fboB.release(); fboC.release()
        clipProgram.release(); postProgram.release(); transitionProgram.release()
        quad.release()
    }
}
