package de.synvyropic.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Kopiert ausgewählte Medien (auch Cloud-Bilder aus Google Fotos) ins Projektverzeichnis,
 * damit Projekte unabhängig von temporären Zugriffsrechten funktionieren.
 */
class MediaImporter(private val context: Context) {

    /** Dekodiert das Bild (inkl. HEIC), skaliert auf max. [maxSide] px und speichert es als JPEG. */
    suspend fun importImage(uri: Uri, targetDir: File, maxSide: Int = 3840): File = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = File(targetDir, "img_${UUID.randomUUID()}.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        out
    }

    suspend fun importAudio(uri: Uri, targetDir: File): File = withContext(Dispatchers.IO) {
        val ext = displayName(uri)?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 } ?: "audio"
        targetDir.listFiles { f -> f.name.startsWith("music.") }?.forEach { it.delete() }
        val out = File(targetDir, "music.$ext")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Datei kann nicht geöffnet werden" }
            out.outputStream().use { input.copyTo(it) }
        }
        out
    }

    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    fun audioDurationMs(file: File): Long = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        }
    }.getOrDefault(0L)

    fun audioTitle(file: File): String? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            val t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            listOfNotNull(a, t).joinToString(" – ").ifBlank { null }
        }
    }.getOrNull()
}
