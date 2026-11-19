package de.synvyropic.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorTest {
    @Test
    fun neutralGradeIsIdentity() {
        assertTrue(ColorLooks.resolve(ColorGrade()).isIdentity)
    }

    @Test
    fun presetAmountScalesLook() {
        val full = ColorLooks.resolve(ColorGrade(presetId = "warm"))
        val half = ColorLooks.resolve(ColorGrade(presetId = "warm", presetAmount = 0.5f))
        assertEquals(0.4f, full.temperature, 1e-4f)
        assertEquals(0.2f, half.temperature, 1e-4f)
    }

    @Test
    fun imageCorrectionAddsOnTopAndClamps() {
        val p = ColorLooks.resolve(ColorGrade(presetId = "bw", brightness = 0.1f), ColorGrade(brightness = 0.3f, saturation = -0.5f))
        assertEquals(0.4f, p.brightness, 1e-4f)
        assertEquals(-1f, p.saturation, 1e-4f) // -1 + -0.5 → begrenzt
    }

    @Test
    fun allLookIdsUnique() {
        assertEquals(ColorLooks.all.size, ColorLooks.all.map { it.id }.toSet().size)
    }

    @Test
    fun parsesCubeWithCommentsAndDomain() {
        val sb = StringBuilder("# Test\nTITLE \"Mein Look\"\nLUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n\n")
        // invertierende LUT
        for (b in 0..1) for (g in 0..1) for (r in 0..1) sb.append("${1 - r} ${1 - g} ${1 - b}\n")
        val lut = CubeLut.parse(sb.toString())
        assertEquals(2, lut.size)
        assertEquals("Mein Look", lut.title)
        assertEquals(Triple(1f, 1f, 1f), lut.lookupNearest(0f, 0f, 0f))
        assertEquals(Triple(0f, 1f, 1f), lut.lookupNearest(1f, 0f, 0f)) // Rot läuft am schnellsten
    }

    @Test
    fun identityLutMapsToItself() {
        val lut = CubeLut.identity(17)
        assertEquals(Triple(1f, 0.5f, 0f), lut.lookupNearest(1f, 0.5f, 0f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteCube() {
        CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n")
    }

    @Test
    fun imageColorSurvivesRecut() {
        val fix = ColorGrade(brightness = 0.3f)
        val p = AutoCut.apply(
            Project(id = "p", name = "t", createdAt = 0, images = listOf("a", "b"),
                imageInfo = mapOf("a" to ImageInfo(100, 100, color = fix))),
        )
        assertEquals(fix, p.clips[0].color)
        assertNull(p.clips[1].color)
        assertEquals(fix, FrameResolver(p).at(0)!!.a.color)
    }
}
