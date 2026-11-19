package de.synvyropic.model

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

enum class OverlayKind { INTRO, OUTRO, CAPTION, TEXT }

/** Ein Text mit Zeitfenster (aufgelöst aus Intro/Outro, Untertiteln und freien Texten). */
data class OverlayItem(
    val key: String,
    val text: String,
    val style: TextStyle,
    val startMs: Long,
    val endMs: Long,
    val kind: OverlayKind,
)

/** Animationszustand eines Textes in einem Frame. Offsets normiert (Anteil von Breite/Höhe). */
data class OverlayFrame(
    val item: OverlayItem,
    val alpha: Float,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** 0..1 – sichtbarer Anteil von links (Schreibmaschine). */
    val reveal: Float = 1f,
    /** 0..1 – Glitch-Stärke. */
    val glitch: Float = 0f,
)

/** Bestimmt, welche Texte zu welcher Zeit wie zu sehen sind. Reine Logik. */
class OverlayResolver(project: Project, timed: List<TimedClip>, private val envelope: (Long) -> Float = { 0f }) {

    val items: List<OverlayItem>
    private val intro = project.intro
    private val outro = project.outro
    private val duration = timed.lastOrNull()?.endMs ?: 0L

    init {
        val list = ArrayList<OverlayItem>()
        project.intro?.let { c ->
            val end = min(c.durationMs, duration)
            list += titleItems("intro", c, 0, end, OverlayKind.INTRO)
        }
        project.outro?.let { c ->
            val start = (duration - c.durationMs).coerceAtLeast(0)
            list += titleItems("outro", c, start, duration, OverlayKind.OUTRO)
        }
        // Untertitel: für jeden Clip, dessen Bild einen Untertitel hat (direkt aufeinanderfolgende zusammengefasst)
        var i = 0
        while (i < timed.size) {
            val uri = timed[i].clip.imageUri
            val caption = project.imageInfo[uri]?.caption?.takeIf { it.isNotBlank() }
            var j = i
            while (j + 1 < timed.size && timed[j + 1].clip.imageUri == uri) j++
            if (caption != null) {
                list += OverlayItem("cap$i:$caption", caption, project.captionStyle, timed[i].startMs, timed[j].endMs, OverlayKind.CAPTION)
            }
            i = j + 1
        }
        project.texts.forEach { t ->
            if (t.text.isNotBlank() && t.endMs > t.startMs) list += OverlayItem("txt${t.id}", t.text, t.style, t.startMs, t.endMs, OverlayKind.TEXT)
        }
        items = list
    }

    private fun titleItems(prefix: String, c: TitleCard, start: Long, end: Long, kind: OverlayKind): List<OverlayItem> {
        if (end <= start) return emptyList()
        val out = ArrayList<OverlayItem>()
        if (c.title.isNotBlank()) out += OverlayItem("$prefix-t", c.title, c.style, start, end, kind)
        c.subtitle?.takeIf { it.isNotBlank() }?.let {
            val sub = c.style.copy(
                size = c.style.size * 0.42f,
                font = TextFont.SANS,
                y = c.style.y + c.style.size / 1920f * 1.15f + 0.03f,
            )
            out += OverlayItem("$prefix-s", it, sub, (start + 200).coerceAtMost(end), end, kind)
        }
        return out
    }

    /** Abdunkelung des Bildes für Intro/Outro (weich ein- und ausgeblendet). */
    fun dimAt(t: Long): Float {
        var d = 0f
        intro?.let { c -> d = maxOf(d, c.dim * window(t, 0, min(c.durationMs, duration), 400)) }
        outro?.let { c -> d = maxOf(d, c.dim * window(t, (duration - c.durationMs).coerceAtLeast(0), duration + 1, 400)) }
        return d
    }

    fun at(t: Long): List<OverlayFrame> = items.filter { t >= it.startMs && t < it.endMs }.map { animate(it, t) }

    fun animate(item: OverlayItem, t: Long): OverlayFrame {
        val len = item.endMs - item.startMs
        val dur = min(450L, len / 3).coerceAtLeast(1)
        val local = t - item.startMs
        val pin = (local.toFloat() / dur).coerceIn(0f, 1f)
        val pout = ((item.endMs - t).toFloat() / dur).coerceIn(0f, 1f)
        val v = min(pin, pout)
        val beat = if (item.style.syncToBeat) 1f + 0.08f * envelope(t) else 1f
        val f = when (item.style.animation) {
            TextAnimation.NONE -> OverlayFrame(item, 1f)
            TextAnimation.FADE -> OverlayFrame(item, v)
            TextAnimation.SLIDE_UP -> OverlayFrame(item, v, offsetY = (1f - Easing.EASE_OUT.apply(v)) * 0.04f)
            TextAnimation.POP -> {
                val s = if (pin < 1f) 0.5f + 0.5f * Easing.BACK_OUT.apply(pin) else 0.85f + 0.15f * pout
                OverlayFrame(item, min(1f, v * 2f), scale = s)
            }
            TextAnimation.TYPEWRITER -> {
                val typing = min(1200L, len / 2).coerceAtLeast(1)
                OverlayFrame(item, pout, reveal = (local.toFloat() / typing).coerceIn(0f, 1f))
            }
            TextAnimation.GLITCH -> {
                val g = 1f - v
                // Flackern beim Ein-/Ausblenden
                val flicker = if (g > 0.05f && abs(sin(t / 37.0)) < g * 0.6) 0.25f else 1f
                OverlayFrame(item, min(1f, v * 3f) * flicker, offsetX = (sin(t / 23.0).toFloat()) * 0.01f * g, glitch = g)
            }
        }
        return if (beat != 1f) f.copy(scale = f.scale * beat) else f
    }

    /** 0 außerhalb, weiche Rampe an beiden Enden. */
    private fun window(t: Long, start: Long, end: Long, ramp: Long): Float {
        if (t < start || t >= end) return 0f
        val r = min(ramp, (end - start) / 2).coerceAtLeast(1)
        return min((t - start).toFloat() / r, (end - t).toFloat() / r).coerceIn(0f, 1f)
    }
}
