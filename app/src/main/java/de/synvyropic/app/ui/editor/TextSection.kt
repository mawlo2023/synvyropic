@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package de.synvyropic.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.synvyropic.app.ui.theme.Rgb
import de.synvyropic.model.Corner
import de.synvyropic.model.Project
import de.synvyropic.model.TextAnimation
import de.synvyropic.model.TextFont
import de.synvyropic.model.TextStyle
import de.synvyropic.model.TitleCard
import de.synvyropic.model.Watermark
import java.io.File
import kotlin.math.roundToLong

private val textColors = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF27D3FF, 0xFF3D7BFF, 0xFF9B4DFF, 0xFFE445E6, 0xFFFF8A3D, 0xFFFFD60A,
)

private fun TextAnimation.label() = when (this) {
    TextAnimation.NONE -> "Keine"
    TextAnimation.FADE -> "Einblenden"
    TextAnimation.SLIDE_UP -> "Hochgleiten"
    TextAnimation.POP -> "Pop"
    TextAnimation.TYPEWRITER -> "Schreibmaschine"
    TextAnimation.GLITCH -> "Glitch"
}

private fun TextFont.label() = when (this) {
    TextFont.SANS_BOLD -> "Fett"
    TextFont.SANS -> "Normal"
    TextFont.BLACK -> "Extra fett"
    TextFont.CONDENSED -> "Schmal"
    TextFont.SERIF -> "Serif"
    TextFont.MONO -> "Mono"
}

/** Abschnitt 5: Intro/Outro, Untertitel-Stil, freie Texte, Wasserzeichen. */
@Composable
fun TextSection(project: Project, vm: EditorViewModel, currentTimeMs: () -> Long, pickLogo: () -> Unit) {
    var editTextId by remember { mutableStateOf<String?>(null) }
    var editStyleOf by remember { mutableStateOf<String?>(null) } // "intro", "outro", "caption"

    Section("Text & Branding", 5, Rgb.Magenta) {
        // ---------------- Intro
        TitleCardEditor("Titel am Anfang", project.intro, default = TitleCard("Mein Video"), onChange = vm::setIntro,
            onStyle = { editStyleOf = "intro" })
        HorizontalDivider()
        // ---------------- Outro
        TitleCardEditor("Titel am Ende", project.outro, default = TitleCard("Danke fürs Zuschauen", durationMs = 2000),
            onChange = vm::setOutro, onStyle = { editStyleOf = "outro" })
        HorizontalDivider()

        // ---------------- Untertitel
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Untertitel pro Bild", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Text pro Bild im Clip-Fenster eingeben (Clip in der Timeline antippen).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { editStyleOf = "caption" }) { Text("Stil") }
        }
        HorizontalDivider()

        // ---------------- Freie Texte
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Texte", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { editTextId = vm.addText(currentTimeMs()) }, enabled = project.clips.isNotEmpty()) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Text")
            }
        }
        if (project.texts.isEmpty()) {
            Text(
                "Wird an der aktuellen Position der Vorschau eingefügt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        project.texts.sortedBy { it.startMs }.forEach { t ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { editTextId = t.id }.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(t.text, maxLines = 1, fontWeight = FontWeight.SemiBold)
                    Text("${formatMs(t.startMs)} – ${formatMs(t.endMs)} · ${t.style.animation.label()}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { editTextId = t.id }) { Icon(Icons.Filled.Edit, "Bearbeiten") }
                IconButton(onClick = { vm.removeText(t.id) }) { Icon(Icons.Filled.Delete, "Löschen") }
            }
        }
        HorizontalDivider()

        // ---------------- Wasserzeichen
        WatermarkEditor(project.watermark, vm, pickLogo)
    }

    // ---------------- Bearbeitungsfenster
    editTextId?.let { id ->
        val t = project.texts.firstOrNull { it.id == id }
        if (t == null) editTextId = null else TextEditSheet(project, t.id, vm) { editTextId = null }
    }
    editStyleOf?.let { which ->
        val style = when (which) {
            "intro" -> project.intro?.style
            "outro" -> project.outro?.style
            else -> project.captionStyle
        }
        if (style == null) editStyleOf = null else StyleSheet(
            title = when (which) { "intro" -> "Stil: Titel am Anfang"; "outro" -> "Stil: Titel am Ende"; else -> "Stil: Untertitel" },
            style = style,
            onChange = { s ->
                when (which) {
                    "intro" -> project.intro?.let { vm.setIntro(it.copy(style = s)) }
                    "outro" -> project.outro?.let { vm.setOutro(it.copy(style = s)) }
                    else -> vm.setCaptionStyle(s)
                }
            },
            onDismiss = { editStyleOf = null },
        )
    }
}

