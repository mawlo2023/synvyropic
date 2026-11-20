package de.synvyropic.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetischer, aber musikähnlicher Track: Kick (1+3), Snare (2+4), Achtel-Hi-Hats,
 * Bassline auf Achteln, Synth-Fläche; optional ein Break (nur Fläche, leise).
 */
object RealisticTrack {
    const val RATE = 22050

    data class Track(val samples: FloatArray, val beatMs: Double, val offsetMs: Double, val breakBeats: IntRange?)

    fun make(
        bpm: Double, beats: Int, offsetMs: Double = 180.0, breakAt: IntRange? = null,
        swing: Double = 0.0, seed: Int = 7,
    ): Track {
        val beatS = 60.0 / bpm
        val n = ((offsetMs / 1000 + beats * beatS + 1.0) * RATE).toInt()
        val out = FloatArray(n)
        val rnd = Random(seed)
        fun add(start: Double, len: Double, f: (Double) -> Double) {
            val s = (start * RATE).toInt()
            for (i in 0 until (len * RATE).toInt()) { val idx = s + i; if (idx in out.indices) out[idx] += f(i.toDouble() / RATE).toFloat() }
        }
        val notes = doubleArrayOf(55.0, 55.0, 65.4, 73.4)
        for (b in 0 until beats) {
            val t0 = offsetMs / 1000 + b * beatS
            val inBreak = breakAt != null && b in breakAt
            if (!inBreak) {
                if (b % 2 == 0) add(t0, 0.25) { t -> 0.9 * exp(-t * 18) * sin(2 * PI * (50 + 100 * exp(-t * 35)) * t) }      // Kick
                else add(t0, 0.2) { t -> 0.45 * exp(-t * 22) * (rnd.nextDouble() * 2 - 1) + 0.2 * exp(-t * 30) * sin(2 * PI * 190 * t) } // Snare
                for (h in 0..1) {
                    val ht = t0 + h * beatS / 2 + if (h == 1) swing * beatS / 2 else 0.0
                    add(ht, 0.05) { t -> 0.12 * exp(-t * 120) * (rnd.nextDouble() * 2 - 1) }                                // Hi-Hat
                    val f = notes[(b / 2) % notes.size]
                    add(t0 + h * beatS / 2, beatS / 2 * 0.9) { t -> 0.25 * (1 - exp(-t * 60)) * exp(-t * 3) * sin(2 * PI * f * t) } // Bass
                }
            }
            // Fläche läuft immer (im Break leiser hörbar, weil alles andere fehlt)
            add(t0, beatS) { t -> 0.06 * (sin(2 * PI * 220 * (t0 + t)) + sin(2 * PI * 277.2 * (t0 + t)) + sin(2 * PI * 329.6 * (t0 + t))) }
        }
        return Track(out, beatS * 1000, offsetMs, breakAt)
    }
}
