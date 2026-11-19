package de.synvyropic.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import de.synvyropic.model.ImageMetrics
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import de.synvyropic.model.FaceBox
import de.synvyropic.model.ImageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** Erkennt Gesichter offline (ML Kit, im APK enthalten) und liefert normierte Rechtecke. */
object FaceAnalyzer {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.04f)
                .build(),
        )
    }

    suspend fun analyze(file: File): ImageInfo = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return@withContext ImageInfo(0, 0)
        // Für die Erkennung reichen ~1600 px
        var sample = 1
        while (max(w, h) / (sample * 2) >= 1600) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext ImageInfo(w, h)
        val faces = try {
            val result = suspendCancellableCoroutine { cont ->
                detector.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val bw = bmp.width.toFloat()
            val bh = bmp.height.toFloat()
            result.map { f ->
                val r = f.boundingBox
                FaceBox(
                    (r.left / bw).coerceIn(0f, 1f), (r.top / bh).coerceIn(0f, 1f),
                    (r.right / bw).coerceIn(0f, 1f), (r.bottom / bh).coerceIn(0f, 1f),
                )
            }.filter { it.width > 0f && it.height > 0f }
        } catch (e: Exception) {
            emptyList()
        }
        val metrics = runCatching { metrics(bmp) }.getOrNull()
        bmp.recycle()
        ImageInfo(w, h, faces, sharpness = metrics?.first, dhash = metrics?.second, colorfulness = metrics?.third)
    }

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build(),
        )
    }

    /**
     * Stellt die Person frei und speichert die Maske als PNG (Alpha = Person, max. 768 px).
     * @return Pfad der Maske oder null, wenn kaum Person erkannt wurde
     */
    suspend fun segment(file: File, outDir: File): String? = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 768) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        try {
            val mask = suspendCancellableCoroutine { cont ->
                segmenter.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val w = mask.width
            val h = mask.height
            val buf = mask.buffer.apply { rewind() }.asFloatBuffer()
            val px = IntArray(w * h)
            var covered = 0
            for (i in 0 until w * h) {
                val c = buf.get(i)
                // weiche Kante, schwache Werte verwerfen
                val a = (((c - 0.3f) / 0.5f).coerceIn(0f, 1f) * 255).toInt()
                if (a > 128) covered++
                px[i] = (a shl 24) or 0x00FFFFFF
            }
            // Weniger als 2 % Person → keine brauchbare Maske
            if (covered < w * h * 0.02f) return@withContext null
            val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            val f = File(outDir, "mask_${file.nameWithoutExtension}.png")
            f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.recycle()
            f.absolutePath
        } catch (e: Exception) {
            null
        } finally {
            bmp.recycle()
        }
    }

    /** Schärfe (512 px), dHash (9×8), Farbigkeit (64×64). */
    private fun metrics(src: Bitmap): Triple<Float, Long, Float> {
        fun gray(b: Bitmap): FloatArray {
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            return FloatArray(px.size) { i ->
                val p = px[i]
                ((p shr 16 and 0xFF) * 0.299f + (p shr 8 and 0xFF) * 0.587f + (p and 0xFF) * 0.114f) / 255f
            }
        }
        val s = 512f / max(src.width, src.height)
        val small = Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(3), (src.height * s).toInt().coerceAtLeast(3), true)
        val sharp = ImageMetrics.sharpness(gray(small), small.width, small.height)
        val tiny = Bitmap.createScaledBitmap(src, 9, 8, true)
        val hash = ImageMetrics.dHash(gray(tiny))
        val c64 = Bitmap.createScaledBitmap(src, 64, 64, true)
        val px = IntArray(64 * 64).also { c64.getPixels(it, 0, 64, 0, 0, 64, 64) }
        val color = ImageMetrics.colorfulness(px)
        small.recycle(); tiny.recycle(); c64.recycle()
        return Triple(sharp, hash, color)
    }
}
