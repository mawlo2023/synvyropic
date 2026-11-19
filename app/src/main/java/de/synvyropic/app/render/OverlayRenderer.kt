package de.synvyropic.app.render

import android.graphics.BitmapFactory
import android.opengl.GLES30
import de.synvyropic.model.Corner
import de.synvyropic.model.OverlayFrame
import de.synvyropic.model.OverlayResolver
import de.synvyropic.model.Project
import de.synvyropic.model.TextStyle
import de.synvyropic.model.Watermark
import java.io.File

/**
 * Zeichnet Texte, Intro/Outro-Abdunkelung und Wasserzeichen über das fertige Bild.
 * Läuft auf dem GL-Thread; Text-Texturen werden nach Inhalt zwischengespeichert.
 */
class OverlayRenderer(private val width: Int, private val height: Int) {

    private val program = GlProgram(Shaders.OVERLAY_VERTEX, Shaders.OVERLAY, "overlay")
    private val quad = FullscreenQuad()
    private val black = ImageTexture.solid(0xFF000000.toInt())
    private val scale = width / 1080f
    private val textCache = HashMap<String, ImageTexture>()
    private var resolver: OverlayResolver? = null
    private var watermark: Watermark? = null
    private var logo: ImageTexture? = null
    private var logoPath: String? = null

    fun setProject(project: Project, resolver: OverlayResolver) {
        this.resolver = resolver
        watermark = project.watermark
        val path = project.watermark?.imageUri
        if (path != logoPath) {
            logo?.release(); logo = null; logoPath = path
            if (path != null) {
                BitmapFactory.decodeFile(path)?.let { bmp -> logo = ImageTexture.upload(bmp); bmp.recycle() }
            }
        }
        // Nicht mehr benötigte Text-Texturen freigeben
        val keys = resolver.items.map { key(it.text, it.style) }.toSet() + watermarkKey()
        textCache.keys.filter { it !in keys }.forEach { textCache.remove(it)?.release() }
    }

    fun render(tMs: Long) {
        val r = resolver ?: return
        val dim = r.dimAt(tMs)
        val frames = r.at(tMs)
        val wm = watermark
        if (dim <= 0.001f && frames.isEmpty() && wm == null) return

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        program.use()
        program.set1i("uTex", 0)
        program.set1f("uTime", tMs / 1000f)
        if (dim > 0.001f) draw(black, 0f, 0f, 1f, 1f, dim)
        frames.forEach { drawText(it) }
        wm?.let { drawWatermark(it) }
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawText(f: OverlayFrame) {
        val tex = text(f.item.text, f.item.style)
        val hw = tex.width.toFloat() / width * f.scale
        val hh = tex.height.toFloat() / height * f.scale
        val cx = (f.item.style.x + f.offsetX) * 2f - 1f
        val cy = 1f - (f.item.style.y + f.offsetY) * 2f
        draw(tex, cx, cy, hw, hh, f.alpha, f.reveal, f.glitch)
    }

    private fun drawWatermark(w: Watermark) {
        val margin = 0.035f * width
        val right = w.corner == Corner.TOP_RIGHT || w.corner == Corner.BOTTOM_RIGHT
        val bottom = w.corner == Corner.BOTTOM_LEFT || w.corner == Corner.BOTTOM_RIGHT
        // Elemente von der Ecke aus stapeln (Pixelkoordinaten, y nach unten)
        var cursorY = if (bottom) height - margin else margin
        val items = buildList {
            logo?.let { l -> val lw = w.scale * width; add(Triple(l, lw, lw * l.height / l.width)) }
            w.text?.takeIf { it.isNotBlank() }?.let { t ->
                val tex = text(t, watermarkStyle(w))
                add(Triple(tex, tex.width.toFloat(), tex.height.toFloat()))
            }
        }.let { if (bottom) it.reversed() else it }
        for ((tex, pw, ph) in items) {
            val px = if (right) width - margin - pw / 2 else margin + pw / 2
            val py = if (bottom) cursorY - ph / 2 else cursorY + ph / 2
            cursorY += if (bottom) -ph else ph
            draw(tex, px / width * 2f - 1f, 1f - py / height * 2f, pw / width, ph / height, w.opacity)
        }
    }

    private fun watermarkStyle(w: Watermark) = TextStyle(size = w.textSize, shadow = true)
    private fun watermarkKey() = watermark?.text?.let { key(it, watermarkStyle(watermark!!)) } ?: ""

    private fun key(text: String, style: TextStyle) = "$text|$style"

    private fun text(text: String, style: TextStyle): ImageTexture =
        textCache.getOrPut(key(text, style)) {
            val bmp = TextRasterizer.render(text, style, scale, (width * 0.88f).toInt())
            ImageTexture.upload(bmp).also { bmp.recycle() }
        }

    /** Rechteck mit Mittelpunkt (cx, cy) und halber Größe (hw, hh) in NDC zeichnen. */
    private fun draw(tex: ImageTexture, cx: Float, cy: Float, hw: Float, hh: Float, alpha: Float, reveal: Float = 1f, glitch: Float = 0f) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex.id)
        program.set2f("uCenter", cx, cy)
        program.set2f("uHalf", hw, hh)
        program.set1f("uAlpha", alpha.coerceIn(0f, 1f))
        program.set1f("uReveal", reveal)
        program.set1f("uGlitch", glitch)
        quad.draw()
    }

    fun release() {
        textCache.values.forEach { it.release() }
        textCache.clear()
        logo?.release()
        black.release()
        program.release()
        quad.release()
    }
}
