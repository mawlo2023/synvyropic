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
        // Beste Bilder auf den Anfang und die Drops legen (nur mit Beat-Analyse sinnvoll)
        val images = if (project.bestOnHighlights) {
            val bpc = project.beatsPerCut ?: style.beatsPerCut
            val grid = project.music?.beats
            val first = grid?.beatsMs?.indexOfFirst { it >= (project.music?.startMs ?: 0) }?.coerceAtLeast(0) ?: 0
            val dropSlots = grid?.dropIndices?.map { (it - first) / bpc }?.filter { it > 0 }.orEmpty()
            ImageRanking.placeBest(project.images, project.imageInfo, listOf(0) + dropSlots)
        } else project.images
        val r = build(
            imageUris = images,
            style = style,
            music = project.music,
            beatsPerCut = project.beatsPerCut ?: style.beatsPerCut,
            lengthMode = project.lengthMode,
            seed = project.seed,
            imageInfo = project.imageInfo,
            format = project.format,
            dynamic = project.dynamicCut,
        )
        // Von Hand gewählte Übergänge übernehmen (nicht für Clip 0 – der hat keinen Übergang)
        val clips = r.clips.mapIndexed { i, c ->
            project.transitionOverrides[i]?.takeIf { i > 0 }?.let { c.copy(transitionIn = it) } ?: c
        }
        // Bildkorrekturen in die Clips übernehmen (überstehen so jeden Neuschnitt)
        val colored = clips.map { c -> project.imageInfo[c.imageUri]?.color?.let { c.copy(color = it) } ?: c }
        return project.copy(
            clips = colored,
            music = project.music?.copy(startMs = r.musicStartMs),
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
        dynamic: Boolean = false,
    ): Result {
        require(beatsPerCut >= 1)
        if (imageUris.isEmpty()) return Result(emptyList(), music?.startMs ?: 0)
        val rnd = Random(seed)
        val framings = HashMap<Pair<String, Int>, Framing.Result>()
        fun frame(uri: String, i: Int) = framings.getOrPut(uri to i) {
            val info = imageInfo[uri]
            val f = Framing.frame(info, format, i, style.kenBurnsStrength, rnd)
            // Videos bewegen sich selbst: kein Ken-Burns-Zoom, nur Ausrichtung auf die Gesichter
            if (info?.video != null) f.copy(motion = f.motion.copy(
                startScale = 1f, endScale = 1f,
                startCenterX = (f.motion.startCenterX + f.motion.endCenterX) / 2, endCenterX = (f.motion.startCenterX + f.motion.endCenterX) / 2,
                startCenterY = (f.motion.startCenterY + f.motion.endCenterY) / 2, endCenterY = (f.motion.startCenterY + f.motion.endCenterY) / 2,
            )) else f
        }
        /** Länge eines Videos in Beats: so viel, wie in den Ausschnitt passt (1–4 Raster). */
        fun videoBeats(uri: String, beatMs: Float): Int {
            val info = imageInfo[uri] ?: return beatsPerCut
            val v = info.video ?: return beatsPerCut
            val ms = v.trimmedMs / SpeedCurve.meanSpeed(info.ramp)
            return ((ms / beatMs / beatsPerCut).toInt() * beatsPerCut).coerceIn(beatsPerCut, beatsPerCut * 4)
        }
        val grid = music?.beats?.takeIf { it.beatsMs.size >= 2 && it.bpm > 0f }

        if (grid == null) {
            val clips = imageUris.mapIndexed { i, uri ->
                Clip(
                    id = "$idPrefix$i",
                    imageUri = uri,
                    durationBeats = beatsPerCut,
                    durationMsFallback = imageInfo[uri]?.video?.let { v ->
                        (v.trimmedMs / SpeedCurve.meanSpeed(imageInfo[uri]!!.ramp)).toLong().coerceIn(1000, 6000)
                    } ?: 2000,
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

        // Dynamischer Schnitt: mittlere Lautheit der kommenden Beats bestimmt die Haltedauer
        val energies = grid.energies.takeIf { dynamic && it.size == grid.beatsMs.size }
        fun energyAt(beat: Int, count: Int): Float? = energies?.let { e ->
            val a = beat.coerceIn(0, e.lastIndex)
            val b = (beat + count).coerceIn(a + 1, e.size)
            e.subList(a, b).average().toFloat()
        }
        fun holdBeats(beat: Int): Int {
            val e = energyAt(beat, beatsPerCut * 2) ?: return beatsPerCut
            val factor = when {
                e < 0.35f -> 4   // Break / sehr ruhig: lange stehen lassen
                e < 0.6f -> 2    // ruhiger Teil
                else -> 1
            }
            return (beatsPerCut * factor).coerceAtMost(maxOf(beatsPerCut, 8))
        }
        // Plätze bestimmen: Videos belegen ggf. mehrere Raster, ruhige Stellen werden länger gehalten
        val clipBeats = ArrayList<Int>()
        run {
            var used = 0
            var i = 0
            while (true) {
                if (lengthMode == LengthMode.FIT_IMAGES && i >= imageUris.size) break
                val uri = imageUris[i % imageUris.size]
                val b = if (imageInfo[uri]?.video != null) videoBeats(uri, beatMs) else holdBeats(firstIdx + used)
                if (lengthMode == LengthMode.FIT_MUSIC && i > 0 && used + b > availableBeats) break
                clipBeats += b; used += b; i++
                if (i > 2000) break
            }
        }
        val downbeats = grid.downbeatIndices.toHashSet()
        val drops = grid.dropIndices.toHashSet()
        val startBeat = IntArray(clipBeats.size).also { arr -> var acc = 0; for (k in arr.indices) { arr[k] = acc; acc += clipBeats[k] } }

        val clips = clipBeats.indices.map { i ->
            val beatIdx = firstIdx + startBeat[i]
            val calm = (energyAt(beatIdx, clipBeats[i]) ?: 1f) < 0.5f
            val trans = when {
                i == 0 -> Transition(TransitionType.CUT, 0)
                // ruhige Passage: weiche, längere Blende statt harter Effekte
                calm && beatIdx !in drops -> transition(pick(CALM_TRANSITIONS, rnd), beatMs.roundToLong(), style, 0.7f, rnd)
                beatIdx in drops -> transition(style.dropTransition, (beatMs * 1.5f).roundToLong(), style, 1f, rnd)
                beatIdx in downbeats -> transition(pick(style.downbeatTransitions, rnd), (beatMs * style.transitionBeatFraction).roundToLong(), style, rnd = rnd)
                else -> transition(pick(style.transitions, rnd), (beatMs * style.transitionBeatFraction).roundToLong(), style, rnd = rnd)
            }
            val uri = imageUris[i % imageUris.size]
            val framed = frame(uri, i)
            Clip(
                id = "$idPrefix$i",
                imageUri = uri,
                durationBeats = clipBeats[i],
                durationMsFallback = (beatMs * clipBeats[i]).roundToLong(),
                motion = framed.motion,
                fit = framed.fit,
                transitionIn = trans,
                effect = style.clipEffect?.let { ClipEffect(it) },
            )
        }
        return Result(clips, startMs)
    }

    private val CALM_TRANSITIONS = listOf(TransitionType.CROSSFADE to 3, TransitionType.DISSOLVE to 2, TransitionType.LIGHT_LEAK to 2, TransitionType.LUMA_MORPH to 1)

    /** RGB-Akzentfarben für Farb-Flashes. */
    private val flashColors = longArrayOf(0xFF27D3FF, 0xFF3D7BFF, 0xFF9B4DFF, 0xFFE445E6, 0xFFFF8A3D)

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
