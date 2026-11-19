package de.synvyropic.app.export

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import de.synvyropic.app.render.PreviewController
import de.synvyropic.model.Project
import de.synvyropic.model.SpeedRamp
import de.synvyropic.model.Timeline
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Fertig kodierte AAC-Spur im Speicher. */
class EncodedAudio(val format: MediaFormat, val samples: List<Sample>) {
    class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)
}

/**
 * Tonspur des Videos: Musik (Ausschnitt, Lautstärke, Fades) + Originalton der Videoclips.
 * Solange Originalton läuft, wird die Musik abgesenkt (Ducking). Ergebnis: AAC 48 kHz Stereo.
 */
object AudioRenderer {
    private const val RATE = 48_000
    private const val DUCK = 0.35f

    suspend fun render(project: Project, durationMs: Long, onProgress: (Float) -> Unit): EncodedAudio? {
        val timed = Timeline.resolve(project)
        val voiced = timed.filter { tc ->
            val info = project.imageInfo[tc.clip.imageUri]
            val v = info?.video
            info != null && v != null && v.hasAudio && v.keepAudio && info.ramp == SpeedRamp.NONE && File(tc.clip.imageUri).exists()
        }
        val music = project.music?.takeIf { File(it.uri).exists() }
        if (music == null && voiced.isEmpty()) return null

        val frames = (durationMs * RATE / 1000).toInt().coerceAtLeast(1)
        val mix = ShortArray(frames * 2)

        // Ducking-Hüllkurve (pro Frame): 150 ms weiche Übergänge
        val duck = FloatArray(frames) { 1f }
        val ramp = RATE * 150 / 1000
        for (tc in voiced) {
            val a = (tc.startMs * RATE / 1000).toInt()
            val b = (tc.endMs * RATE / 1000).toInt().coerceAtMost(frames)
            for (i in (a - ramp).coerceAtLeast(0) until (b + ramp).coerceAtMost(frames)) {
                val edge = when {
                    i < a -> (a - i).toFloat() / ramp
                    i >= b -> (i - b).toFloat() / ramp
                    else -> 0f
                }.coerceIn(0f, 1f)
                duck[i] = minOf(duck[i], DUCK + (1f - DUCK) * edge)
            }
        }

        // 1) Musik
        music?.let { m ->
            val startUs = m.startMs * 1000
            decode(File(m.uri), startUs, startUs + durationMs * 1000, Mixer(mix, 0) { outFrame ->
                if (outFrame >= frames) 0f else PreviewController.gain(m, outFrame * 1000L / RATE, durationMs) * duck[outFrame]
            }) { p -> onProgress(p * 0.5f) }
        }
        // 2) Originalton der Videoclips
        voiced.forEachIndexed { idx, tc ->
            val v = project.imageInfo[tc.clip.imageUri]!!.video!!
            val len = minOf(tc.endMs - tc.startMs, v.trimmedMs)
            val offset = (tc.startMs * RATE / 1000).toInt()
            val fadeFrames = RATE * 30 / 1000
            val clipFrames = (len * RATE / 1000).toInt()
            decode(File(tc.clip.imageUri), v.trimStartMs * 1000, (v.trimStartMs + len) * 1000, Mixer(mix, offset) { outFrame ->
                val local = outFrame - offset
                val fade = minOf(local, clipFrames - local).toFloat() / fadeFrames
                v.audioVolume * fade.coerceIn(0f, 1f)
            }) { p -> onProgress(0.5f + (idx + p) / voiced.size * 0.3f) }
        }
        return encode(mix) { p -> onProgress(0.8f + p * 0.2f) }
    }

    /**
     * Mischt eingehende Stereo-Frames (beliebige Rate) linear interpoliert auf 48 kHz in [target],
     * beginnend bei [offset]; [gain] liefert die Lautstärke pro Ausgabe-Frame.
     */
    private class Mixer(val target: ShortArray, val offset: Int, val gain: (Int) -> Float) {
        var srcRate = RATE
        private var srcIndex = 0L
        private var prevL = 0f
        private var prevR = 0f
        private var nextOut = 0L

        fun accept(l: Float, r: Float) {
            val ratio = RATE.toDouble() / srcRate
            val pos = srcIndex * ratio
            val prevPos = (srcIndex - 1) * ratio
            while (nextOut <= pos) {
                val f = if (srcIndex == 0L) 1f else ((nextOut - prevPos) / ratio).toFloat().coerceIn(0f, 1f)
                val out = offset + nextOut.toInt()
                if (out * 2 + 1 >= target.size) { nextOut++; continue }
                val g = gain(out)
                add(out * 2, (prevL + (l - prevL) * f) * g)
                add(out * 2 + 1, (prevR + (r - prevR) * f) * g)
                nextOut++
            }
            prevL = l; prevR = r
            srcIndex++
        }

        private fun add(i: Int, v: Float) {
            target[i] = (target[i] + v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Dekodiert [startUs, endUs) einer Datei und reicht Stereo-Frames an den Mixer. */
    private suspend fun decode(file: File, startUs: Long, endUs: Long, mixer: Mixer, onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); return }
        extractor.selectTrack(track)
        val fmt = extractor.getTrackFormat(track)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        mixer.srcRate = rate
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var done = false
        try {
            while (!done) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || extractor.sampleTime > endUs) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); mixer.srcRate = rate
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    val bps = if (floatPcm) 4 else 2
                    val n = info.size / (bps * ch)
                    for (f in 0 until n) {
                        val t = info.presentationTimeUs + f * 1_000_000L / rate
                        if (t < startUs) continue
                        if (t >= endUs) { done = true; break }
                        fun s(c: Int): Float {
                            val pos = info.offset + (f * ch + minOf(c, ch - 1)) * bps
                            return if (floatPcm) buf.getFloat(pos) else buf.getShort(pos) / 32768f
                        }
                        mixer.accept(s(0), s(1))
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    onProgress(((info.presentationTimeUs - startUs).toFloat() / (endUs - startUs).coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release(); extractor.release()
        }
    }

    /** 16-Bit-Stereo-PCM (48 kHz) → AAC. */
    private suspend fun encode(pcm: ShortArray, onProgress: (Float) -> Unit): EncodedAudio? {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val info = MediaCodec.BufferInfo()
        val samples = ArrayList<EncodedAudio.Sample>()
        var outFormat: MediaFormat? = null
        var pos = 0
        var inputDone = false
        var done = false
        try {
            while (!done) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = enc.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val b = enc.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                        b.clear()
                        val n = minOf(pcm.size - pos, 2048, b.capacity() / 2)
                        val pts = (pos / 2).toLong() * 1_000_000L / RATE
                        if (n <= 0) {
                            enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            b.asShortBuffer().put(pcm, pos, n)
                            enc.queueInputBuffer(i, 0, n * 2, pts, 0)
                            pos += n
                            if (pos % (RATE * 2) < 2048) onProgress(pos.toFloat() / pcm.size)
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = enc.outputFormat
                else if (o >= 0) {
                    val ob = enc.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val data = ByteArray(info.size)
                        ob.position(info.offset); ob.get(data)
                        samples += EncodedAudio.Sample(data, info.presentationTimeUs, info.flags)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        } finally {
            runCatching { enc.stop() }; enc.release()
        }
        return outFormat?.let { EncodedAudio(it, samples) }
    }
}
