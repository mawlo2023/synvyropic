package de.synvyropic.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameResolverTest {
    private val grid = BeatGrid(
        bpm = 120f,
        beatsMs = (0 until 40).map { 1000L + it * 500L },
        strengths = List(40) { 1f },
        downbeatIndices = (0 until 40 step 4).toList(),
    )

    /** 3 Clips à 2 Beats (1000 ms); Clip 1 mit 400-ms-RGB-Split, Clip 2 mit hartem Schnitt. */
    private val project = Project(
        id = "p", name = "t", createdAt = 0,
        music = MusicTrack(uri = "m", startMs = 1000, beats = grid),
        clips = listOf(
            Clip("c0", "a", durationBeats = 2, transitionIn = Transition(TransitionType.CUT, 0)),
            Clip("c1", "b", durationBeats = 2, transitionIn = Transition(TransitionType.RGB_SPLIT, 400)),
            Clip("c2", "c", durationBeats = 2, transitionIn = Transition(TransitionType.CUT, 0)),
        ),
    )
    private val r = FrameResolver(project)

    @Test
    fun durationAndClipLookup() {
        assertEquals(3000L, r.durationMs)
        assertEquals(0, r.indexAt(0))
        assertEquals(1, r.indexAt(1000))
        assertEquals(2, r.indexAt(2999))
    }

    @Test
    fun transitionIsCenteredOnCut() {
        // Schnitt bei 1000 ms, Übergang 400 ms → 800..1200 ms
        assertNull(r.at(799)!!.b)
        val start = r.at(800)!!
        assertEquals("a", start.a.imageUri)
        assertEquals("b", start.b!!.imageUri)
        assertEquals(0f, start.progress, 1e-3f)
        assertEquals(0.5f, r.at(1000)!!.progress, 1e-3f)
        assertEquals(TransitionType.RGB_SPLIT, r.at(1100)!!.transition!!.type)
        assertNull(r.at(1200)!!.b)
        assertEquals("b", r.at(1200)!!.a.imageUri)
    }

    @Test
    fun hardCutHasNoTransitionFrames() {
        assertNull(r.at(1999)!!.b)
        assertEquals("c", r.at(2000)!!.a.imageUri)
    }

    @Test
    fun kenBurnsProgressesOverVisibleSpan() {
        val first = r.at(0)!!.a
        val last = r.at(1199)!!.a  // Clip 0 sichtbar bis 1200 (Ende des Übergangs)
        assertEquals(project.clips[0].motion.startScale, first.scale, 1e-3f)
        assertTrue(last.scale > first.scale)
    }

    @Test
    fun beatEnvelopePeaksOnBeats() {
        assertEquals(1f, r.envelope(0), 1e-3f)       // Beat bei Video-t 0
        assertTrue(r.envelope(250) < 0.2f)            // klingt ab
        assertEquals(1f, r.envelope(500), 1e-3f)      // nächster Beat
    }

    @Test
    fun emptyProjectGivesNoFrame() {
        assertNull(FrameResolver(Project(id = "x", name = "x", createdAt = 0)).at(0))
        assertNotNull(r.at(5000)) // über das Ende hinaus wird auf den letzten Frame begrenzt
    }

    @Test
    fun transitionOrdinalsAreStable() {
        // Die Shader nutzen die Ordnungszahlen – Reihenfolge darf sich nicht ändern.
        val expected = listOf(
            "CUT", "CROSSFADE", "RGB_SPLIT", "CHROMATIC_ABERRATION", "DIGITAL_GLITCH", "VHS", "PIXEL_SORT",
            "DATAMOSH", "ZOOM_PUNCH_IN", "ZOOM_PUNCH_OUT", "WHIP_PAN_LEFT", "WHIP_PAN_RIGHT", "WHIP_PAN_UP",
            "WHIP_PAN_DOWN", "SPIN", "SHAKE", "STRETCH", "WHITE_FLASH", "COLOR_FLASH", "LIGHT_LEAK",
            "LENS_FLARE", "BURN", "CIRCLE_WIPE", "LINE_WIPE", "ZIGZAG_WIPE", "GRID_WIPE", "DISSOLVE", "LUMA_MORPH",
        )
        assertEquals(expected, TransitionType.entries.map { it.name })
    }
}

class SubjectTest {
    @Test
    fun maskAndProgressReachRenderer() {
        val p = AutoCut.apply(
            Project(id = "p", name = "t", createdAt = 0, images = listOf("a", "b"),
                imageInfo = mapOf("a" to ImageInfo(10, 10, maskPath = "/m/a.png", maskTried = true))),
        )
        val r = FrameResolver(p)
        val s0 = r.at(0)!!.a
        assertEquals("/m/a.png", s0.maskPath)
        assertEquals(0f, s0.progress, 1e-3f)
        assertTrue(r.at(1500)!!.a.progress > 0.5f)
        val texts = p.copy(texts = listOf(TextOverlay("1", "Hinter", 0, 1000, TextStyle(behindSubject = true))))
        val o = OverlayResolver(texts, Timeline.resolve(texts))
        assertTrue(o.hasBehind(500))
        assertTrue(!o.hasBehind(1500))
    }
}
