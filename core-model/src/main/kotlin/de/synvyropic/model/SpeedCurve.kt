package de.synvyropic.model

import kotlin.math.exp

/**
 * Tempo-Rampen: Geschwindigkeit s(t) über die Clip-Dauer und die daraus folgende Quellzeit
 * F(t) = ∫ s dt. Die Quellzeit wird so normiert, dass sie nie über den verfügbaren Ausschnitt hinausläuft.
 */
object SpeedCurve {

    /**
     * @param local Zeit seit Clip-Beginn (ms)
     * @param length Clip-Länge (ms)
     * @param beats Beat-Zeitpunkte relativ zum Clip-Beginn (für Beat-Velocity)
     */
    fun speed(ramp: SpeedRamp, local: Float, length: Float, beats: FloatArray): Float {
        val u = if (length > 0f) (local / length).coerceIn(0f, 1f) else 0f
        return when (ramp) {
            SpeedRamp.NONE -> 1f
            SpeedRamp.SLOW_MO -> 0.5f
            SpeedRamp.FAST -> 2f
            SpeedRamp.SLOW_TO_FAST -> 0.35f + (2.5f - 0.35f) * smooth((u - 0.4f) / 0.2f)
            SpeedRamp.FAST_TO_SLOW -> 2.5f + (0.35f - 2.5f) * smooth((u - 0.4f) / 0.2f)
            SpeedRamp.BEAT_VELOCITY -> {
                // Phase seit dem letzten Beat → schneller Ruck auf dem Beat, danach Zeitlupe
                var last = 0f
                var next = length
                for (b in beats) { if (b <= local) last = b else { next = b; break } }
                val span = (next - last).coerceAtLeast(1f)
                val phase = ((local - last) / span).coerceIn(0f, 1f)
                0.35f + 2.65f * exp(-phase * 8f)
            }
        }
    }

    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** Vorberechnete Quellzeit-Tabelle eines Clips (Schritt 5 ms). */
    class Table(ramp: SpeedRamp, private val length: Long, beats: FloatArray) {
        private val step = 5f
        private val values: FloatArray
        val total: Float

        init {
            val n = (length / step).toInt() + 2
            values = FloatArray(n)
            var acc = 0f
            for (i in 1 until n) {
                val t = (i - 0.5f) * step
                acc += speed(ramp, t, length.toFloat(), beats) * step
                values[i] = acc
            }
            total = values[((length / step).toInt()).coerceIn(0, n - 1)].coerceAtLeast(1e-3f)
        }

        /** F(local) in ms (Quellzeit ohne Normierung). */
        fun at(local: Long): Float {
            val x = (local.coerceIn(0, length) / step)
            val i = x.toInt().coerceIn(0, values.size - 2)
            val f = x - i
            return values[i] + (values[i + 1] - values[i]) * f
        }

        /** Fortschritt 0..1 der Quellzeit. */
        fun progress(local: Long): Float = (at(local) / total).coerceIn(0f, 1f)

        /** Quellposition im Video: Start + Fortschritt × min(benötigt, verfügbar). */
        fun sourceMs(local: Long, trimStart: Long, available: Long): Long =
            trimStart + (progress(local) * minOf(total, available.toFloat())).toLong()
    }

    /** Mittlere Geschwindigkeit (für die Beat-Zuteilung im Auto-Schnitt). */
    fun meanSpeed(ramp: SpeedRamp): Float = when (ramp) {
        SpeedRamp.NONE -> 1f
        SpeedRamp.SLOW_MO -> 0.5f
        SpeedRamp.FAST -> 2f
        SpeedRamp.SLOW_TO_FAST, SpeedRamp.FAST_TO_SLOW -> 1.4f
        SpeedRamp.BEAT_VELOCITY -> 0.65f
    }
}
