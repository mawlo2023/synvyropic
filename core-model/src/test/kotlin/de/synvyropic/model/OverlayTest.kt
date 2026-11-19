package de.synvyropic.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTest {
    private val images = listOf("a", "b", "c")
    private fun project(f: (Project) -> Project = { it }): Project {
        val base = Project(id = "p", name = "t", createdAt = 0, images = images) // ohne Musik: 3 × 2000 ms
        return AutoCut.apply(f(base))
    }
    private fun resolver(p: Project) = OverlayResolver(p, Timeline.resolve(p))

    @Test
    fun introAndOutroSitOnFirstAndLastImage() {
        val p = project { it.copy(intro = TitleCard("Sommer", "2026", durationMs = 2500), outro = TitleCard("Ende", durationMs = 1500)) }
        val r = resolver(p)
        assertEquals(listOf("Sommer", "2026", "Ende"), r.items.map { it.text })
        val intro = r.items.first()
        assertEquals(0L, intro.startMs); assertEquals(2500L, intro.endMs)
        val outro = r.items.last()
        assertEquals(4500L, outro.startMs); assertEquals(6000L, outro.endMs)
        assertTrue(r.dimAt(1000) > 0.3f)
        assertEquals(0f, r.dimAt(3500), 1e-4f)
    }

    @Test
    fun subtitleSitsBelowTitle() {
        val r = resolver(project { it.copy(intro = TitleCard("Titel", "Untertitel")) })
        val (t, s) = r.items
        assertTrue(s.style.y > t.style.y)
        assertTrue(s.style.size < t.style.size)
    }

    @Test
    fun captionFollowsImage() {
        val p = project { it.copy(imageInfo = mapOf("b" to ImageInfo(100, 100, caption = "Rom"))) }
        val r = resolver(p)
        val cap = r.items.single()
        assertEquals("Rom", cap.text)
        assertEquals(2000L, cap.startMs); assertEquals(4000L, cap.endMs)
        assertTrue(r.at(1999).isEmpty())
        assertEquals(1, r.at(2500).size)
    }

    @Test
    fun freeTextVisibleOnlyInWindow() {
        val p = project { it.copy(texts = listOf(TextOverlay("1", "Hallo", 1000, 3000))) }
        val r = resolver(p)
        assertTrue(r.at(999).isEmpty())
        assertEquals(1, r.at(2000).size)
        assertTrue(r.at(3000).isEmpty())
    }

    @Test
    fun animationsRampInAndOut() {
        val item = OverlayItem("k", "x", TextStyle(animation = TextAnimation.FADE), 0, 3000, OverlayKind.TEXT)
        val r = resolver(project())
        assertEquals(0f, r.animate(item, 0).alpha, 1e-3f)
        assertEquals(1f, r.animate(item, 1500).alpha, 1e-3f)
        assertTrue(r.animate(item, 2900).alpha < 0.5f)
        val tw = r.animate(item.copy(style = TextStyle(animation = TextAnimation.TYPEWRITER)), 600)
        assertEquals(0.5f, tw.reveal, 1e-3f)
        val pop = r.animate(item.copy(style = TextStyle(animation = TextAnimation.POP)), 50)
        assertTrue(pop.scale < 1f)
    }

    @Test
    fun beatSyncPulsesScale() {
        val item = OverlayItem("k", "x", TextStyle(animation = TextAnimation.NONE, syncToBeat = true), 0, 3000, OverlayKind.TEXT)
        val p = project()
        val r = OverlayResolver(p, Timeline.resolve(p)) { 1f }
        assertEquals(1.08f, r.animate(item, 1000).scale, 1e-3f)
    }
}