@Composable
private fun TitleCardEditor(label: String, card: TitleCard?, default: TitleCard, onChange: (TitleCard?) -> Unit, onStyle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        if (card != null) TextButton(onClick = onStyle) { Text("Stil") }
        Switch(checked = card != null, onCheckedChange = { on -> onChange(if (on) default else null) })
    }
    if (card != null) {
        var title by remember(label) { mutableStateOf(card.title) }
        var sub by remember(label) { mutableStateOf(card.subtitle.orEmpty()) }
        OutlinedTextField(title, { title = it; onChange(card.copy(title = it)) }, label = { Text("Titel") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sub, { sub = it; onChange(card.copy(subtitle = it.ifBlank { null })) }, label = { Text("Untertitel (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1500L, 2500L, 4000L).forEach { d ->
                FilterChip(selected = card.durationMs == d, onClick = { onChange(card.copy(durationMs = d)) },
                    label = { Text("%.1f s".format(d / 1000f)) })
            }
        }
        LabeledSlider("Bild abdunkeln", card.dim, 0f..0.8f, { "${(it * 100).toInt()} %" }) { onChange(card.copy(dim = it)) }
    }
}

@Composable
private fun WatermarkEditor(w: Watermark?, vm: EditorViewModel, pickLogo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Wasserzeichen", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = w != null, onCheckedChange = { on -> vm.setWatermark(if (on) Watermark(text = "@") else null) })
    }
    if (w == null) return
    // Logo
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (w.imageUri != null) {
            coil.compose.AsyncImage(
                model = File(w.imageUri!!), contentDescription = "Logo",
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF30334F)),
            )
            Text("  Logo", modifier = Modifier.weight(1f))
            IconButton(onClick = vm::removeWatermarkLogo) { Icon(Icons.Filled.Close, "Logo entfernen") }
        } else {
            FilledTonalButton(onClick = pickLogo) {
                Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp)); Text("Logo wählen (PNG)")
            }
        }
    }
    var text by remember { mutableStateOf(w.text.orEmpty()) }
    OutlinedTextField(text, { text = it; vm.setWatermark(w.copy(text = it.ifBlank { null })) },
        label = { Text("Text, z. B. @deinkanal") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Corner.TOP_LEFT to "↖ oben links", Corner.TOP_RIGHT to "↗ oben rechts",
            Corner.BOTTOM_LEFT to "↙ unten links", Corner.BOTTOM_RIGHT to "↘ unten rechts").forEach { (c, l) ->
            FilterChip(selected = w.corner == c, onClick = { vm.setWatermark(w.copy(corner = c)) }, label = { Text(l) })
        }
    }
    LabeledSlider("Deckkraft", w.opacity, 0.2f..1f, { "${(it * 100).toInt()} %" }) { vm.setWatermark(w.copy(opacity = it)) }
    if (w.imageUri != null) {
        LabeledSlider("Logogröße", w.scale, 0.06f..0.4f, { "${(it * 100).toInt()} %" }) { vm.setWatermark(w.copy(scale = it)) }
    }
    if (!w.text.isNullOrBlank()) {
        LabeledSlider("Textgröße", w.textSize, 20f..80f, { "${it.toInt()}" }) { vm.setWatermark(w.copy(textSize = it)) }
    }
}

