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
