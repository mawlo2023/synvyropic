package de.synvyropic.app.render

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Dekodiert ein Video in eine OES-Textur (SurfaceTexture). Liefert auf Anfrage das Bild
 * zur gewünschten Quellzeit: vorwärts wird dekodiert und übersprungen, rückwärts oder bei
 * großen Sprüngen wird zum vorherigen Keyframe gesprungen.
 * Alle Methoden auf dem GL-Thread aufrufen.
 */
class VideoFrameSource(private val path: String) {

    val textureId: Int
    private val surfaceTexture: SurfaceTexture
    private val surface: Surface
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val lock = ReentrantLock()
    private val frameReady = lock.newCondition()
    @Volatile private var frameAvailable = false
    private var inputDone = false
    private var outputDone = false

    /** Zeitstempel (µs) des aktuell in der Textur liegenden Bildes; -1 = noch keins. */
    private var currentUs = -1L
    private var frameIntervalUs = 33_333L

    /** Transformationsmatrix der SurfaceTexture (enthält Drehung/Spiegelung). */
    val transform = FloatArray(16)

    /** Anzeigegröße (Drehung berücksichtigt). */
    val width: Int
    val height: Int

    init {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener({
            lock.withLock { frameAvailable = true; frameReady.signalAll() }
        }, callbackHandler)
        surface = Surface(surfaceTexture)

        extractor.setDataSource(path)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        val w = format.getInteger(MediaFormat.KEY_WIDTH)
        val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        width = if (rotation % 180 != 0) h else w
        height = if (rotation % 180 != 0) w else h
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            runCatching { frameIntervalUs = (1_000_000L / format.getInteger(MediaFormat.KEY_FRAME_RATE)).coerceIn(5_000, 100_000) }
        }
        // Beim Dekodieren in eine Surface wendet der Decoder die Drehung selbst an
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, surface, null, 0)
        codec.start()
        android.opengl.Matrix.setIdentityM(transform, 0)
    }

    /**
     * Bringt das Bild zur Quellzeit [ms] in die Textur.
     * @param blocking true (Export): warten, bis das Bild da ist; false (Vorschau): höchstens kurz dekodieren
     */
    fun seekTo(ms: Long, blocking: Boolean) {
        val target = ms * 1000
        if (currentUs >= 0 && target >= currentUs && target < currentUs + frameIntervalUs) return // passt schon
        if (currentUs < 0 || target < currentUs || target > currentUs + 2_000_000) {
            // Rückwärts oder großer Sprung: zum vorherigen Keyframe
            extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush()
            inputDone = false
            outputDone = false
        }
        val deadline = System.nanoTime() + if (blocking) 5_000_000_000L else 60_000_000L
        while (!outputDone && System.nanoTime() < deadline) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(2_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 2_000)
            if (outIdx < 0) continue
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val pts = info.presentationTimeUs
            // Bilder vor dem Ziel überspringen (außer es ist das letzte vorhandene)
            val render = pts + frameIntervalUs > target || eos
            codec.releaseOutputBuffer(outIdx, render && info.size > 0)
            if (eos) outputDone = true
            if (render && info.size > 0) {
                awaitFrame()
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(transform)
                currentUs = pts
                return
            }
        }
    }

    private fun awaitFrame() {
        lock.withLock {
            val until = System.nanoTime() + 500_000_000L
            while (!frameAvailable) {
                val left = until - System.nanoTime()
                if (left <= 0) break
                frameReady.awaitNanos(left)
            }
            frameAvailable = false
        }
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
        surface.release()
        surfaceTexture.release()
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    companion object {
        /** Eigener Thread für die „Bild bereit“-Rückmeldungen (der GL-Thread wartet darauf). */
        private val callbackHandler: Handler by lazy {
            Handler(HandlerThread("video-frames").apply { start() }.looper)
        }
    }
}
