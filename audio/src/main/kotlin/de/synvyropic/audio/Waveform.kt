package de.synvyropic.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Kompakte Wellenform für die Anzeige: Spitzenwerte je Abschnitt, normiert auf 0..1. */
object Waveform {
    fun peaks(samples: FloatArray, buckets: Int = 1200): List<Float> {
        if (samples.isEmpty() || buckets <= 0) return emptyList()
        val n = minOf(buckets, samples.size)
        val out = FloatArray(n)
        val per = samples.size.toDouble() / n
        for (b in 0 until n) {
            val a = (b * per).toInt()
            val e = minOf(samples.size, ((b + 1) * per).toInt().coerceAtLeast(a + 1))
            var peak = 0f
            var sq = 0.0
            for (i in a until e) { val v = abs(samples[i]); peak = max(peak, v); sq += v * v }
            val rms = sqrt(sq / (e - a)).toFloat()
            // Mischung aus Spitze und RMS wirkt wie in Schnittprogrammen
            out[b] = 0.6f * peak + 0.4f * rms
        }
        val top = out.maxOrNull()?.takeIf { it > 1e-6f } ?: return List(n) { 0f }
        return out.map { (it / top).coerceIn(0f, 1f) }
    }
}
