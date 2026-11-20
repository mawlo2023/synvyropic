package de.synvyropic.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class RealisticBeatTest {

    /** Abweichungen (ms) der erkannten Beats vom echten Raster (ab 2 s). */
    private fun errors(beatsMs: List<Long>, t: RealisticTrack.Track): List<Double> =
        beatsMs.filter { it > 2000 }.map { b ->
            val k = ((b - t.offsetMs) / t.beatMs).roundToInt()
            abs(b - (t.offsetMs + k * t.beatMs))
        }

    private fun check(bpm: Double, swing: Double = 0.0, allowHalf: Boolean = false) {
        val t0 = RealisticTrack.make(bpm, 64, swing = swing)
        val g0 = BeatDetector().analyze(t0.samples, RealisticTrack.RATE)
        // Halbes Tempo (z. B. Drum & Bass) ist musikalisch zulässig → gegen das halbe Raster prüfen
        val half = allowHalf && kotlin.math.abs(g0.bpm - bpm / 2) < 2
        val t = if (half) t0.copy(beatMs = t0.beatMs * 2) else t0
        val bpmExp = if (half) bpm / 2 else bpm
        val g = g0
        val e = errors(g.beatsMs, t)
        val signed = g.beatsMs.filter { it > 2000 }.map { b -> val k = ((b - t.offsetMs) / t.beatMs).roundToInt(); b - (t.offsetMs + k * t.beatMs) }
        println("  Versatz im Mittel ${"%.1f".format(signed.average())} ms")
        println("BPM soll $bpm, erkannt ${g.bpm}; Beats ${g.beatsMs.size}; mittl. Fehler ${"%.1f".format(e.average())} ms, max ${"%.1f".format(e.max())} ms")
        assertEquals(bpmExp.toFloat(), g.bpm, 2f)
        assertTrue("mittlerer Fehler ${e.average()} ms", e.average() < 10)
        assertTrue("max Fehler ${e.max()} ms", e.max() < 30)
    }

    @Test fun pop100() = check(100.0)
    @Test fun house124() = check(124.0)
    @Test fun hiphop88() = check(88.0)
    @Test fun dnb170() = check(170.0, allowHalf = true)
    @Test fun swing110() = check(110.0, swing = 0.3)

    @Test
    fun breakHasLowEnergy() {
        val t = RealisticTrack.make(120.0, 48, breakAt = 24 until 32)
        val g = BeatDetector().analyze(t.samples, RealisticTrack.RATE)
        assertEquals(g.beatsMs.size, g.energies.size)
        val inBreak = g.beatsMs.indices.filter { val k = ((g.beatsMs[it] - t.offsetMs) / t.beatMs).roundToInt(); k in 25..30 }
        val outside = g.beatsMs.indices.filter { val k = ((g.beatsMs[it] - t.offsetMs) / t.beatMs).roundToInt(); k in 8..20 }
        val eIn = inBreak.map { g.energies[it] }.average()
        val eOut = outside.map { g.energies[it] }.average()
        println("Energie im Break ${"%.2f".format(eIn)}, sonst ${"%.2f".format(eOut)}")
        assertTrue("Break ($eIn) muss deutlich leiser sein als Rest ($eOut)", eIn < 0.5 * eOut)
        // Beats laufen im Break weiter (Tempo bleibt), damit der Schnitt im Raster bleibt
        assertTrue(inBreak.size >= 4)
    }
}
