package de.synvyropic.app.ui.editor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Öffnet direkt Google Fotos zur Mehrfachauswahl (inkl. Cloud-Bilder).
 * Ist Google Fotos nicht installiert, erscheint die normale App-Auswahl.
 */
class PickImagesFromApp : ActivityResultContract<Unit, List<Uri>>() {

    override fun createIntent(context: Context, input: Unit): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val photos = Intent(intent).setPackage(GOOGLE_PHOTOS)
        val available = context.packageManager.queryIntentActivities(photos, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
        return if (available) photos else Intent.createChooser(intent, "Bilder auswählen")
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (intent == null || resultCode != android.app.Activity.RESULT_OK) return emptyList()
        val clip = intent.clipData
        if (clip != null) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return listOfNotNull(intent.data)
    }

    companion object {
        const val GOOGLE_PHOTOS = "com.google.android.apps.photos"
    }
}
