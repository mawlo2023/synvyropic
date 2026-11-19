package de.synvyropic.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max

/**
 * Lädt Bilder im Hintergrund und lädt sie auf dem GL-Thread als Textur hoch.
 * Alle Methoden außer dem Dekodieren laufen auf dem GL-Thread.
 */
class TextureCache(private val maxSide: Int, private val capacity: Int = 8) {
    private val decoder: ExecutorService = Executors.newFixedThreadPool(2)
    private val textures = LinkedHashMap<String, ImageTexture>(16, 0.75f, true)
    private val pending = HashMap<String, Future<Bitmap?>>()

    /** Startet das Dekodieren, falls noch nicht geschehen. */
    fun prefetch(path: String) {
        if (path in textures || path in pending) return
        pending[path] = decoder.submit<Bitmap?> { decode(path) }
    }

    /** Liefert die Textur, falls bereit (lädt fertige Bitmaps hoch). */
    fun getIfReady(path: String): ImageTexture? {
        textures[path]?.let { return it }
        val f = pending[path] ?: run { prefetch(path); return null }
        if (!f.isDone) return null
        return upload(path, f)
    }

    /** Wartet, bis die Textur da ist (für den Export). */
    fun getBlocking(path: String): ImageTexture? {
        textures[path]?.let { return it }
        prefetch(path)
        return upload(path, pending.getValue(path))
    }

    private fun upload(path: String, f: Future<Bitmap?>): ImageTexture? {
        pending.remove(path)
        val bmp = runCatching { f.get() }.getOrNull() ?: return null
        val tex = ImageTexture.upload(bmp)
        bmp.recycle()
        textures[path] = tex
        evict()
        return tex
    }

    /** Hält nur die zuletzt benutzten Texturen; [keep] wird nie entfernt. */
    fun evict(keep: Set<String> = emptySet()) {
        val it = textures.entries.iterator()
        while (textures.size > capacity && it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) { e.value.release(); it.remove() }
        }
    }

    fun release() {
        textures.values.forEach { it.release() }
        textures.clear()
        pending.values.forEach { it.cancel(true) }
        pending.clear()
        decoder.shutdownNow()
    }

    private fun decode(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        // Ken Burns zoomt bis ~1.3x → etwas Reserve lassen
        while (longest / (sample * 2) >= maxSide * 1.3f) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }
}
