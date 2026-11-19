package de.synvyropic.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingTest {
    @Test
    fun sharpImageScoresHigherThanBlurred() {
        val w = 32; val h = 32
        val sharp = FloatArray(w * h) { i -> if ((i % w / 4 + i / w / 4) % 2 == 0) 1f else 0f } // Schachbrett
        val flat = FloatArray(w * h) { 0.5f }
        assertTrue(ImageMetrics.sharpness(sharp, w, h) > 10 * (ImageMetrics.sharpness(flat, w, h) + 1e-3f))
    }

    @Test
    fun dHashMatchesNearDuplicates() {
        val a = FloatArray(72) { (it % 9) / 9f }
        val b = a.copyOf().also { it[5] += 0.01f }
        val c = FloatArray(72) { 1f - (it % 9) / 9f }
        assertTrue(ImageMetrics.hamming(ImageMetrics.dHash(a), ImageMetrics.dHash(b)) <= 2)
        assertTrue(ImageMetrics.hamming(ImageMetrics.dHash(a), ImageMetrics.dHash(c)) > 30)
    }

    @Test
    fun colorfulnessGrayIsZero() {
        assertEquals(0f, ImageMetrics.colorfulness(IntArray(100) { 0x808080 }), 1e-4f)
        assertTrue(ImageMetrics.colorfulness(IntArray(100) { if (it % 2 == 0) 0xFF0000 else 0x0000FF }) > 0.5f)
    }

    private val info = mapOf(
        "a" to ImageInfo(10, 10, sharpness = 100f, dhash = 0b1111L, takenAt = 3000),
        "b" to ImageInfo(10, 10, sharpness = 40f, dhash = 0b1110L, takenAt = 1000),   // Duplikat von a, unschärfer
        "c" to ImageInfo(10, 10, sharpness = 110f, dhash = -1L, takenAt = null),
        "d" to ImageInfo(10, 10, sharpness = 5f, dhash = 0x0F0F0F0F0F0F0F0FL, takenAt = 2000),  // unscharf
        "e" to ImageInfo(10, 10, sharpness = 120f, dhash = 0x00FF00FF00FF00FFL, faces = listOf(FaceBox(0.4f, 0.4f, 0.5f, 0.5f))),
    )
    private val images = listOf("a", "b", "c", "d", "e")

    @Test
    fun findsDuplicatesAndBlurry() {
        assertEquals(setOf("b"), ImageRanking.duplicates(images, info))
        assertEquals(setOf("d"), ImageRanking.blurry(images, info))
    }

    @Test
    fun sortsByDateUnknownLast() {
        assertEquals(listOf("b", "d", "a", "c", "e"), ImageRanking.byDate(images, info))
    }

    @Test
    fun bestImagesGoToHighlightSlots() {
        val out = ImageRanking.placeBest(images, info, listOf(0, 3))
        assertEquals("e", out[0]) // scharf + Gesicht
        assertEquals(images.toSet(), out.toSet())
        assertEquals(5, out.size)
    }

    @Test
    fun autoCutPutsBestOnFirstClip() {
        val p = AutoCut.apply(Project(id = "p", name = "t", createdAt = 0, images = images, imageInfo = info, bestOnHighlights = true))
        assertEquals("e", p.clips[0].imageUri)
    }
}
