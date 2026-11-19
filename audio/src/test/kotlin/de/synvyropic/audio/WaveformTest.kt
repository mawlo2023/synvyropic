package de.synvyropic.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformTest {
    @Test
    fun loudSectionIsHigherAndNormalized() {
        val s = FloatArray(10000) { i -> if (i < 5000) 0.1f * (if (i % 2 == 0) 1 else -1) else 0.8f * (if (i % 2 == 0) 1 else -1) }
        val w = Waveform.peaks(s, 100)
        assertEquals(100, w.size)
        assertEquals(1f, w.max(), 1e-4f)
        assertTrue(w.first() < 0.2f)
    }

    @Test
    fun silenceAndEmpty() {
        assertTrue(Waveform.peaks(FloatArray(0)).isEmpty())
        assertTrue(Waveform.peaks(FloatArray(500), 50).all { it == 0f })
        assertEquals(10, Waveform.peaks(FloatArray(10) { 0.5f }, 1200).size) // nie mehr Abschnitte als Samples
    }
}
