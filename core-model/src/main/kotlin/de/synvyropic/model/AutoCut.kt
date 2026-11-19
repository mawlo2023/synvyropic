package de.synvyropic.model

import kotlin.math.roundToLong
import kotlin.random.Random

/** Wie die Videolänge bestimmt wird. */
@kotlinx.serialization.Serializable
enum class LengthMode {
    /** Jedes Bild genau einmal; Musik endet mit dem letzten Bild (Fade-Out). */
    FIT_IMAGES,
    /** Bilder wiederholen sich, bis der Musikausschnitt gefüllt ist. */
    FIT_MUSIC,
}

/**
 * Auto-Schnitt: verteilt Bilder auf das Beat-Raster und wählt Übergänge gemäß Stil.
 * Deterministisch für denselben [seed].
 */
object AutoCut {

    /** Baut die Clips eines Projekts neu auf (behält Musik/Einstellungen). */
    fun apply(project: Project): Project {
        val style = Styles.byId(project.styleId)
        val r = build(
            imageUris = project.images,
            style = style,
            music = project.music,
            beatsPerCut = project.beatsPerCut ?: style.beatsPerCut,
            lengthMode = project.lengthMode,
            seed = project.seed,
            imageInfo = project.imageInfo,
            format = project.format,
        )
        return project.copy(
            clips = r.clips,
            music = project.music?.copy(startMs = r.musicStartMs),
            color = project.color.copy(presetId = style.colorPresetId),
        )
    }

    data class Result(val clips: List<Clip>, val musicStartMs: Long)

    fun build(
        imageUris: List<String>,
        style: Style,
        music: MusicTrack?,
        beatsPerCut: Int = style.beatsPerCut,
        lengthMode: LengthMode = LengthMode.FIT_IMAGES,
        seed: Int = 1,
        idPrefix: String = "c",
        imageInfo: Map<String, ImageInfo> = emptyMap(),
        format: AspectFormat = AspectFormat.PORTRAIT_9_16,
    ): Result {
        require(beatsPerCut >= 1)
        if (imageUris.isEmpty()) return Result(emptyList(), music?.startMs ?: 0)
        val rnd = Random(seed)
        val framings = HashMap<Pair<String, Int>, Framing.Result>()
        fun frame(uri: String, i: Int) = framings.getOrPut(uri to i) {
            Framing.frame(imageInfo[uri], format, i, style.kenBurnsStrength, rnd)
        }
        val grid = music?.beats?.takeIf { it.beatsMs.size >= 2 && it.bpm > 0f }

        if (grid == null) {
            val clips = imageUris.mapIndexed { i, uri ->
                Clip(
                    id = "$idPrefix$i",
                    imageUri = uri,
                    durationBeats = beatsPerCut,
                    durationMsFallback = 2000,
                    motion = frame(uri, i).motion,
                    fit = frame(uri, i).fit,
                    transitionIn = if (i == 0) Transition(TransitionType.CUT, 0)
                                   else transition(pick(style.transitions, rnd), 500, style, rnd = rnd),
                    effect = style.clipEffect?.let { ClipEffect(it) },
                )
            }
            return Result(clips, music?.startMs ?: 0)
        }

        // Start auf die erste Takt-Eins ab gewähltem Startpunkt einrasten.
        val requestedStart = music.startMs
        val firstIdx = grid.downbeatIndices.firstOrNull { grid.beatsMs[it] >= requestedStart }
            ?: grid.beatsMs.indexOfFirst { it >= requestedStart }.takeIf { it >= 0 }
            ?: 0
        val startMs = grid.beatsMs[firstIdx]
        val endMs = music.endMs ?: Long.MAX_VALUE
        val beatMs = 60000f / grid.bpm
        val availableBeats = grid.beatsMs.count { it in startMs until endMs }

        val slots = when (lengthMode) {
            LengthMode.FIT_IMAGES -> imageUris.size
            LengthMode.FIT_MUSIC -> maxOf(imageUris.size.coerceAtMost(1), availableBeats / beatsPerCut)
        }
        val downbeats = grid.downbeatIndices.toHashSet()
        val drops = grid.dropIndices.toHashSet()

        val clips = (0 until slots).map { i ->
            val beatIdx = firstIdx + i * beatsPerCut
            val trans = when {
                i == 0 -> Transition(TransitionType.CUT, 0)
                beatIdx in drops -> transition(style.dropTransition, (beatMs * 1.5f).roundToLong(), style, 1f, rnd)
                beatIdx in downbeats -> transition(pick(style.downbeatTransitions, rnd), (beatMs * style.transitionBeatFraction).roundToLong(), style, rnd = rnd)
                else -> transition(pick(style.transitions, rnd), (beatMs * style.transitionBeatFraction).roundToLong(), style, rnd = rnd)
            }
            val uri = imageUris[i % imageUris.size]
            val framed = frame(uri, i)
            Clip(
                id = "$idPrefix$i",
                imageUri = uri,
                durationBeats = beatsPerCut,
                durationMsFallback = (beatMs * beatsPerCut).roundToLong(),
                motion = framed.motion,
                fit = framed.fit,
                transitionIn = trans,
                effect = style.clipEffect?.let { ClipEffect(it) },
            )
        }
        return Result(clips, startMs)
    }

