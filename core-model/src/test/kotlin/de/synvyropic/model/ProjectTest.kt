package de.synvyropic.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectTest {
    @Test
    fun jsonRoundTrip() {
        val project = Project(
            id = "p1",
            name = "Urlaub",
            createdAt = 1_700_000_000_000,
            clips = listOf(
                Clip(id = "c1", imageUri = "content://a", transitionIn = Transition(TransitionType.RGB_SPLIT)),
            ),
            watermark = Watermark(text = "@meinkanal"),
            intro = TitleCard(title = "Sommer 2026"),
        )
        assertEquals(project, Project.fromJson(project.toJson()))
    }

    @Test
    fun everyTransitionHasAFamily() {
        TransitionType.entries.forEach { assert(it.family in TransitionFamily.entries) }
    }

    @Test
    fun uhdDoublesResolution() {
        assertEquals(2160 to 3840, AspectFormat.PORTRAIT_9_16.size(Resolution.UHD_4K))
    }
}
