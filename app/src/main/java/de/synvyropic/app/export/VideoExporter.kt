package de.synvyropic.app.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import de.synvyropic.app.render.Compositor
import de.synvyropic.model.Project
import de.synvyropic.model.Resolution
import de.synvyropic.model.Timeline
import de.synvyropic.model.VideoCodec
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * Rendert ein Projekt framegenau als MP4 (H.264/HEVC + AAC) direkt nach Movies/Synvyropic.
 * Nutzt denselben [Compositor] wie die Vorschau.
 */
class VideoExporter(private val context: Context) {

    /** @return URI der fertigen Datei in der Galerie */
    suspend fun export(project: Project, onProgress: (Float) -> Unit): Uri {
        val durationMs = Timeline.durationMs(project)
        require(durationMs > 0) { "Projekt ist leer" }
        val (w, h) = project.format.size(project.export.resolution)
        val fps = project.export.fps

        // 1) Audio vorab kodieren (0–15 %)
        val audio = project.music?.let { m -> AudioRenderer.render(m, durationMs) { onProgress(it * 0.15f) } }

        // 2) Zieldatei in der Galerie anlegen
        val resolver = context.contentResolver
        val name = "Synvyropic_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Synvyropic")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("Datei in der Galerie konnte nicht angelegt werden")

        // 3) Video auf eigenem GL-Thread rendern
        val glThread = Executors.newSingleThreadExecutor { r -> Thread(r, "synvyropic-export") }
        try {
            withContext(glThread.asCoroutineDispatcher()) {
                resolver.openFileDescriptor(uri, "rw").use { pfd ->
                    requireNotNull(pfd) { "Datei kann nicht geöffnet werden" }
                    val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    try {
                        encodeVideo(project, w, h, fps, durationMs, muxer, audio) { onProgress(0.15f + it * 0.85f) }
                    } finally {
                        runCatching { muxer.stop() }
                        muxer.release()
                    }
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            onProgress(1f)
            return uri
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        } finally {
            glThread.shutdown()
        }
    }

    private suspend fun encodeVideo(
        project: Project, w: Int, h: Int, fps: Int, durationMs: Long,
        muxer: MediaMuxer, audio: EncodedAudio?, onProgress: (Float) -> Unit,
    ) {
        val mime = if (project.export.codec == VideoCodec.HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val pixels = w.toLong() * h
        val bitrate = (pixels * fps * 0.25).toInt().coerceIn(8_000_000, 80_000_000) // ~16 Mbit/s bei 1080p30
        val format = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(mime)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val egl = EglCore()
        egl.attach(inputSurface)
        val compositor = Compositor(w, h)
        compositor.setProject(project)

        var videoTrack = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            if (endOfStream) encoder.signalEndOfInputStream()
            while (true) {
                val idx = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Format doppelt geändert" }
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        val audioTrack = audio?.let { muxer.addTrack(it.format) }
                        muxer.start()
                        muxerStarted = true
                        // Audio komplett vorab schreiben
                        if (audio != null && audioTrack != null) {
                            val ai = MediaCodec.BufferInfo()
                            for (s in audio.samples) {
                                ai.set(0, s.data.size, s.ptsUs, s.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), ai)
                            }
                        }
                    }
                    idx >= 0 -> {
                        val buf = encoder.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, buf, info)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            val totalFrames = (durationMs * fps / 1000).toInt().coerceAtLeast(1)
            for (n in 0 until totalFrames) {
                coroutineContext.ensureActive()
                val tMs = n * 1000L / fps
                compositor.render(tMs, w, h, blocking = true)
                egl.setPresentationTime(n * 1_000_000_000L / fps)
                egl.swap()
                drain(false)
                if (n % 5 == 0) onProgress(n.toFloat() / totalFrames)
            }
            drain(true)
        } finally {
            compositor.release()
            egl.release()
            runCatching { encoder.stop() }
            encoder.release()
            inputSurface.release()
        }
    }

    companion object {
        fun describe(project: Project): String {
            val (w, h) = project.format.size(project.export.resolution)
            val res = if (project.export.resolution == Resolution.UHD_4K) "4K" else "1080p"
            return "$res · ${w}×$h · ${project.export.fps} fps"
        }
    }
}
