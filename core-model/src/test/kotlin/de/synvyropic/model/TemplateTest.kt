package de.synvyropic.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateTest {
    private val styled = Project(
        id = "a", name = "Vorbild", createdAt = 0, styleId = Styles.RETRO_VHS.id, format = AspectFormat.LANDSCAPE_16_9,
        color = ColorGrade(presetId = "vintage", lutPath = "/p/a/media/lut.cube", lutName = "Mein LUT"),
        cinema = CinemaLook.PRESET, intro = TitleCard("Hallo"),
        watermark = Watermark(imageUri = "/p/a/media/logo.png", text = "@kanal"),
        images = listOf("x"), music = MusicTrack("m"),
    )

    @Test
    fun roundTripCopiesDesignButNotContent() {
        val t = ProjectTemplate.from(styled, "t1", "Retro", 1) { it.replace("/p/a/media", "/tpl/t1") }
        assertEquals("/tpl/t1/lut.cube", t.color.lutPath)
        assertEquals("/tpl/t1/logo.png", t.watermark!!.imageUri)

        val target = Project(id = "b", name = "Neu", createdAt = 0, images = listOf("y", "z"))
        val applied = t.applyTo(target) { it.replace("/tpl/t1", "/p/b/media") }
        assertEquals(Styles.RETRO_VHS.id, applied.styleId)
        assertEquals(AspectFormat.LANDSCAPE_16_9, applied.format)
        assertEquals("/p/b/media/lut.cube", applied.color.lutPath)
        assertEquals("/p/b/media/logo.png", applied.watermark!!.imageUri)
        assertEquals(CinemaLook.PRESET, applied.cinema)
        assertEquals(listOf("y", "z"), applied.images)          // Inhalte bleiben
        assertEquals(null, applied.music)
        assertEquals("Neu", applied.name)
        assertEquals(2, applied.clips.size)                    // neu geschnitten
    }
}
