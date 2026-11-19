package de.synvyropic.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedCurveTest {
    private val none = FloatArray(0)

    @Test
    fun normalSpeedMapsOneToOne() {
        val t = SpeedCurve.Table(SpeedRamp.NONE, 2000, none)
        assertEquals(1000f, t.at(1000), 1f)
        assertEquals(1500L, t.sourceMs(1000, 500, 10_000))
    }

    @Test
    fun slowMoUsesHalfTheSource() {
        val t = SpeedCurve.Table(SpeedRamp.SLOW_MO, 2000, none)
        assertEquals(1000f, t.total, 2f)
        assertEquals(500.0, t.sourceMs(1000, 0, 10_000).toDouble(), 2.0)
    }

    @Test
    fun neverRunsPastAvailableSource() {
        val t = SpeedCurve.Table(SpeedRamp.FAST, 4000, none) // bräuchte 8 s
        assertEquals(3000L, t.sourceMs(4000, 0, 3000))        // nur 3 s da → gestaucht
        assertTrue(t.sourceMs(2000, 0, 3000) in 1400..1600)
    }

    @Test
    fun rampIsMonotonicAndSlowThenFast() {
        val t = SpeedCurve.Table(SpeedRamp.SLOW_TO_FAST, 2000, none)
        var last = -1f
        for (ms in 0..2000 step 50) { val v = t.at(ms.toLong()); assertTrue(v >= last); last = v }
        val firstHalf = t.at(1000)
        assertTrue("erste Hälfte langsamer", firstHalf < t.total - firstHalf)
    }

    @Test
    fun beatVelocityBurstsOnBeats() {
        val beats = floatArrayOf(0f, 500f, 1000f, 1500f)
        assertTrue(SpeedCurve.speed(SpeedRamp.BEAT_VELOCITY, 510f, 2000f, beats) > 2.5f)
        assertTrue(SpeedCurve.speed(SpeedRamp.BEAT_VELOCITY, 950f, 2000f, beats) < 0.5f)
    }
}

class VideoCutTest {
    private val grid = BeatGrid(120f, (0 until 80).map { it * 500L }, List(80) { 1f }, (0 until 80 step 4).toList())

    @Test
    fun videoGetsBeatsForItsLengthAndSourceTimeAdvances() {
        val p = AutoCut.apply(
            Project(
                id = "p", name = "t", createdAt = 0, images = listOf("foto", "clip"),
                imageInfo = mapOf("clip" to ImageInfo(1080, 1920, video = VideoInfo(10_000, 1080, 1920, trimStartMs = 1000, trimEndMs = 4100))),
                music = MusicTrack("m", beats = grid),
            ),
        )
        // Raster 2 Beats (1 s); Ausschnitt 3,1 s → 3 s = 6 Beats
        assertEquals(2, p.clips[0].durationBeats)
        assertEquals(6, p.clips[1].durationBeats)
        val r = FrameResolver(p)
        assertEquals(null, r.at(500)!!.a.videoMs)
        // außerhalb des Übergangs um 1000 ms messen: Quellzeit = Ausschnittstart + vergangene Zeit
        val start = r.at(1200)!!.a.videoMs!!
        val later = r.at(2500)!!.a.videoMs!!
        assertEquals(1200.0, start.toDouble(), 20.0)
        assertEquals(2500.0, later.toDouble(), 30.0)
    }

    @Test
    fun photoRampChangesZoomProgress() {
        val base = Project(id = "p", name = "t", createdAt = 0, images = listOf("a"), music = MusicTrack("m", beats = grid))
        val lin = FrameResolver(AutoCut.apply(base)).at(500)!!.a.progress
        val ramped = FrameResolver(AutoCut.apply(base.copy(imageInfo = mapOf("a" to ImageInfo(10, 10, ramp = SpeedRamp.SLOW_TO_FAST))))).at(500)!!.a.progress
        assertTrue(ramped < lin)
    }
}
