package de.synvyropic.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeatEditTest {
    private val g = BeatGrid(
        bpm = 90f, beatsMs = (0 until 16).map { 200L + it * 666L }, strengths = List(16) { 1f },
        downbeatIndices = (0 until 16 step 4).toList(), energies = List(16) { 0.8f }, version = 2,
    )

    @Test
    fun doubleAndHalfAreInverse() {
        val d = BeatEdit.double(g)
        assertEquals(180f, d.bpm, 0.01f)
        assertEquals(31, d.beatsMs.size)
        assertEquals(533L, d.beatsMs[1])
        val h = BeatEdit.half(d)
        assertEquals(90f, h.bpm, 0.01f)
        assertEquals(g.beatsMs, h.beatsMs)
        assertTrue(h.edited)
    }

    @Test
    fun shiftMovesAllBeats() {
        assertEquals(230L, BeatEdit.shift(g, 30).beatsMs.first())
    }

    @Test
    fun tapsBuildEvenGrid() {
        // 125 BPM = 480 ms, Taps leicht ungenau ab 1000 ms
        val taps = listOf(1000L, 1485L, 1958L, 2445L, 2918L, 3402L)
        val r = BeatEdit.fromTaps(g, taps, 10_000)!!
        assertEquals(125f, r.bpm, 2f)
        // ein Beat liegt nahe am ersten Tap und ist Takt-Eins
        val i = r.beatsMs.indices.minBy { abs(r.beatsMs[it] - 1000) }
        assertTrue(abs(r.beatsMs[i] - 1000) < 15)
        assertTrue(i in r.downbeatIndices)
        // gleichmäßig über die ganze Datei
        assertTrue(r.beatsMs.first() < 480 && r.beatsMs.last() > 9500)
    }

    @Test
    fun tooFewTapsIgnored() {
        assertEquals(null, BeatEdit.fromTaps(g, listOf(0L, 500L, 1000L), 5000))
    }
}
