package de.synvyropic.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class BeatDetectorTest {

    private val rate = 22050

    /**
     * Synthetischer Track: Kick auf jedem Beat (Takt-Eins lauter), Hi-Hat auf Offbeats,
     * optional ab [dropAtBeat] deutlich lauter (Drop) mit Synth-Fläche.
     */
    private fun track(bpm: Double, seconds: Double, offset: Double = 0.25, dropAtBeat: Int? = null): FloatArray {
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val rnd = Random(42)
        val beatLen = 60.0 / bpm
        var beat = 0
        var t0 = offset
        while (t0 < seconds) {
            val loud = dropAtBeat != null && beat >= dropAtBeat
            val gain = (if (beat % 4 == 0) 1.0 else 0.6) * (if (dropAtBeat == null || loud) 1.0 else 0.25)
            val start = (t0 * rate).toInt()
            // Kick: 55 Hz Sinus mit Pitch-Drop und schnellem Abklingen
            for (i in 0 until (0.15 * rate).toInt()) {
                val idx = start + i
                if (idx >= n) break
                val t = i.toDouble() / rate
                val f = 55 + 90 * exp(-t * 40)
                out[idx] += (gain * 0.8 * exp(-t * 25) * sin(2 * PI * f * t)).toFloat()
            }
            // Hi-Hat auf dem Offbeat
            val hh = ((t0 + beatLen / 2) * rate).toInt()
            for (i in 0 until (0.03 * rate).toInt()) {
                val idx = hh + i
                if (idx >= n) break
                out[idx] += (gain * 0.15 * exp(-i.toDouble() / rate * 150) * (rnd.nextDouble() * 2 - 1)).toFloat()
            }
            beat++
            t0 += beatLen
        }
        if (dropAtBeat != null) {
            val dropStart = ((offset + dropAtBeat * beatLen) * rate).toInt()
            for (i in dropStart until n) {
                val t = i.toDouble() / rate
                out[i] += (0.25 * (sin(2 * PI * 220 * t) + 0.5 * sin(2 * PI * 330 * t))).toFloat()
            }
        }
        return out
    }

    private fun assertOnGrid(beatsMs: List<Long>, bpm: Double, offsetMs: Double, toleranceMs: Double) {
        val beatMs = 60000.0 / bpm
        val misses = beatsMs.filter { it > 2000 }.map { b ->
            val k = ((b - offsetMs) / beatMs).roundToInt()
            abs(b - (offsetMs + k * beatMs))
        }
        assertTrue("zu wenige Beats: ${beatsMs.size}", misses.size > 10)
        val bad = misses.count { it > toleranceMs }
        assertTrue("$bad von ${misses.size} Beats außerhalb ±$toleranceMs ms, max=${misses.maxOrNull()}", bad == 0)
    }

    @Test
    fun detects120Bpm() {
        val grid = BeatDetector().analyze(track(120.0, 30.0), rate)
        assertEquals(120f, grid.bpm, 1.5f)
        assertOnGrid(grid.beatsMs, 120.0, 250.0, 25.0)
        // Ca. 60 Beats in 30 s
        assertTrue("Beatanzahl ${grid.beatsMs.size}", grid.beatsMs.size in 55..62)
    }

    @Test
    fun detects95BpmWithoutOctaveError() {
        val grid = BeatDetector().analyze(track(95.0, 30.0), rate)
        assertEquals(95f, grid.bpm, 1.5f)
        assertOnGrid(grid.beatsMs, 95.0, 250.0, 25.0)
    }

    @Test
    fun detects140Bpm() {
        val grid = BeatDetector().analyze(track(140.0, 30.0), rate)
        assertEquals(140f, grid.bpm, 2f)
        assertOnGrid(grid.beatsMs, 140.0, 250.0, 25.0)
    }

    @Test
    fun downbeatsAreBarStarts() {
        val grid = BeatDetector().analyze(track(120.0, 30.0), rate)
        assertTrue(grid.downbeatIndices.isNotEmpty())
        // Takt-Einsen liegen bei 250 ms + k * 2000 ms
        grid.downbeatIndices.map { grid.beatsMs[it] }.filter { it > 2000 }.forEach { ms ->
            val k = ((ms - 250) / 2000.0).roundToInt()
            assertTrue("Downbeat bei $ms ms", abs(ms - (250 + k * 2000)) < 30)
        }
    }

    @Test
    fun strengthsAreNormalizedAndAccentsStronger() {
        val grid = BeatDetector().analyze(track(120.0, 30.0), rate)
        assertEquals(grid.beatsMs.size, grid.strengths.size)
        assertTrue(grid.strengths.all { it in 0f..1f })
        val down = grid.downbeatIndices.map { grid.strengths[it] }.average()
        val other = grid.strengths.indices.filter { it !in grid.downbeatIndices }.map { grid.strengths[it] }.average()
        assertTrue("Downbeats $down sollten stärker sein als $other", down > other)
    }

    @Test
    fun findsDrop() {
        // Drop bei Beat 32 → 250 ms + 32 * 500 ms = 16,25 s
        val grid = BeatDetector().analyze(track(120.0, 40.0, dropAtBeat = 32), rate)
        assertTrue("keine Drops gefunden", grid.dropIndices.isNotEmpty())
        val dropMs = grid.beatsMs[grid.dropIndices.first()]
        assertTrue("Drop bei $dropMs ms", abs(dropMs - 16250) < 600)
    }

    @Test
    fun silenceGivesEmptyGrid() {
        val grid = BeatDetector().analyze(FloatArray(rate), rate)
        assertTrue(grid.beatsMs.isEmpty())
    }

    @Test
    fun resamplerHalvesRateAndMixesToMono() {
        val src = 44100
        val pcm = ShortArray(src * 2) // 1 s Stereo
        for (i in 0 until src) {
            val v = (sin(2 * PI * 440 * i / src) * 16000).toInt().toShort()
            pcm[2 * i] = v; pcm[2 * i + 1] = v
        }
        val r = MonoResampler(src, 2, 22050)
        r.accept(pcm.copyOfRange(0, 30000))
        r.accept(pcm.copyOfRange(30000, pcm.size))
        val out = r.result()
        assertEquals(22050f, out.size.toFloat(), 2f)
        assertTrue(out.maxOrNull()!! in 0.45f..0.5f)
    }
}
