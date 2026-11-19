package de.synvyropic.app.export

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import de.synvyropic.app.render.PreviewController
import de.synvyropic.model.MusicTrack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Fertig kodierte AAC-Spur im Speicher. */
class EncodedAudio(val format: MediaFormat, val samples: List<Sample>) {
    class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)
}

/**
 * Schneidet die Musik auf den Videoausschnitt, wendet Lautstärke und Fades an
 * und kodiert das Ergebnis als AAC (Stereo bzw. Mono, Originalrate).
 */
object AudioRenderer {

    suspend fun render(music: MusicTrack, durationMs: Long, onProgress: (Float) -> Unit): EncodedAudio? {
        val file = File(music.uri)
        if (!file.exists()) return null
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null.also { extractor.release() }
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val startUs = music.startMs * 1000
        val endUs = startUs + durationMs * 1000
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inFormat, null, null, 0)
        decoder.start()

        var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var inChannels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        var encoder: MediaCodec? = null
        var outChannels = 2
        var encFormat: MediaFormat? = null
        val samples = ArrayList<EncodedAudio.Sample>()
        var framesWritten = 0L
        val totalFrames = { durationMs * rate / 1000 }

        // PCM-Puffer zwischen Decoder und Encoder (16 Bit, interleaved, outChannels)
        var pcm = ShortArray(0)
        var pcmLen = 0
        val info = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var decoderDone = false
        var inputDone = false
        var encoderInputDone = false
        var encoderDone = false

        fun ensureEncoder(): MediaCodec {
            encoder?.let { return it }
            outChannels = if (inChannels >= 2) 2 else 1
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, outChannels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
                encoder = it
            }
        }

        try {
            while (!encoderDone) {
                coroutineContext.ensureActive()
                // 1) Decoder füttern
                if (!inputDone) {
                    val idx = decoder.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val buf = decoder.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0 || extractor.sampleTime > endUs) {
                            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                // 2) Decoder-Ausgabe → PCM-Puffer (mit Trimmen, Gain, Downmix)
                if (!decoderDone) {
                    val idx = decoder.dequeueOutputBuffer(info, 5_000)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = decoder.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        inChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (idx >= 0) {
                        ensureEncoder()
                        val buf = decoder.getOutputBuffer(idx)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val bytesPerSample = if (floatPcm) 4 else 2
                        val frames = info.size / (bytesPerSample * inChannels)
                        val ptsUs = info.presentationTimeUs
                        if (pcm.size < pcmLen + frames * outChannels) pcm = pcm.copyOf((pcmLen + frames * outChannels) * 2)
                        for (f in 0 until frames) {
                            val tUs = ptsUs + f * 1_000_000L / rate
                            if (tUs < startUs) continue
                            if (framesWritten + pcmLen / outChannels >= totalFrames()) break
                            val tVideo = (tUs - startUs) / 1000
                            val g = PreviewController.gain(music, tVideo, durationMs)
                            for (c in 0 until outChannels) {
                                val ch = minOf(c, inChannels - 1)
                                val pos = (f * inChannels + ch) * bytesPerSample
                                val v = if (floatPcm) buf.getFloat(info.offset + pos) else buf.getShort(info.offset + pos) / 32768f
                                pcm[pcmLen++] = (v * g * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                        }
                        decoder.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                        onProgress(((ptsUs - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f))
                    }
                }
                val enc = encoder
                if (enc == null) { if (decoderDone) break else continue }
                // 3) PCM → Encoder (in Blöcken zu 1024 Frames)
                if (!encoderInputDone) {
                    val reachedEnd = decoderDone || framesWritten + pcmLen / outChannels >= totalFrames()
                    val block = 1024 * outChannels
                    if (pcmLen >= block || (reachedEnd && pcmLen > 0) || (reachedEnd && pcmLen == 0)) {
                        val idx = enc.dequeueInputBuffer(5_000)
                        if (idx >= 0) {
                            val inBuf = enc.getInputBuffer(idx)!!.order(ByteOrder.nativeOrder())
                            inBuf.clear()
                            val n = minOf(pcmLen, block, inBuf.capacity() / 2)
                            val pts = framesWritten * 1_000_000L / rate
                            if (n == 0) {
                                enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                encoderInputDone = true
                            } else {
                                inBuf.asShortBuffer().put(pcm, 0, n)
                                enc.queueInputBuffer(idx, 0, n * 2, pts, 0)
                                System.arraycopy(pcm, n, pcm, 0, pcmLen - n)
                                pcmLen -= n
                                framesWritten += n / outChannels
                            }
                        }
                    }
                }
                // 4) Encoder-Ausgabe einsammeln
                val outIdx = enc.dequeueOutputBuffer(encInfo, 5_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    encFormat = enc.outputFormat
                } else if (outIdx >= 0) {
                    val ob: ByteBuffer = enc.getOutputBuffer(outIdx)!!
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encInfo.size > 0) {
                        val data = ByteArray(encInfo.size)
                        ob.position(encInfo.offset); ob.get(data)
                        samples += EncodedAudio.Sample(data, encInfo.presentationTimeUs, encInfo.flags)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                }
            }
        } finally {
            runCatching { decoder.stop() }; decoder.release()
            encoder?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
        val f = encFormat ?: return null
        return EncodedAudio(f, samples)
    }
}
