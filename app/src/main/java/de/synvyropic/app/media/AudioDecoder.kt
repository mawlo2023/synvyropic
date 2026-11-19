package de.synvyropic.app.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import de.synvyropic.audio.MonoResampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder

/** Dekodiert eine Audiodatei zu Mono-PCM (Float) mit [targetRate] Hz für die Beat-Analyse. */
object AudioDecoder {

    class Pcm(val samples: FloatArray, val sampleRate: Int)

    suspend fun decodeMono(file: File, targetRate: Int = 22050, onProgress: (Float) -> Unit = {}): Pcm =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Keine Audiospur gefunden")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var resampler: MonoResampler? = null
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var shorts = ShortArray(0)

            try {
                while (!outputDone) {
                    ensureActive()
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            if ((resampler?.sampleCount ?: 0) == 0) resampler = null
                        }
                        outIdx >= 0 -> {
                            val r = resampler ?: MonoResampler(rate, channels, targetRate).also { resampler = it }
                            val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val count = if (floatPcm) info.size / 4 else info.size / 2
                            if (shorts.size < count) shorts = ShortArray(count)
                            if (floatPcm) {
                                val fb = buf.asFloatBuffer()
                                for (i in 0 until count) shorts[i] = (fb.get(i).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            } else {
                                buf.asShortBuffer().get(shorts, 0, count)
                            }
                            r.accept(shorts, count)
                            codec.releaseOutputBuffer(outIdx, false)
                            if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
                extractor.release()
            }
            Pcm(resampler?.result() ?: FloatArray(0), targetRate)
        }
}
