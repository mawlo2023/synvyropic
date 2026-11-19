@file:OptIn(ExperimentalLayoutApi::class)

package de.synvyropic.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.synvyropic.model.ColorGrade
import de.synvyropic.model.ColorLooks
import de.synvyropic.model.Project

private val Yellow = Color(0xFFFFD60A)

private fun pct(v: Float) = (if (v > 0) "+" else "") + "${(v * 100).toInt()}"

/** Projektweite Farbe: Look, Regler, LUT. */
@Composable
fun ColorSection(project: Project, vm: EditorViewModel, pickLut: () -> Unit) {
    val g = project.color
    Section("Farbe", 4, Yellow) {
        Text("Look", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = g.presetId == null, onClick = { vm.updateColor { it.copy(presetId = null) } }, label = { Text("Ohne") })
            ColorLooks.all.forEach { look ->
                FilterChip(
                    selected = g.presetId == look.id,
                    onClick = { vm.updateColor { it.copy(presetId = look.id) } },
                    label = { Text(look.name) },
                )
            }
        }
        if (g.presetId != null) {
            LabeledSlider("Stärke des Looks", g.presetAmount, 0f..1f, { "${(it * 100).toInt()} %" }) { v ->
                vm.updateColor { it.copy(presetAmount = v) }
            }
        }
        ColorSliders(g, includeVignette = true) { f -> vm.updateColor(f) }

        // LUT
        Text("LUT (.cube)", style = MaterialTheme.typography.labelLarge)
        if (g.lutPath == null) {
            FilledTonalButton(onClick = pickLut) {
                Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("LUT importieren")
            }
            Text(
                "Eigene Looks aus Lightroom, Premiere, DaVinci & Co. als .cube-Datei.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.lutName ?: "LUT", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::removeLut) { Icon(Icons.Filled.Close, "LUT entfernen") }
            }
            LabeledSlider("LUT-Intensität", g.lutIntensity, 0f..1f, { "${(it * 100).toInt()} %" }) { v ->
                vm.updateColor { it.copy(lutIntensity = v) }
            }
        }
        if (!g.isNeutral) {
            TextButton(onClick = vm::resetColor) { Text("Look und Regler zurücksetzen") }
        }
    }
}

/** Regler für Helligkeit, Kontrast, Sättigung, Wärme (+ optional Vignette). */
@Composable
fun ColorSliders(g: ColorGrade, includeVignette: Boolean, onChange: ((ColorGrade) -> ColorGrade) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxWidth()) {
        LabeledSlider("Helligkeit", g.brightness, -1f..1f, ::pct) { v -> onChange { it.copy(brightness = v) } }
        LabeledSlider("Kontrast", g.contrast, -1f..1f, ::pct) { v -> onChange { it.copy(contrast = v) } }
        LabeledSlider("Sättigung", g.saturation, -1f..1f, ::pct) { v -> onChange { it.copy(saturation = v) } }
        LabeledSlider("Wärme", g.temperature, -1f..1f, ::pct) { v -> onChange { it.copy(temperature = v) } }
        if (includeVignette) {
            LabeledSlider("Vignette", g.vignette, 0f..1f, { "${(it * 100).toInt()} %" }) { v -> onChange { it.copy(vignette = v) } }
        }
    }
}
