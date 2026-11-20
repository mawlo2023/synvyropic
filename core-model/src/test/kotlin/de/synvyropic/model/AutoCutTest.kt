package de.synvyropic.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCutTest {
    /** 120 BPM, erster Beat bei 100 ms, Takt-Eins jeder 4. Beat, Drop bei Beat 32. */
    private val grid = BeatGrid(
        bpm = 120f,
        beatsMs = (0 until 120).map { 100L + it * 500L },
        strengths = List(120) { if (it % 4 == 0) 1f else 0.5f },
        downbeatIndices = (0 until 120 step 4).toList(),
        dropIndices = listOf(32),
    )
    private val images = (1..10).map { "img$it" }

    private fun project(style: Style, mode: LengthMode = LengthMode.FIT_IMAGES, start: Long = 0, end: Long? = null) =
        Project(
            id = "p", name = "t", createdAt = 0, styleId = style.id, images = images, lengthMode = mode,
            music = MusicTrack(uri = "m", startMs = start, endMs = end, beats = grid),
        )

    @Test
    fun cutsLandExactlyOnBeats() {
        val p = AutoCut.apply(project(Styles.GLITCH_ENERGY))
        val timed = Timeline.resolve(p)
        assertEquals(10, timed.size)
        timed.forEach { assertEquals(0L, it.startMs % 1000) } // 2 Beats à 500 ms
        assertEquals(10_000L, Timeline.durationMs(p))
    }

    @Test
    fun startSnapsToNextDownbeat() {
        val p = AutoCut.apply(project(Styles.FLASH_PARTY, start = 1300))
        // Beats: 100, 600, 1100, 1600 ... Takt-Einsen: 100, 2100, ... → 2100
        assertEquals(2100L, p.music!!.startMs)
    }

    @Test
    fun dropGetsDropTransition() {
        // Flash Party: 1 Beat pro Schnitt → Clip 32 beginnt auf Drop-Beat 32
        val p = AutoCut.apply(project(Styles.FLASH_PARTY, mode = LengthMode.FIT_MUSIC))
        assertEquals(Styles.FLASH_PARTY.dropTransition, p.clips[32].transitionIn.type)
        assertEquals(1f, p.clips[32].transitionIn.intensity)
    }

    @Test
    fun fitMusicRepeatsImagesToFillRange() {
        val p = AutoCut.apply(project(Styles.SMOOTH_CINEMATIC, mode = LengthMode.FIT_MUSIC, end = 30_100))
        // Beats in [100, 30100) = 60 → 4 Beats pro Schnitt = 15 Clips
        assertEquals(15, p.clips.size)
        assertEquals("img1", p.clips[10].imageUri)
    }

    @Test
    fun firstClipHasNoTransitionAndIsDeterministic() {
        val a = AutoCut.apply(project(Styles.RETRO_VHS))
        val b = AutoCut.apply(project(Styles.RETRO_VHS))
        assertEquals(TransitionType.CUT, a.clips[0].transitionIn.type)
        assertEquals(a, b)
    }

    @Test
    fun withoutMusicUsesFallbackDurations() {
        val p = AutoCut.apply(Project(id = "p", name = "t", createdAt = 0, images = images))
        assertEquals(20_000L, Timeline.durationMs(p))
        assertTrue(p.clips.drop(1).all { it.transitionIn.durationMs > 0 || it.transitionIn.type == TransitionType.CUT })
    }

    @Test
    fun timelineExtrapolatesPastLastBeat() {
        val short = grid.copy(beatsMs = grid.beatsMs.take(4), strengths = grid.strengths.take(4), downbeatIndices = listOf(0), dropIndices = emptyList())
        val p = AutoCut.apply(project(Styles.GLITCH_ENERGY).let { it.copy(music = it.music!!.copy(beats = short)) })
        assertEquals(10_000L, Timeline.durationMs(p))
    }
}

class OverrideAndEasingTest {
    private val grid = BeatGrid(
        bpm = 120f, beatsMs = (0 until 60).map { it * 500L }, strengths = List(60) { 1f },
        downbeatIndices = (0 until 60 step 4).toList(),
    )

    @Test
    fun manualTransitionSurvivesRecut() {
        val manual = Transition(TransitionType.CIRCLE_WIPE, 700)
        val base = Project(
            id = "p", name = "t", createdAt = 0, images = (1..5).map { "i$it" },
            music = MusicTrack("m", beats = grid), transitionOverrides = mapOf(2 to manual, 0 to manual),
        )
        val a = AutoCut.apply(base)
        assertEquals(manual, a.clips[2].transitionIn)
        assertEquals(TransitionType.CUT, a.clips[0].transitionIn.type) // Clip 0 nie überschrieben
        // Neuer Stil + neu würfeln → Override bleibt
        val b = AutoCut.apply(a.copy(styleId = Styles.FLASH_PARTY.id, seed = 7))
        assertEquals(manual, b.clips[2].transitionIn)
    }

    @Test
    fun easingEndpointsAndMonotonic() {
        for (e in Easing.entries.filter { it != Easing.BACK_OUT }) {
            assertEquals(0f, e.apply(0f), 1e-3f)
            assertEquals(1f, e.apply(1f), 1e-3f)
            var last = -1f
            for (i in 0..20) { val v = e.apply(i / 20f); assertTrue("$e nicht monoton", v >= last - 1e-4f); last = v }
        }
        assertEquals(0.5f, Easing.EASE_IN_OUT.apply(0.5f), 1e-3f) // Mitte bleibt auf dem Beat
    }
}

class DynamicCutTest {
    /** 120 BPM; Beats 16–31 sind ein ruhiger Break. */
    private val grid = BeatGrid(
        bpm = 120f, beatsMs = (0 until 64).map { it * 500L }, strengths = List(64) { 1f },
        downbeatIndices = (0 until 64 step 4).toList(),
        energies = List(64) { if (it in 16..31) 0.2f else 0.95f }, version = 2,
    )
    private fun project(dynamic: Boolean) = Project(
        id = "p", name = "t", createdAt = 0, images = (1..12).map { "i$it" }, styleId = Styles.FLASH_PARTY.id,
        music = MusicTrack("m", beats = grid), dynamicCut = dynamic,
    )

    @Test
    fun breakHoldsImagesLongerWithSoftTransitions() {
        val p = AutoCut.apply(project(true))
        val timed = Timeline.resolve(p)
        val inBreak = timed.filter { it.startMs in 8000 until 16000 }
        val loud = timed.filter { it.startMs < 8000 }
        assertTrue("im Break länger halten", inBreak.all { it.clip.durationBeats >= 4 })
        assertTrue("laut: Stil-Raster (1 Beat)", loud.all { it.clip.durationBeats == 1 })
        val soft = setOf(TransitionType.CROSSFADE, TransitionType.DISSOLVE, TransitionType.LIGHT_LEAK, TransitionType.LUMA_MORPH)
        assertTrue(inBreak.filter { it.startMs > 8000 }.all { it.clip.transitionIn.type in soft })
        // Jeder Schnitt bleibt exakt auf einem Beat
        timed.forEach { assertEquals(0L, it.startMs % 500) }
    }

    @Test
    fun offKeepsFixedGrid() {
        val p = AutoCut.apply(project(false))
        assertTrue(p.clips.all { it.durationBeats == 1 })
    }
}
