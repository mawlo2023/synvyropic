package de.synvyropic.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FramingTest {
    private val portrait = AspectFormat.PORTRAIT_9_16
    private fun rnd() = Random(1)

    /** Sichtbarer Bereich bei Zoom z um (cx, cy), begrenzt wie im Renderer. */
    private fun visible(info: ImageInfo, z: Float, cx: Float, cy: Float): FaceBox {
        val (sx0, sy0) = Framing.coverSpan(info.width.toFloat() / info.height, portrait.width.toFloat() / portrait.height)
        val sx = sx0 / z; val sy = sy0 / z
        val x = if (sx >= 1f) 0.5f else cx.coerceIn(sx / 2, 1 - sx / 2)
        val y = if (sy >= 1f) 0.5f else cy.coerceIn(sy / 2, 1 - sy / 2)
        return FaceBox(x - sx / 2, y - sy / 2, x + sx / 2, y + sy / 2)
    }

    private fun contains(outer: FaceBox, inner: FaceBox) =
        inner.left >= outer.left - 1e-3f && inner.right <= outer.right + 1e-3f &&
            inner.top >= outer.top - 1e-3f && inner.bottom <= outer.bottom + 1e-3f

    @Test
    fun singleFaceAtEdgeStaysVisibleThroughoutZoom() {
        // Querformat 4:3, Gesicht ganz rechts – die Bildmitte würde es abschneiden
        val face = FaceBox(0.82f, 0.3f, 0.92f, 0.45f)
        val info = ImageInfo(4000, 3000, listOf(face))
        for (i in 0..1) {
            val r = Framing.frame(info, portrait, i, 0.15f, rnd())
            assertEquals(FitMode.FILL, r.fit)
            val m = r.motion
            assertTrue(contains(visible(info, m.startScale, m.startCenterX, m.startCenterY), face))
            assertTrue(contains(visible(info, m.endScale, m.endCenterX, m.endCenterY), face))
        }
    }

    @Test
    fun wideGroupPhotoUsesBlur() {
        val faces = listOf(0.05f, 0.3f, 0.55f, 0.85f).map { FaceBox(it, 0.4f, it + 0.08f, 0.5f) }
        val r = Framing.frame(ImageInfo(4000, 2250, faces), portrait, 0, 0.15f, rnd())
        assertEquals(FitMode.BLUR, r.fit)
    }

    @Test
    fun twoCloseFacesPanFromOneToTheOther() {
        // 16:9, Gesichter bei x≈0.35 und 0.62 → passen nicht in Span 0.32, aber Spreizung < 1.8
        val faces = listOf(FaceBox(0.32f, 0.35f, 0.38f, 0.45f), FaceBox(0.59f, 0.35f, 0.65f, 0.45f))
        val r = Framing.frame(ImageInfo(1920, 1080, faces), portrait, 0, 0.15f, rnd())
        assertEquals(FitMode.PAN, r.fit)
        assertEquals(0.35f, r.motion.startCenterX, 0.01f)
        assertEquals(0.62f, r.motion.endCenterX, 0.01f)
    }

    @Test
    fun userOverrideWins() {
        val face = FaceBox(0.4f, 0.4f, 0.5f, 0.5f)
        val r = Framing.frame(ImageInfo(4000, 3000, listOf(face), fit = FitMode.BLUR), portrait, 0, 0.15f, rnd())
        assertEquals(FitMode.BLUR, r.fit)
    }

    @Test
    fun noFacesKeepsRandomMotionAndFill() {
        val r = Framing.frame(ImageInfo(3000, 4000), portrait, 0, 0.15f, rnd())
        assertEquals(FitMode.FILL, r.fit)
        assertTrue(r.motion.enabled)
    }

    @Test
    fun autoCutUsesImageInfo() {
        val face = FaceBox(0.85f, 0.3f, 0.95f, 0.45f)
        val p = AutoCut.apply(
            Project(
                id = "p", name = "t", createdAt = 0, images = listOf("a"),
                imageInfo = mapOf("a" to ImageInfo(4000, 3000, listOf(face))),
            ),
        )
        assertTrue(p.clips[0].motion.startCenterX > 0.8f)
    }
}
