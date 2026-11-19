package de.synvyropic.model

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Bestimmt Darstellungsmodus und Ken-Burns-Fahrt so, dass erkannte Gesichter im Bild bleiben.
 * Koordinaten: normiert auf das Bild (0..1, y nach unten); „Span“ = sichtbarer Anteil bei Zoom 1.
 */
object Framing {

    data class Result(val fit: FitMode, val motion: KenBurns)

    /** Rand um Gesichter (Haare, Kinn) als Anteil der Gesichtsgröße. */
    private const val MARGIN = 0.45f

    fun frame(
        info: ImageInfo?,
        format: AspectFormat,
        index: Int,
        strength: Float,
        rnd: Random,
    ): Result {
        if (info == null || info.width <= 0 || info.height <= 0) return Result(FitMode.FILL, randomMotion(index, strength, rnd))
        val imageAspect = info.width.toFloat() / info.height
        val outAspect = format.width.toFloat() / format.height
        val (spanX, spanY) = coverSpan(imageAspect, outAspect)
        val union = union(info.faces)

        val mode = when (info.fit) {
            FitMode.AUTO -> autoMode(union, info.faces.size, spanX, spanY)
            else -> info.fit
        }
        return when (mode) {
            FitMode.BLUR -> Result(FitMode.BLUR, blurMotion(index, strength))
            FitMode.PAN -> if (info.faces.size >= 2) Result(FitMode.PAN, panMotion(info.faces, spanX, spanY, index))
                           else Result(FitMode.FILL, fillMotion(union, spanX, spanY, index, strength, rnd))
            else -> Result(FitMode.FILL, fillMotion(union, spanX, spanY, index, strength, rnd))
        }
    }

    fun coverSpan(imageAspect: Float, outAspect: Float): Pair<Float, Float> =
        if (imageAspect > outAspect) (outAspect / imageAspect) to 1f else 1f to (imageAspect / outAspect)

    /** Vereinigung aller Gesichter inkl. Rand, auf das Bild begrenzt. */
    fun union(faces: List<FaceBox>): FaceBox? {
        if (faces.isEmpty()) return null
        var l = 1f; var t = 1f; var r = 0f; var b = 0f
        for (f in faces) {
            val mx = f.width * MARGIN
            val my = f.height * MARGIN
            l = min(l, f.left - mx); t = min(t, f.top - my * 1.3f) // oben mehr Platz für Haare
            r = max(r, f.right + mx); b = max(b, f.bottom + my)
        }
        return FaceBox(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    private fun autoMode(union: FaceBox?, faceCount: Int, spanX: Float, spanY: Float): FitMode {
        if (union == null) return FitMode.FILL
        if (union.width <= spanX && union.height <= spanY) return FitMode.FILL
        // Gesichter passen nicht: weit verteilte Gruppen ganz zeigen, sonst schwenken
        val spread = max(union.width / spanX, union.height / spanY)
        return if (faceCount >= 2 && spread <= 1.8f) FitMode.PAN else FitMode.BLUR
    }

    /** Füllend: Ausschnitt um die Gesichter, Zoom nur so weit, dass alle sichtbar bleiben. */
    private fun fillMotion(union: FaceBox?, spanX: Float, spanY: Float, index: Int, strength: Float, rnd: Random): KenBurns {
        if (union == null) return randomMotion(index, strength, rnd)
        val maxZoom = max(1f, min(spanX / union.width.coerceAtLeast(1e-3f), spanY / union.height.coerceAtLeast(1e-3f)))
        val zoom = 1f + min(strength, maxZoom - 1f)
        val cx = union.centerX
        val cy = union.centerY
        val zoomIn = index % 2 == 0
        return KenBurns(
            enabled = true,
            startScale = if (zoomIn) 1f else zoom,
            endScale = if (zoomIn) zoom else 1f,
            startCenterX = cx, startCenterY = cy,
            endCenterX = cx, endCenterY = cy,
        )
    }

    /** Kamerafahrt vom ersten zum letzten Gesicht entlang der langen Bildachse. */
    private fun panMotion(faces: List<FaceBox>, spanX: Float, spanY: Float, index: Int): KenBurns {
        val horizontal = spanX < spanY || spanX < 1f
        val sorted = if (horizontal) faces.sortedBy { it.centerX } else faces.sortedBy { it.centerY }
        var a = sorted.first()
        var b = sorted.last()
        if (index % 2 == 1) { val t = a; a = b; b = t }
        return KenBurns(
            enabled = true,
            startScale = 1.05f, endScale = 1.05f,
            startCenterX = a.centerX, startCenterY = a.centerY,
            endCenterX = b.centerX, endCenterY = b.centerY,
        )
    }

    private fun blurMotion(index: Int, strength: Float): KenBurns {
        val z = 1f + strength * 0.5f
        val zoomIn = index % 2 == 0
        return KenBurns(enabled = true, startScale = if (zoomIn) 1f else z, endScale = if (zoomIn) z else 1f)
    }

    /** Ohne Gesichter: abwechselnd rein-/rauszoomen mit leichtem, zufälligem Schwenk. */
    fun randomMotion(index: Int, strength: Float, rnd: Random): KenBurns {
        if (strength <= 0f) return KenBurns(enabled = false)
        val zoomIn = index % 2 == 0
        val pan = strength * 0.5f
        val dx = (rnd.nextFloat() * 2 - 1) * pan
        val dy = (rnd.nextFloat() * 2 - 1) * pan
        return KenBurns(
            enabled = true,
            startScale = if (zoomIn) 1f else 1f + strength,
            endScale = if (zoomIn) 1f + strength else 1f,
            startCenterX = 0.5f - dx / 2, startCenterY = 0.5f - dy / 2,
            endCenterX = 0.5f + dx / 2, endCenterY = 0.5f + dy / 2,
        )
    }
}
