package de.synvyropic.app.render

import android.opengl.GLES11Ext
import android.opengl.GLES30
import de.synvyropic.model.ClipEffectType
import android.util.Half
import de.synvyropic.model.CinemaLook
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
import de.synvyropic.model.OverlayResolver
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
    private val fboP = Fbo(width, height)
    private val fboAcc by lazy { Fbo(width, height) }
    private var accCreated = false
    private val copyProgram = GlProgram(Shaders.VERTEX, Shaders.COPY, "copy")
    private val subjectProgram = GlProgram(Shaders.VERTEX, Shaders.SUBJECT, "subject")
    private var overlayResolver: OverlayResolver? = null
    private var parallax = 0f
    private var projectMasks: Map<String, String> = emptyMap()
    private var projectVideos: Set<String> = emptySet()
    private val videos = HashMap<String, VideoFrameSource>()
    private val clipOesProgram = GlProgram(Shaders.VERTEX, Shaders.CLIP_OES, "clip-video")

    /** Lage der freigestellten Person im aktuellen Frame (für die Personen-Ebene). */
    private class Subject(val mask: String, val cx: Float, val cy: Float, val sx: Float, val sy: Float, val alpha: Float)
    private val subjects = ArrayList<Subject>(2)
    private var cinema = CinemaLook()
    private val cache = TextureCache(maxSide = maxOf(width, height))
    private val black = ImageTexture.solid(0xFF000000.toInt())

    private var resolver: FrameResolver? = null
    private val overlays = OverlayRenderer(width, height)

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
        val o = OverlayResolver(project, r.timed, r::envelope)
        overlayResolver = o
        overlays.setProject(project, o)
        parallax = project.parallax
        projectMasks = project.imageInfo.mapNotNull { (k, v) -> v.maskPath?.let { k to it } }.toMap()
        projectVideos = project.imageInfo.filterValues { it.video != null }.keys
        grade = project.color
        cinema = project.cinema
        projectParams = ColorLooks.resolve(grade)
        if (grade.lutPath != loadedLutPath) loadLut(grade.lutPath)
        r.neededAround(0).map { r.timed[it].clip.imageUri }.filter { it !in projectVideos }.forEach(cache::prefetch)
    }

    val durationMs: Long get() = resolver?.durationMs ?: 0L

    /**
     * Rendert Zeitpunkt [tMs] in den Default-Framebuffer (Viewport [vw]×[vh]).
     * @param blocking true = auf Texturen warten (Export), false = fehlende Bilder schwarz (Vorschau)
     * @param samples Teilbilder für Bewegungsunschärfe (1 = aus), verteilt über [shutterMs]
     * @return true, wenn alle benötigten Bilder geladen waren
     */
    fun render(tMs: Long, vw: Int, vh: Int, blocking: Boolean, samples: Int = 1, shutterMs: Float = 0f): Boolean {
        val r = resolver
        if (r == null || r.at(tMs) == null) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, vw, vh)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return true
        }
        var complete = true
        if (samples <= 1) {
            complete = renderScene(r, tMs, fboP, blocking)
            present(fboP, vw, vh)
        } else {
            // Bewegungsunschärfe: laufender Mittelwert mehrerer Teilbilder
            val acc = fboAcc
            if (!accCreated) accCreated = true
            acc.bind()
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            for (i in 0 until samples) {
                val t = tMs + (i * shutterMs / samples).toLong()
                complete = renderScene(r, t, fboP, blocking) && complete
                acc.bind()
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                copyProgram.use()
                bindTexture(0, fboP.texture)
                copyProgram.set1i("uTex", 0)
                copyProgram.set1f("uAlpha", 1f / (i + 1))
                quad.draw()
                GLES30.glDisable(GLES30.GL_BLEND)
            }
            present(acc, vw, vh)
        }
        // Texte, Intro/Outro, Wasserzeichen (unabhängig von Farbe und Unschärfe)
        if (overlayResolver?.hasBehind(tMs) == true && subjects.isNotEmpty()) {
            overlays.render(tMs, OverlayRenderer.Layer.BEHIND)
            drawSubjects(if (samples <= 1) fboP else fboAcc, blocking)
            overlays.render(tMs, OverlayRenderer.Layer.FRONT)
        } else {
            overlays.render(tMs)
        }
        return complete
    }

    /** Foto-Textur oder Video-Decoder (auf die Quellzeit gestellt) für einen Clip. */
    private fun source(st: ClipState, blocking: Boolean, tex: (String) -> ImageTexture): Pair<ImageTexture?, VideoFrameSource?> {
        val ms = st.videoMs ?: return tex(st.imageUri) to null
        val v = videos[st.imageUri] ?: runCatching { VideoFrameSource(st.imageUri) }.getOrNull()?.also { videos[st.imageUri] = it }
        if (v == null) return black to null
        runCatching { v.seekTo(ms, blocking) }
        return null to v
    }

    /** Person aus dem fertigen Bild über die „hinteren“ Texte zeichnen. */
    private fun drawSubjects(scene: Fbo, blocking: Boolean) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        subjectProgram.use()
        subjectProgram.set1i("uScene", 0)
        subjectProgram.set1i("uMask", 1)
        for (sub in subjects) {
            val mask = (if (blocking) cache.getBlocking(sub.mask) else cache.getIfReady(sub.mask)) ?: continue
            bindTexture(0, scene.texture)
            bindTexture(1, mask.id)
            subjectProgram.set2f("uCenter", sub.cx, sub.cy)
            subjectProgram.set2f("uSpan", sub.sx, sub.sy)
            subjectProgram.set1f("uAlpha", sub.alpha)
            quad.draw()
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /** Fertiges Bild auf den Bildschirm/Encoder kopieren. */
    private fun present(src: Fbo, vw: Int, vh: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, vw, vh)
        copyProgram.use()
        bindTexture(0, src.texture)
        copyProgram.set1i("uTex", 0)
        copyProgram.set1f("uAlpha", 1f)
        quad.draw()
    }

    /** Ein Bild der Timeline (ohne Texte) in [target] rendern. */
    private fun renderScene(r: FrameResolver, tMs: Long, target: Fbo, blocking: Boolean): Boolean {
        val spec = r.at(tMs) ?: return true
        // Vorladen der nächsten Bilder
        val aroundUris = r.neededAround(tMs).map { r.timed[it].clip.imageUri }
        val needed = aroundUris.filter { it !in projectVideos }.flatMap { listOfNotNull(it, projectMasks[it]) }
        // Video-Decoder nur für aktuelle/nächste Clips offen halten
        val keepVideos = aroundUris.filter { it in projectVideos }.toSet()
        videos.keys.filter { it !in keepVideos }.forEach { videos.remove(it)?.release() }
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

        subjects.clear()
        val pB = if (spec.b != null) spec.progress else 0f
        val (texA, vidA) = source(spec.a, blocking, ::tex)
        drawClip(texA, vidA, spec.a, fboA, pulseZoom, blocking, subjectAlpha = if (spec.b == null) 1f else (1f - 2f * pB).coerceIn(0f, 1f))
        var source = fboA
        val b = spec.b
        val tr = spec.transition
        if (b != null && tr != null) {
            val (texB, vidB) = source(b, blocking, ::tex)
            drawClip(texB, vidB, b, fboB, pulseZoom, blocking, subjectAlpha = (2f * pB - 1f).coerceIn(0f, 1f))
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

        // Post-Pass → Ziel-FBO (Farbe, LUT, Beat-Effekte, Kino-Look)
        target.bind()
        postProgram.use()
        bindTexture(0, source.texture)
        val glow = cinema.bloom > 0.001f || cinema.halation > 0.001f
        if (glow) {
            // Unschärfestufen für Glühen/Lichtsaum
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        }
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
        postProgram.set1f("uBloom", cinema.bloom)
        postProgram.set1f("uHalation", cinema.halation)
        postProgram.set1f("uGrain", cinema.grain)
        postProgram.set1f("uLetterbox", cinema.letterbox)
        quad.draw()
        if (glow) {
            bindTexture(0, source.texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        }
        return complete
    }

    /** Bild „cover“ in das Format einpassen, Ken-Burns-Zoom/Schwenk anwenden. */
    private fun drawClip(
        tex: ImageTexture?, video: VideoFrameSource?, state: ClipState, target: Fbo,
        extraZoom: Float, blocking: Boolean, subjectAlpha: Float,
    ) {
        target.bind()
        val prog = if (video != null) clipOesProgram else clipProgram
        prog.use()
        val texW: Int
        val texH: Int
        if (video != null) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, video.textureId)
            prog.setMat4("uTexMatrix", video.transform)
            texW = video.width; texH = video.height
        } else {
            val t = tex ?: black
            bindTexture(0, t.id)
            texW = t.width; texH = t.height
        }
        prog.set1i("uTex", 0)
        val imageAspect = texW.toFloat() / texH
        val outAspect = width.toFloat() / height
        // „Cover“: Bild füllt das Format (Span ≤ 1 je Achse)
        val coverX: Float
        val coverY: Float
        if (imageAspect > outAspect) { coverX = outAspect / imageAspect; coverY = 1f } else { coverX = 1f; coverY = imageAspect / outAspect }
        val zoom = (state.scale * extraZoom).coerceAtLeast(1f)
        val c = if (state.color == null) projectParams else ColorLooks.resolve(grade, state.color)
        prog.set4f("uG1", c.brightness, c.contrast, c.saturation, c.temperature)
        prog.set3f("uG2", c.tint, c.fade, if (c.isIdentity) 0f else 1f)
        prog.set3f("uShadow", c.shadowTint.r, c.shadowTint.g, c.shadowTint.b)
        prog.set3f("uHigh", c.highlightTint.r, c.highlightTint.g, c.highlightTint.b)
        if (state.fit == FitMode.BLUR) {
            // „Contain“: ganzes Bild sichtbar (Span ≥ 1), Rand = unscharfer Hintergrund
            val containX = coverX / minOf(coverX, coverY)
            val containY = coverY / minOf(coverX, coverY)
            prog.set1i("uMode", 1)
            prog.set1f("uParallax", 0f)
            prog.set2f("uCenter", 0.5f, 0.5f)
            prog.set2f("uSpan", containX / zoom, containY / zoom)
            prog.set2f("uBgSpan", coverX * 0.9f, coverY * 0.9f)
        } else {
            val spanX = coverX / zoom
            val spanY = coverY / zoom
            val cx = if (spanX >= 1f) 0.5f else state.centerX.coerceIn(spanX / 2, 1f - spanX / 2)
            val cy = if (spanY >= 1f) 0.5f else state.centerY.coerceIn(spanY / 2, 1f - spanY / 2)
            prog.set1i("uMode", 0)
            prog.set2f("uCenter", cx, cy)
            prog.set2f("uSpan", spanX, spanY)
            // Parallax / Personen-Ebene
            val maskPath = state.maskPath
            val mask = maskPath?.let { if (blocking) cache.getBlocking(it) else cache.getIfReady(it) }
            val fgZoom = 1f + parallax * 0.10f * state.progress
            if (mask != null && parallax > 0.001f && video == null) {
                bindTexture(1, mask.id)
                prog.set1i("uMask", 1)
                prog.set1f("uParallax", parallax)
                prog.set1f("uFgZoom", fgZoom)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            } else {
                prog.set1f("uParallax", 0f)
            }
            if (maskPath != null && subjectAlpha > 0.01f) {
                val z = if (parallax > 0.001f) fgZoom else 1f
                subjects += Subject(maskPath, cx, cy, spanX / z, spanY / z, subjectAlpha)
            }
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
        overlays.release()
        black.release()
        fboA.release(); fboB.release(); fboC.release(); fboP.release()
        if (accCreated) fboAcc.release()
        copyProgram.release()
        subjectProgram.release()
        clipOesProgram.release()
        videos.values.forEach { it.release() }
        videos.clear()
        GLES30.glDeleteTextures(1, intArrayOf(lutTexture), 0)
        clipProgram.release(); postProgram.release(); transitionProgram.release()
        quad.release()
    }
}
