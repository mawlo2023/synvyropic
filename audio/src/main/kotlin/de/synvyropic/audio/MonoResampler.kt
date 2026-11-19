package de.synvyropic.audio

/**
 * Wandelt eingehende PCM-16-Blöcke (interleaved, beliebige Kanalzahl/Rate) fortlaufend
 * in Mono-Float mit Zielrate um. Für die Beat-Analyse reicht lineare Interpolation.
 */
class MonoResampler(
    private val srcRate: Int,
    private val channels: Int,
    val dstRate: Int = 22050,
) {
    private var out = FloatArray(dstRate * 60)
    private var size = 0
    private val step = srcRate.toDouble() / dstRate
    /** Position des nächsten Ausgabesamples in Quell-Samples (global). */
    private var nextPos = 0.0
    /** Anzahl bisher verarbeiteter Quell-Frames (global). */
    private var consumed = 0L
    private var lastSample = 0f

    val sampleCount: Int get() = size

    fun accept(pcm: ShortArray, length: Int = pcm.size) {
        val frames = length / channels
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += pcm[f * channels + c]
            val s = sum / (channels * 32768f)
            val idx = consumed + f
            // Alle Ausgabepositionen zwischen idx-1 und idx interpolieren.
            while (nextPos <= idx) {
                val frac = (nextPos - (idx - 1)).toFloat().coerceIn(0f, 1f)
                append(lastSample + (s - lastSample) * frac)
                nextPos += step
            }
            lastSample = s
        }
        consumed += frames
    }

    fun result(): FloatArray = out.copyOf(size)

    private fun append(v: Float) {
        if (size == out.size) out = out.copyOf(out.size * 2)
        out[size++] = v
    }
}