/** Bearbeitung eines freien Textes: Inhalt, Zeitfenster, Stil. */
@Composable
private fun TextEditSheet(project: Project, id: String, vm: EditorViewModel, onDismiss: () -> Unit) {
    val t = project.texts.first { it.id == id }
    val duration = (vm.state.value.timeline.lastOrNull()?.endMs ?: 1L).coerceAtLeast(1)
    var text by remember(id) { mutableStateOf(t.text) }
    var range by remember(id) { mutableStateOf(t.startMs.toFloat()..t.endMs.toFloat()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Text bearbeiten", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(text, { text = it; vm.updateText(id) { o -> o.copy(text = it) } }, label = { Text("Text") },
                modifier = Modifier.fillMaxWidth())
            Text("Sichtbar: ${formatMs(range.start.toLong())} – ${formatMs(range.endInclusive.toLong())}",
                style = MaterialTheme.typography.labelLarge)
            RangeSlider(
                value = range, onValueChange = { range = it }, valueRange = 0f..duration.toFloat(),
                onValueChangeFinished = {
                    val s = range.start.roundToLong()
                    val e = range.endInclusive.roundToLong().coerceAtLeast(s + 300)
                    vm.updateText(id) { o -> o.copy(startMs = s, endMs = e) }
                },
            )
            HorizontalDivider()
            TextStyleEditor(t.style) { s -> vm.updateText(id) { o -> o.copy(style = s) } }
            TextButton(onClick = { vm.removeText(id); onDismiss() }) { Text("Text löschen", color = Rgb.Magenta) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StyleSheet(title: String, style: TextStyle, onChange: (TextStyle) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextStyleEditor(style, onChange)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Schrift, Größe, Farbe, Kontur, Schatten, Position, Animation, Beat-Kopplung. */
@Composable
fun TextStyleEditor(style: TextStyle, onChange: (TextStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Schrift", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextFont.entries.forEach { f ->
                FilterChip(selected = style.font == f, onClick = { onChange(style.copy(font = f)) }, label = { Text(f.label()) })
            }
        }
        LabeledSlider("Größe", style.size, 24f..180f, { "${it.toInt()}" }) { onChange(style.copy(size = it)) }

        Text("Farbe", style = MaterialTheme.typography.labelLarge)
        ColorRow(style.colorArgb) { onChange(style.copy(colorArgb = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = style.outlineArgb != null,
                onClick = { onChange(style.copy(outlineArgb = if (style.outlineArgb == null) 0xFF000000 else null)) },
                label = { Text("Kontur") })
            FilterChip(selected = style.shadow, onClick = { onChange(style.copy(shadow = !style.shadow)) }, label = { Text("Schatten") })
        }
        style.outlineArgb?.let { o ->
            Text("Konturfarbe", style = MaterialTheme.typography.labelLarge)
            ColorRow(o) { onChange(style.copy(outlineArgb = it)) }
        }

        Text("Position", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.14f to "Oben", 0.5f to "Mitte", 0.86f to "Unten").forEach { (y, l) ->
                FilterChip(selected = kotlin.math.abs(style.y - y) < 0.02f, onClick = { onChange(style.copy(y = y, x = 0.5f)) }, label = { Text(l) })
            }
        }
        LabeledSlider("Höhe", style.y, 0.05f..0.95f, { "${(it * 100).toInt()} %" }) { onChange(style.copy(y = it)) }

        Text("Animation", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAnimation.entries.forEach { a ->
                FilterChip(selected = style.animation == a, onClick = { onChange(style.copy(animation = a)) }, label = { Text(a.label()) })
            }
        }
        FilterChip(selected = style.syncToBeat, onClick = { onChange(style.copy(syncToBeat = !style.syncToBeat)) },
            label = { Text("Pulsiert im Takt") })
    }
}

@Composable
private fun ColorRow(selected: Long, onPick: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        textColors.forEach { c ->
            val sel = selected == c
            Box(
                Modifier.size(32.dp).background(Color(c.toInt()), CircleShape)
                    .border(if (sel) 3.dp else 1.dp, if (sel) Rgb.Cyan else Color.Gray, CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}
