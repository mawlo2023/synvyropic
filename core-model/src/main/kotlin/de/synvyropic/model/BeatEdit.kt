package de.synvyropic.model

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Manuelle Korrekturen am Beat-Raster. Ergebnis ist immer ein neues, in sich stimmiges Raster. */
object BeatEdit {

    /** Doppeltes Tempo: zwischen je zwei Beats einen einfügen. */
    fun double(g: BeatGrid): BeatGrid {
        if (g.beatsMs.size < 2) return g
        val beats = ArrayList<Long>(); val str = ArrayList<Float>(); val en = ArrayList<Float>()
        for (i in g.beatsMs.indices) {
            beats += g.beatsMs[i]; str += g.strengths.getOrElse(i) { 1f }; en += g.energies.getOrElse(i) { 1f }
            if (i + 1 < g.beatsMs.size) {
                beats += (g.beatsMs[i] + g.beatsMs[i + 1]) / 2
                str += g.strengths.getOrElse(i) { 1f } * 0.6f; en += g.energies.getOrElse(i) { 1f }
            }
        }
        return g.copy(
            bpm = g.bpm * 2, beatsMs = beats, strengths = str,
            energies = if (g.energies.isEmpty()) emptyList() else en,
            // Takt-Eins alle 4 neuen Beats, ausgehend von der ersten bisherigen Takt-Eins
            downbeatIndices = (g.downbeatIndices.firstOrNull() ?: 0).let { f -> beats.indices.filter { (it - f * 2).mod(4) == 0 } },
            dropIndices = g.dropIndices.map { it * 2 }, edited = true,
        )
    }

    /** Halbes Tempo: jeden zweiten Beat behalten (die Phase mit den meisten Takt-Einsen). */
    fun half(g: BeatGrid): BeatGrid {
        if (g.beatsMs.size < 4) return g
        val phase = if (g.downbeatIndices.count { it % 2 == 0 } >= g.downbeatIndices.count { it % 2 == 1 }) 0 else 1
        val keep = g.beatsMs.indices.filter { it % 2 == phase }
        fun map(list: List<Int>) = list.filter { it % 2 == phase }.map { keep.indexOf(it) }.filter { it >= 0 }
        return g.copy(
            bpm = g.bpm / 2,
            beatsMs = keep.map { g.beatsMs[it] },
            strengths = keep.map { g.strengths.getOrElse(it) { 1f } },
            energies = if (g.energies.isEmpty()) emptyList() else keep.map { g.energies[it] },
            downbeatIndices = map(g.downbeatIndices).filterIndexed { i, _ -> i % 2 == 0 }.ifEmpty { map(g.downbeatIndices) },
            dropIndices = map(g.dropIndices), edited = true,
        )
    }

    /** Alle Beats um [ms] verschieben (positiv = später). */
    fun shift(g: BeatGrid, ms: Long): BeatGrid =
        g.copy(beatsMs = g.beatsMs.map { (it + ms).coerceAtLeast(0) }, edited = true)

    /**
     * Tap-Tempo: aus mitgetippten Zeitpunkten (Musikzeit, ms) Tempo und Phase bestimmen
     * und ein gleichmäßiges Raster über die ganze Datei legen. Die erste Tap-Zeit wird Takt-Eins.
     */
    fun fromTaps(g: BeatGrid?, taps: List<Long>, durationMs: Long): BeatGrid? {
        if (taps.size < 4) return null
        val intervals = taps.zipWithNext { a, b -> (b - a).toDouble() }.filter { it in 250.0..1500.0 }
        if (intervals.size < 3) return null
        val period = intervals.sorted()[intervals.size / 2]
        // Phase: mittlere Abweichung der Taps vom Raster ab dem ersten Tap
        val first = taps.first().toDouble()
        val dev = taps.map { t -> val k = ((t - first) / period).roundToInt(); t - (first + k * period) }.average()
        val anchor = first + dev
        val startK = -(anchor / period).toInt()
        val beats = ArrayList<Long>()
        var k = startK
        while (true) {
            val t = anchor + k * period
            if (t > durationMs) break
            if (t >= 0) beats += t.roundToLong()
            k++
        }
        if (beats.size < 2) return null
        val firstTapIdx = beats.indexOfFirst { abs(it - anchor) < period / 2 }.coerceAtLeast(0)
        val down = beats.indices.filter { (it - firstTapIdx).mod(4) == 0 }
        // Lautheit/Betonung vom alten Raster übernehmen (nächster alter Beat)
        fun nearest(t: Long): Int? = g?.beatsMs?.let { old -> old.indices.minByOrNull { abs(old[it] - t) } }
        return BeatGrid(
            bpm = (60000.0 / period).toFloat(),
            beatsMs = beats,
            strengths = beats.map { t -> nearest(t)?.let { g!!.strengths.getOrNull(it) } ?: 1f },
            downbeatIndices = down,
            dropIndices = g?.dropIndices?.mapNotNull { d -> g.beatsMs.getOrNull(d)?.let { t -> beats.indices.minByOrNull { abs(beats[it] - t) } } }.orEmpty(),
            energies = if (g?.energies.isNullOrEmpty()) emptyList() else beats.map { t -> nearest(t)?.let { g!!.energies.getOrNull(it) } ?: 1f },
            version = g?.version ?: 2,
            edited = true,
        )
    }
}