    /** RGB-Akzentfarben für Farb-Flashes. */
    private val flashColors = longArrayOf(0xFFFF2D55, 0xFF2DFFB4, 0xFF2D7DFF, 0xFFFFD60A, 0xFFB44DFF)

    private fun transition(type: TransitionType, durationMs: Long, style: Style, intensity: Float = 0.8f, rnd: Random = Random(type.ordinal)) =
        Transition(
            type = type,
            durationMs = if (type == TransitionType.CUT) 0 else durationMs.coerceIn(120, 1500),
            intensity = intensity,
            easing = if (style.beatReactive) Easing.EXPO_OUT else Easing.EASE_IN_OUT,
            beatReactive = style.beatReactive,
            angleDeg = if (type == TransitionType.LINE_WIPE || type == TransitionType.ZIGZAG_WIPE) 45f * rnd.nextInt(8) else 0f,
            colorArgb = if (type == TransitionType.COLOR_FLASH) flashColors[rnd.nextInt(flashColors.size)] else 0xFFFFFFFF,
        )

    private fun <T> pick(weighted: List<Pair<T, Int>>, rnd: Random): T {
        val total = weighted.sumOf { it.second }
        var r = rnd.nextInt(total)
        for ((v, w) in weighted) { if (r < w) return v; r -= w }
        return weighted.last().first
    }

}

/** Ein Clip mit aufgelösten Zeiten (relativ zum Videostart). */
data class TimedClip(val clip: Clip, val startMs: Long, val endMs: Long)

object Timeline {
    /**
     * Rechnet Beat-Dauern in Millisekunden um. Video-t = 0 entspricht music.startMs.
     * Über das Ende der erkannten Beats hinaus wird mit dem Tempo extrapoliert.
     */
    fun resolve(project: Project): List<TimedClip> {
        val music = project.music
        val grid = music?.beats?.takeIf { it.beatsMs.size >= 2 && it.bpm > 0f }
        val out = ArrayList<TimedClip>(project.clips.size)
        if (grid == null) {
            var t = 0L
            for (c in project.clips) { out.add(TimedClip(c, t, t + c.durationMsFallback)); t += c.durationMsFallback }
            return out
        }
        val beatMs = 60000.0 / grid.bpm
        val first = grid.beatsMs.indexOfFirst { it >= music.startMs }.let { if (it < 0) grid.beatsMs.lastIndex else it }
        fun timeOf(idx: Int): Long =
            if (idx < grid.beatsMs.size) grid.beatsMs[idx]
            else grid.beatsMs.last() + ((idx - grid.beatsMs.lastIndex) * beatMs).roundToLong()
        var idx = first
        val origin = timeOf(first)
        for (c in project.clips) {
            val next = idx + c.durationBeats
            out.add(TimedClip(c, timeOf(idx) - origin, timeOf(next) - origin))
            idx = next
        }
        return out
    }

    fun durationMs(project: Project): Long = resolve(project).lastOrNull()?.endMs ?: 0L
}
