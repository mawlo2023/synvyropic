@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.synvyropic.app.ui.editor

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.synvyropic.app.export.VideoExporter
import de.synvyropic.app.ui.theme.Rgb
import de.synvyropic.model.ExportSettings
import de.synvyropic.model.Project
import de.synvyropic.model.Resolution
import de.synvyropic.model.Timeline
import de.synvyropic.model.VideoCodec

/** Exportoptionen: Auflösung, Bildrate, Codec – mit Geräteprüfung und Größenschätzung. */
@Composable
fun ExportSheet(project: Project, onExport: (ExportSettings) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(project.export) }
    val durationMs = Timeline.durationMs(project)

    fun supported(s: ExportSettings): Boolean {
        val (w, h) = project.format.size(s.resolution)
        return VideoExporter.isSupported(s.codec, w, h, s.fps)
    }
    val ok = remember(settings, project.format) { supported(settings) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Video exportieren", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Text("Auflösung", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Resolution.FHD_1080 to "1080p (Full HD)", Resolution.UHD_4K to "4K").forEach { (r, l) ->
                    FilterChip(selected = settings.resolution == r, onClick = { settings = settings.copy(resolution = r) }, label = { Text(l) })
                }
            }
            Text("Bildrate", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30 fps", 60 to "60 fps (flüssiger)").forEach { (f, l) ->
                    FilterChip(selected = settings.fps == f, onClick = { settings = settings.copy(fps = f) }, label = { Text(l) })
                }
            }
            Text("Codec", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(VideoCodec.H264 to "H.264 (überall abspielbar)", VideoCodec.HEVC to "HEVC (kleinere Datei)").forEach { (c, l) ->
                    FilterChip(selected = settings.codec == c, onClick = { settings = settings.copy(codec = c) }, label = { Text(l) })
                }
            }

            val (w, h) = project.format.size(settings.resolution)
            val bytes = VideoExporter.estimateBytes(settings, project.format, durationMs)
            Text(
                "${w}×$h · ${formatMs(durationMs)} · ca. ${Formatter.formatShortFileSize(context, bytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!ok) {
                Text(
                    "Diese Kombination unterstützt dein Gerät nicht. Bitte eine kleinere Auflösung, 30 fps oder H.264 wählen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Rgb.Magenta,
                )
            } else if (settings.resolution == Resolution.UHD_4K || settings.fps == 60) {
                Text(
                    "Dauert länger als 1080p/30 fps. Der Export läuft auch im Hintergrund weiter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { onExport(settings) }, enabled = ok, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Movie, null); Spacer(Modifier.width(8.dp)); Text("Exportieren")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
