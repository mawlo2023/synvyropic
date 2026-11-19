package de.synvyropic.app.media

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
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
        } finally {
            bmp.recycle()
        }
        ImageInfo(w, h, faces)
    }
}
