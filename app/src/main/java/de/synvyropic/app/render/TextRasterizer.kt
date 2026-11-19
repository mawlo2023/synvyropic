package de.synvyropic.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import de.synvyropic.model.TextFont
import de.synvyropic.model.TextStyle
import kotlin.math.ceil

/** Rendert Text mit Kontur und Schatten in eine Bitmap (vormultipliertes Alpha). */
object TextRasterizer {

    fun typeface(font: TextFont): Typeface = when (font) {
        TextFont.SANS_BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
        TextFont.SANS -> Typeface.create("sans-serif", Typeface.NORMAL)
        TextFont.BLACK -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        TextFont.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        TextFont.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        TextFont.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    /**
     * @param scale Pixel pro „1080er-Pixel“ (Renderbreite / 1080)
     * @param maxWidth maximale Zeilenbreite in Pixeln
     */
    fun render(text: String, style: TextStyle, scale: Float, maxWidth: Int): Bitmap {
        val size = (style.size * scale).coerceAtLeast(4f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = typeface(style.font)
            color = style.colorArgb.toInt()
        }
        val pad = ceil(size * 0.35f).toInt()
        val width = maxWidth.coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
            .build()
        // Tatsächlich genutzte Breite (eng um den Text)
        var used = 0f
        for (i in 0 until layout.lineCount) used = maxOf(used, layout.getLineWidth(i))
        val bw = ceil(used).toInt() + pad * 2
        val bh = layout.height + pad * 2
        val bmp = Bitmap.createBitmap(bw.coerceAtLeast(1), bh.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.translate(pad - (width - used) / 2f, pad.toFloat())
        style.outlineArgb?.let { outline ->
            val stroke = TextPaint(paint).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = size * 0.09f
                strokeJoin = Paint.Join.ROUND
                color = outline.toInt()
            }
            StaticLayout.Builder.obtain(text, 0, text.length, stroke, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build().draw(canvas)
        }
        if (style.shadow) paint.setShadowLayer(size * 0.12f, 0f, size * 0.05f, 0xAA000000.toInt())
        layout.draw(canvas)
        return bmp
    }
}
