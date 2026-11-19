package de.synvyropic.audio

import de.synvyropic.model.BeatGrid
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Beat-Erkennung in reinem Kotlin.
 *
 * Ablauf:
 * 1. STFT → log-komprimiertes Betragsspektrum
 * 2. Spectral Flux (gesamt + Bassband) → Onset-Hüllkurve
 * 3. Tempo über gewichtete Autokorrelation der Hüllkurve
 * 4. Beat-Tracking per dynamischer Programmierung (Ellis 2007)
 * 5. Betonung je Beat, Takt-Einsen (4/4) und Drops
 *
 * Erwartet Mono-PCM in [-1, 1]. Empfohlen: 22050 Hz.
 */
class BeatDetector(
    private val frameSize: Int = 1024,
    private val hopSize: Int = 256,
    private val minBpm: Float = 60f,
    private val maxBpm: Float = 200f,
    private val preferredBpm: Float = 120f,
    /** Wie stark das Tracking am Tempo festhält (Ellis: "tightness"). */
    private val tightness: Float = 100f,
    /** Anteil des Bassbands an der Onset-Hüllkurve (0..1). */
    private val bassWeight: Float = 0.7f,
) {
    init {
        require(frameSize > 0 && frameSize and (frameSize - 1) == 0) { "frameSize muss Zweierpotenz sein" }
    }

    fun analyze(samples: FloatArray, sampleRate: Int, onProgress: (Float) -> Unit = {}): BeatGrid {
        val frameRate = sampleRate.toFloat() / hopSize
        val onset = onsetEnvelope(samples, sampleRate) { onProgress(it * 0.7f) }
        if (onset.flux.size < frameRate * 2) return BeatGrid(0f, emptyList(), emptyList())

        val full = normalizeEnvelope(onset.flux, frameRate)
        val bass = normalizeEnvelope(onset.bassFlux, frameRate)
        // Bass (Kick) stärker gewichten: der Beat sitzt meist auf der Kick, nicht auf den Hi-Hats.
        val env = FloatArray(full.size) { bassWeight * bass[it] + (1f - bassWeight) * full[it] }
        val period = estimatePeriod(env, frameRate)
        onProgress(0.8f)
        val beatFrames = trackBeats(env, period)
        onProgress(0.9f)

        val bpm = 60f * frameRate / period
        // Zeitpunkt = Fenstermitte des Frames.
        val beatsMs = beatFrames.map { ((it * hopSize + frameSize / 2) * 1000.0 / sampleRate).roundToLong() }

        val strengths = beatStrengths(beatFrames, env, bass)
        val downbeats = findDownbeats(beatFrames, bass)
        val drops = findDrops(beatFrames, onset.energy, downbeats)
        onProgress(1f)
        return BeatGrid(bpm, beatsMs, strengths, downbeats, drops)
    }

    // ---------------------------------------------------------------- Onsets

    internal class Onsets(val flux: FloatArray, val bassFlux: FloatArray, val energy: FloatArray)

    internal fun onsetEnvelope(samples: FloatArray, sampleRate: Int, onProgress: (Float) -> Unit): Onsets {
        val frames = max(0, (samples.size - frameSize) / hopSize + 1)
        val bins = frameSize / 2
        val bassBins = max(2, (150f * frameSize / sampleRate).roundToInt())
        val window = FloatArray(frameSize) { (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / frameSize)).toFloat() }
        val re = FloatArray(frameSize)
        val im = FloatArray(frameSize)
        var prev = FloatArray(bins)
        var cur = FloatArray(bins)
        val flux = FloatArray(frames)
        val bassFlux = FloatArray(frames)
        val energy = FloatArray(frames)

        for (f in 0 until frames) {
            val off = f * hopSize
            var e = 0f
            for (i in 0 until frameSize) {
                val s = samples[off + i]
                e += s * s
                re[i] = s * window[i]
                im[i] = 0f
            }
            energy[f] = sqrt(e / frameSize)
            Fft.transform(re, im)
            var sum = 0f
            var bassSum = 0f
            for (k in 0 until bins) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                val v = ln(1f + 100f * mag)
                cur[k] = v
                val d = v - prev[k]
                if (d > 0f) {
                    sum += d
                    if (k in 1..bassBins) bassSum += d
                }
            }
            flux[f] = if (f == 0) 0f else sum
            bassFlux[f] = if (f == 0) 0f else bassSum
            val t = prev; prev = cur; cur = t
            if (f % 512 == 0) onProgress(f.toFloat() / frames)
        }
        return Onsets(flux, bassFlux, energy)
    }

    /** Lokalen Mittelwert abziehen, gleichrichten, auf Standardabweichung normieren. */
    internal fun normalizeEnvelope(x: FloatArray, frameRate: Float): FloatArray {
        val half = max(1, (0.25f * frameRate).roundToInt())
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val a = max(0, i - half)
            val b = min(x.size, i + half + 1)
            val mean = ((prefix[b] - prefix[a]) / (b - a)).toFloat()
            out[i] = max(0f, x[i] - mean)
        }
        var sq = 0.0
        for (v in out) sq += v * v
        val std = sqrt(sq / max(1, out.size)).toFloat()
        if (std > 1e-9f) for (i in out.indices) out[i] /= std
        return out
    }

    // ---------------------------------------------------------------- Tempo

    /** Beat-Periode in Frames (gebrochen) über gewichtete Autokorrelation. */
    internal fun estimatePeriod(env: FloatArray, frameRate: Float): Float {
        val minLag = max(1, (60f * frameRate / maxBpm).toInt())
        val maxLag = min(env.size - 1, (60f * frameRate / minBpm).toInt() + 1)
        val prefLag = 60f * frameRate / preferredBpm
        // Bis 2·maxLag berechnen, damit jeder Kandidat seinen Oberton (doppelte Periode) bekommt.
        val acMax = min(env.size - 1, 2 * maxLag + 1)
        val ac = FloatArray(acMax + 1)
        for (lag in minLag..acMax) {
            if (lag >= env.size) break
            var s = 0.0
            for (i in lag until env.size) s += env[i] * env[i - lag]
            ac[lag] = (s / (env.size - lag)).toFloat()
        }
        // Log-Gauß-Gewichtung um das bevorzugte Tempo (gegen Oktavfehler).
        var bestLag = minLag
        var best = Float.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            // Harmonische (doppelte Periode) mit einbeziehen → stabilere Schätzung.
            val harm = if (2 * lag < ac.size) 0.5f * ac[2 * lag] else 0f
            val w = exp(-0.5 * (log2(lag / prefLag) / 1.0).let { it * it }).toFloat()
            val score = w * (ac[lag] + harm)
            if (score > best) { best = score; bestLag = lag }
        }
        // Parabolische Interpolation für Sub-Frame-Genauigkeit.
        if (bestLag in (minLag + 1) until maxLag) {
            val a = ac[bestLag - 1]; val b = ac[bestLag]; val c = ac[bestLag + 1]
            val denom = a - 2 * b + c
            if (abs(denom) > 1e-9f) {
                val delta = 0.5f * (a - c) / denom
                if (abs(delta) < 1f) return bestLag + delta
            }
        }
        return bestLag.toFloat()
    }

    // ---------------------------------------------------------------- Tracking

    /** Dynamische Programmierung nach Ellis (2007). Liefert Beat-Frames. */
    internal fun trackBeats(env: FloatArray, period: Float): List<Int> {
        val n = env.size
        val score = FloatArray(n)
        val back = IntArray(n) { -1 }
        val lo = max(1, (period / 2).roundToInt())
        val hi = max(lo + 1, (period * 2).roundToInt())
        for (t in 0 until n) {
            var bestPrev = -1
            var bestVal = 0f
            val start = t - hi
            val end = t - lo
            if (end >= 0) {
                for (p in max(0, start)..end) {
                    val r = ln((t - p) / period)
                    val v = score[p] - tightness * r * r
                    if (bestPrev < 0 || v > bestVal) { bestVal = v; bestPrev = p }
                }
            }
            if (bestPrev >= 0 && bestVal > 0f) {
                score[t] = env[t] + bestVal
                back[t] = bestPrev
            } else {
                score[t] = env[t]
            }
        }
        // Endpunkt: bestes Ergebnis in der letzten Periode.
        val tailStart = max(0, n - period.roundToInt())
        var t = tailStart
        for (i in tailStart until n) if (score[i] > score[t]) t = i
        val beats = ArrayList<Int>()
        while (t >= 0) { beats.add(t); t = back[t] }
        beats.reverse()
        return beats
    }

    // ---------------------------------------------------------------- Betonung

    internal fun beatStrengths(beats: List<Int>, env: FloatArray, bass: FloatArray): List<Float> {
        if (beats.isEmpty()) return emptyList()
        val raw = beats.map { b ->
            var m = 0f
            for (i in max(0, b - 2)..min(env.size - 1, b + 2)) m = max(m, 0.4f * env[i] + 0.6f * bass[i])
            m
        }
        val sorted = raw.sorted()
        val p95 = sorted[((sorted.size - 1) * 0.95f).toInt()].takeIf { it > 1e-6f } ?: 1f
        return raw.map { min(1f, it / p95) }
    }

    /** 4/4-Annahme: die Phase mit der stärksten Bassenergie markiert die Takt-Einsen. */
    internal fun findDownbeats(beats: List<Int>, bass: FloatArray): List<Int> {
        if (beats.size < 4) return beats.indices.toList()
        val sums = FloatArray(4)
        beats.forEachIndexed { i, b ->
            var m = 0f
            for (k in max(0, b - 2)..min(bass.size - 1, b + 2)) m = max(m, bass[k])
            sums[i % 4] += m
        }
        val phase = sums.indices.maxBy { sums[it] }
        return beats.indices.filter { it % 4 == phase }
    }

    /** Drop = Takt-Eins, nach der die Lautheit deutlich gegenüber den vorherigen 8 Beats ansteigt. */
    internal fun findDrops(beats: List<Int>, energy: FloatArray, downbeats: List<Int>): List<Int> {
        if (beats.size < 12) return emptyList()
        val perBeat = FloatArray(beats.size) { i ->
            val a = beats[i]
            val b = if (i + 1 < beats.size) beats[i + 1] else min(energy.size, a + (a - beats[max(0, i - 1)]).coerceAtLeast(1))
            var s = 0f
            var c = 0
            for (k in a until min(b, energy.size)) { s += energy[k]; c++ }
            if (c > 0) s / c else 0f
        }
        val median = perBeat.sorted()[perBeat.size / 2]
        val drops = ArrayList<Int>()
        var last = -1000
        for (i in downbeats) {
            if (i < 8 || i + 4 > perBeat.size) continue
            val before = (i - 8 until i).map { perBeat[it] }.average().toFloat()
            val after = (i until i + 4).map { perBeat[it] }.average().toFloat()
            if (after > median && after > 1.6f * max(before, 1e-6f) && i - last >= 16) {
                drops.add(i); last = i
            }
        }
        return drops
    }
}

/** Iterative Radix-2-FFT (in place). */
internal object Fft {
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = kotlin.math.cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
