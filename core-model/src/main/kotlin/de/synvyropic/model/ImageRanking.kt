package de.synvyropic.model

import kotlin.math.sqrt

/** Bildmetriken aus Graustufen-/RGB-Pixeln (reine Rechnung, ohne Android). */
object ImageMetrics {

    /** Varianz des Laplace-Operators – hoch = scharf. gray: Werte 0..1, Zeilen nacheinander. */
    fun sharpness(gray: FloatArray, w: Int, h: Int): Float {
        if (w < 3 || h < 3) return 0f
        var sum = 0.0
        var sq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4f * gray[i]
            sum += l; sq += l * l; n++
        }
        val mean = sum / n
        return (sq / n - mean * mean).toFloat() * 1000f
    }

    /** dHash aus einem 9×8-Graustufenbild: Bit = linkes Pixel heller als rechtes. */
    fun dHash(gray9x8: FloatArray): Long {
        require(gray9x8.size == 72)
        var h = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (gray9x8[y * 9 + x] > gray9x8[y * 9 + x + 1]) h = h or (1L shl bit)
            bit++
        }
        return h
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Farbigkeit nach Hasler & Süsstrunk, grob auf 0..1 skaliert. rgb: 0xRRGGBB-Werte. */
    fun colorfulness(rgb: IntArray): Float {
        if (rgb.isEmpty()) return 0f
        var sRg = 0.0; var sYb = 0.0; var qRg = 0.0; var qYb = 0.0
        for (p in rgb) {
            val r = (p shr 16 and 0xFF).toDouble(); val g = (p shr 8 and 0xFF).toDouble(); val b = (p and 0xFF).toDouble()
            val rg = r - g; val yb = 0.5 * (r + g) - b
            sRg += rg; sYb += yb; qRg += rg * rg; qYb += yb * yb
        }
        val n = rgb.size
        val mRg = sRg / n; val mYb = sYb / n
        val std = sqrt((qRg / n - mRg * mRg) + (qYb / n - mYb * mYb))
        val mean = sqrt(mRg * mRg + mYb * mYb)
        return ((std + 0.3 * mean) / 110.0).toFloat().coerceIn(0f, 1f)
    }
}

/** Bewertung und Ordnung der Projektbilder. */
object ImageRanking {

    private fun medianSharpness(infos: Collection<ImageInfo>): Float? =
        infos.mapNotNull { it.sharpness }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /** Unscharf = deutlich unschärfer als der Durchschnitt des Projekts. */
    fun blurry(images: List<String>, info: Map<String, ImageInfo>): Set<String> {
        val med = medianSharpness(images.mapNotNull { info[it] }) ?: return emptySet()
        if (med <= 0f) return emptySet()
        return images.filter { p -> info[p]?.sharpness?.let { it < med * 0.3f } == true }.toSet()
    }

    /** Fast gleiche Fotos: von jeder Gruppe bleibt das schärfste, die übrigen werden gemeldet. */
    fun duplicates(images: List<String>, info: Map<String, ImageInfo>, maxDistance: Int = 6): Set<String> {
        val out = HashSet<String>()
        val withHash = images.filter { info[it]?.dhash != null }
        val used = HashSet<String>()
        for (a in withHash) {
            if (a in used) continue
            val group = withHash.filter { b -> b !in used && ImageMetrics.hamming(info[a]!!.dhash!!, info[b]!!.dhash!!) <= maxDistance }
            used += group
            if (group.size > 1) {
                val keep = group.maxBy { info[it]?.sharpness ?: 0f }
                out += group - keep
            }
        }
        return out
    }

    /** Punktzahl 0..1: Schärfe (relativ), Gesichter, Farbigkeit. */
    fun score(path: String, info: Map<String, ImageInfo>, images: List<String>): Float {
        val i = info[path] ?: return 0f
        val med = medianSharpness(images.mapNotNull { info[it] }) ?: 1f
        val sharp = ((i.sharpness ?: med) / (med * 2f).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
        val faces = if (i.faces.isNotEmpty()) 1f else 0f
        return 0.5f * sharp + 0.3f * faces + 0.2f * (i.colorfulness ?: 0.5f)
    }

    /** Nach Aufnahmezeit sortieren (unbekannte Zeiten ans Ende, sonst Reihenfolge behalten). */
    fun byDate(images: List<String>, info: Map<String, ImageInfo>): List<String> =
        images.withIndex().sortedWith(compareBy({ info[it.value]?.takenAt ?: Long.MAX_VALUE }, { it.index })).map { it.value }

    /**
     * Ordnet die Bilder so, dass die besten auf den Highlight-Plätzen liegen;
     * alle übrigen behalten ihre Reihenfolge.
     */
    fun placeBest(images: List<String>, info: Map<String, ImageInfo>, highlightSlots: List<Int>): List<String> {
        val slots = highlightSlots.filter { it in images.indices }.distinct()
        if (slots.isEmpty()) return images
        val best = images.sortedByDescending { score(it, info, images) }.take(slots.size)
        val rest = images.filter { it !in best }.toMutableList()
        val out = ArrayList<String>(images.size)
        var b = 0
        for (i in images.indices) out += if (i in slots) best[b++] else rest.removeAt(0)
        return out
    }
}
