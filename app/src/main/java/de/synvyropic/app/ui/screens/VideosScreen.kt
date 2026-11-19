@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.synvyropic.app.ui.screens

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.synvyropic.app.ui.editor.formatMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportedVideo(val uri: Uri, val name: String, val durationMs: Long, val dateMs: Long, val sizeBytes: Long)

/** Liest alle Videos aus Filme/Synvyropic (eigene immer, fremde/ältere nur mit Berechtigung). */
suspend fun loadExportedVideos(context: Context): List<ExportedVideo> = withContext(Dispatchers.IO) {
    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val projection = arrayOf(
        MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE,
    )
    val list = ArrayList<ExportedVideo>()
    context.contentResolver.query(
        collection, projection,
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Movies/Synvyropic%"),
        "${MediaStore.Video.Media.DATE_ADDED} DESC",
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            list += ExportedVideo(
                uri = ContentUris.withAppendedId(collection, id),
                name = c.getString(1) ?: "Video",
                durationMs = c.getLong(2),
                dateMs = c.getLong(3) * 1000,
                sizeBytes = c.getLong(4),
            )
        }
    }
    list
}

@Composable
fun VideosScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reload++ }
    val deleteRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload++ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        // Nur einmal pro Installation fragen
        val prefs = context.getSharedPreferences("synvyropic", Context.MODE_PRIVATE)
        if (!granted && !prefs.getBoolean("askedVideoPermission", false)) {
            prefs.edit().putBoolean("askedVideoPermission", true).apply()
            permission.launch(Manifest.permission.READ_MEDIA_VIDEO)
        }
    }
    val videos by produceState<List<ExportedVideo>?>(null, reload) { value = loadExportedVideos(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meine Videos", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
    ) { padding ->
        val list = videos
        when {
            list == null -> Unit
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.VideoLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Noch keine exportierten Videos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text(
                    "Exportierte Videos landen in Filme/Synvyropic und erscheinen hier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(list, key = { it.uri.toString() }) { v ->
                    VideoCard(
                        video = v,
                        onPlay = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(v.uri, "video/mp4")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, v.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Video teilen"))
                        },
                        onDelete = {
                            // Systemdialog zur Bestätigung (funktioniert auch für Videos älterer Installationen)
                            val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(v.uri))
                            deleteRequest.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoCard(video: ExportedVideo, onPlay: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, video.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(video.uri, Size(480, 480), null) }.getOrNull()
        }
    }
    Card(onClick = onPlay) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(Color.Black), contentAlignment = Alignment.Center) {
            thumb?.let {
                Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Icon(Icons.Filled.PlayCircle, "Abspielen", Modifier.size(48.dp), tint = Color.White.copy(alpha = 0.85f))
            Text(
                formatMs(video.durationMs),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
        }
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            Text(video.name.removeSuffix(".mp4"), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(video.dateMs)) +
                    " · " + Formatter.formatShortFileSize(context, video.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Teilen") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Löschen") }
        }
    }
}
