package de.synvyropic.app.ui.editor

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import de.synvyropic.app.export.ExportManager
import de.synvyropic.app.export.ExportState
import de.synvyropic.app.render.PreviewController
import de.synvyropic.model.Project

/** Erzeugt den Vorschau-Controller und bindet ihn an den Lebenszyklus. */
@Composable
fun rememberPreviewController(): PreviewController {
    val context = LocalContext.current
    val controller = remember { PreviewController(context) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> controller.onHostPause()
                Lifecycle.Event.ON_RESUME -> controller.onHostResume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            controller.release()
        }
    }
    return controller
}

/** Vorschaufenster mit Play/Pause und Spulen. */
@Composable
fun PreviewPane(controller: PreviewController, project: Project, modifier: Modifier = Modifier) {
    val playing by controller.playing.collectAsState()
    val position by controller.position.collectAsState()
    val duration by controller.duration.collectAsState()
    val beat by controller.beat.collectAsState()
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val ratio = project.format.width.toFloat() / project.format.height

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.weight(1f, fill = false).aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black),
        ) {
            AndroidView(factory = { controller.view }, modifier = Modifier.fillMaxWidth().aspectRatio(ratio))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledIconButton(onClick = controller::toggle) {
                if (playing) androidx.compose.material3.Icon(Icons.Filled.Pause, "Pause")
                else androidx.compose.material3.Icon(Icons.Filled.PlayArrow, "Abspielen")
            }
            Slider(
                value = scrubbing ?: position.toFloat(),
                onValueChange = { scrubbing = it; controller.seekTo(it.toLong()) },
                onValueChangeFinished = { scrubbing = null },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                modifier = Modifier.weight(1f),
            )
            // Beat-Anzeige: leuchtet auf jedem erkannten Beat (Takt-Eins in Magenta)
            Box(
                Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(
                    when (beat) {
                        2 -> de.synvyropic.app.ui.theme.Rgb.Magenta
                        1 -> de.synvyropic.app.ui.theme.Rgb.Cyan
                        else -> Color.White.copy(alpha = 0.15f)
                    },
                ),
            )
            Text(
                "${formatMs(position)} / ${formatMs(duration)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Fortschritts-/Ergebnisdialog für den Export dieses Projekts. */
@Composable
fun ExportDialog(projectId: String) {
    val state by ExportManager.state.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val running = state is ExportState.Running && (state as ExportState.Running).projectId == projectId
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    when (val s = state) {
        is ExportState.Running -> if (s.projectId == projectId) AlertDialog(
            onDismissRequest = {},
            title = { Text("Video wird exportiert …") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(s.progress * 100).toInt()} % – du kannst die App geöffnet lassen, der Bildschirm bleibt an.")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = ExportManager::cancel) { Text("Abbrechen") } },
        )
        is ExportState.Done -> if (s.projectId == projectId) AlertDialog(
            onDismissRequest = ExportManager::dismiss,
            title = { Text("Fertig! 🎬") },
            text = { Text("Das Video liegt in der Galerie unter Filme/Synvyropic.") },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(shareIntent(s.uri), "Video teilen"))
                }) { Text("Teilen") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, "video/mp4")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }) { Text("Ansehen") }
                    TextButton(onClick = ExportManager::dismiss) { Text("OK") }
                }
            },
        )
        is ExportState.Failed -> if (s.projectId == projectId) AlertDialog(
            onDismissRequest = ExportManager::dismiss,
            title = { Text("Export fehlgeschlagen") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = ExportManager::dismiss) { Text("OK") } },
        )
        ExportState.Idle -> Unit
    }
}

private fun shareIntent(uri: Uri) = Intent(Intent.ACTION_SEND).apply {
    type = "video/mp4"
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
