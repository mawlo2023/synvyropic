package de.synvyropic.model

import kotlin.math.exp

/** Zustand eines Bildes in einem Frame: welcher Clip, mit welchem Ken-Burns-Ausschnitt. */
data class ClipState(
    val clipIndex: Int,
    val imageUri: String,
    val scale: Float,
    val centerX: Float,
    val centerY: Float,
    val fit: FitMode = FitMode.FILL,
)

/**
 * Alles, was der Renderer für einen Frame wissen muss.
 * Übergänge sind auf den Schnitt-Beat zentriert: bei [progress] = 0.5 liegt der Beat.
 */
data class FrameSpec(
    val a: ClipState,
    val b: ClipState?,
    val transition: Transition?,
    val progress: Float,
    /** Beat-Hüllkurve 0..1: springt auf jedem Beat hoch und klingt ab. */
    val beatEnvelope: Float,
    val effect: ClipEffect?,
)

/** Übersetzt Projekt + Zeit in eine [FrameSpec]. Reine Logik, unabhängig von OpenGL. */
class FrameResolver(project: Project, private val beatDecayMs: Float = 140f) {

    val timed: List<TimedClip> = Timeline.resolve(project)
    val durationMs: Long = timed.lastOrNull()?.endMs ?: 0L

    private val beatTimes: LongArray
    private val beatStrengths: FloatArray

    init {
        val grid = project.music?.beats
        val start = project.music?.startMs ?: 0L
        if (grid == null || grid.beatsMs.isEmpty()) {
            beatTimes = LongArray(0)
            beatStrengths = FloatArray(0)
        } else {
            val idx = grid.beatsMs.indices.filter { grid.beatsMs[it] >= start }
            beatTimes = LongArray(idx.size) { grid.beatsMs[idx[it]] - start }
            beatStrengths = FloatArray(idx.size) { grid.strengths.getOrElse(idx[it]) { 1f } }
        }
    }

    fun at(tMs: Long): FrameSpec? {
        if (timed.isEmpty()) return null
        val t = tMs.coerceIn(0, maxOf(0, durationMs - 1))
        val i = indexAt(t)

        // Übergang in den nächsten Clip (beginnt eine halbe Übergangsdauer vor dem Schnitt)?
        if (i + 1 < timed.size) {
            val next = timed[i + 1]
            val d = activeDuration(next.clip)
            if (d > 0 && t >= next.startMs - d / 2) return transitionFrame(i, i + 1, t, d)
        }
        // Übergang in den aktuellen Clip (endet eine halbe Dauer nach dem Schnitt)?
        if (i > 0) {
            val cur = timed[i]
            val d = activeDuration(cur.clip)
            if (d > 0 && t < cur.startMs + d / 2) return transitionFrame(i - 1, i, t, d)
        }
        return FrameSpec(state(i, t), null, null, 0f, envelope(t), timed[i].clip.effect)
    }

    private fun transitionFrame(ai: Int, bi: Int, t: Long, d: Long): FrameSpec {
        val cut = timed[bi].startMs
        val linear = ((t - (cut - d / 2)).toFloat() / d).coerceIn(0f, 1f)
        val tr = timed[bi].clip.transitionIn
        // Fortschreitende Übergänge weich beschleunigen; Höhepunkt-Übergänge bleiben linear (Peak auf dem Beat)
        val p = if (tr.type.isProgressive) Easing.EASE_IN_OUT.apply(linear) else linear
        val effect = if (linear < 0.5f) timed[ai].clip.effect else timed[bi].clip.effect
        return FrameSpec(state(ai, t), state(bi, t), tr, p, envelope(t), effect)
    }

    private fun activeDuration(clip: Clip): Long =
        if (clip.transitionIn.type == TransitionType.CUT) 0 else clip.transitionIn.durationMs

    fun indexAt(t: Long): Int {
        var lo = 0
        var hi = timed.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (timed[mid].startMs <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Ken Burns läuft über die gesamte Sichtbarkeit inkl. der halben Übergänge an beiden Enden. */
    private fun state(k: Int, t: Long): ClipState {
        val tc = timed[k]
        val m = tc.clip.motion
        if (!m.enabled) return ClipState(k, tc.clip.imageUri, 1f, 0.5f, 0.5f, tc.clip.fit)
        val dIn = if (k > 0) activeDuration(tc.clip) / 2 else 0
        val dOut = if (k + 1 < timed.size) activeDuration(timed[k + 1].clip) / 2 else 0
        val s = tc.startMs - dIn
        val e = tc.endMs + dOut
        val u = if (e > s) ((t - s).toFloat() / (e - s)).coerceIn(0f, 1f) else 0f
        return ClipState(
            clipIndex = k,
            imageUri = tc.clip.imageUri,
            scale = lerp(m.startScale, m.endScale, u),
            centerX = lerp(m.startCenterX, m.endCenterX, u),
            centerY = lerp(m.startCenterY, m.endCenterY, u),
            fit = tc.clip.fit,
        )
    }

    fun envelope(t: Long): Float {
        if (beatTimes.isEmpty()) return 0f
        var lo = 0
        var hi = beatTimes.lastIndex
        if (beatTimes[0] > t) return 0f
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (beatTimes[mid] <= t) lo = mid else hi = mid - 1
        }
        val dt = (t - beatTimes[lo]).toFloat()
        return beatStrengths[lo] * exp(-dt / beatDecayMs)
    }

    /** Clip-Indizes, die um Zeitpunkt t gebraucht werden (zum Vorladen von Texturen). */
    fun neededAround(t: Long, ahead: Int = 2): List<Int> {
        if (timed.isEmpty()) return emptyList()
        val i = indexAt(t.coerceAtLeast(0))
        return ((i - 1)..(i + ahead)).filter { it in timed.indices }
    }

    private fun lerp(a: Float, b: Float, u: Float) = a + (b - a) * u
}
