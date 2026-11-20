@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.synvyropic.app.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import de.synvyropic.app.export.ExportManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import de.synvyropic.app.ui.theme.Rgb
import de.synvyropic.model.AspectFormat
import de.synvyropic.model.FitMode
import androidx.compose.material.icons.filled.Face
import de.synvyropic.model.LengthMode
import de.synvyropic.model.Project
import de.synvyropic.model.Styles
import java.io.File
import kotlin.math.roundToLong

@Composable
fun EditorScreen(onBack: () -> Unit, vm: EditorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); vm.dismissError() }
    }

    val context = LocalContext.current
    val preview = rememberPreviewController()
    val previewError by preview.error.collectAsState()
    LaunchedEffect(previewError) { previewError?.let { snackbar.showSnackbar(it) } }
    // Vorschau bekommt jede neue Timeline
    // Jede Projektänderung (Schnitt, Farbe, Texte, Wasserzeichen …) sofort an die Vorschau
    LaunchedEffect(state.project) {
        state.project?.takeIf { it.clips.isNotEmpty() }?.let(preview::setProject)
    }
    // Änderungen sofort in der Vorschau zeigen: betroffenen Bereich in Schleife abspielen
    LaunchedEffect(Unit) {
        vm.focus.collect { f -> preview.playLoop(f.startMs, f.endMs) }
    }
    var selectedClip by remember { mutableStateOf<Int?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var templateDialog by remember { mutableStateOf<String?>(null) }
    val templates by vm.templates.collectAsState()
    when (templateDialog) {
        "save" -> {
            var name by remember { mutableStateOf(state.project?.name ?: "") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { templateDialog = null },
                title = { Text("Als Vorlage speichern") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Gespeichert werden Stil, Raster, Format, Farbe/LUT, Kino-Look, Textstile, Intro/Outro und Wasserzeichen – keine Bilder und keine Musik.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(name, { name = it }, label = { Text("Name der Vorlage") }, singleLine = true)
                    }
                },
                confirmButton = { TextButton(onClick = { vm.saveAsTemplate(name); templateDialog = null }) { Text("Speichern") } },
                dismissButton = { TextButton(onClick = { templateDialog = null }) { Text("Abbrechen") } },
            )
        }
        "apply" -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { templateDialog = null },
            title = { Text("Vorlage anwenden") },
            text = {
                Column {
                    if (templates.isEmpty()) Text("Noch keine Vorlagen gespeichert.")
                    templates.forEach { t ->
                        TextButton(onClick = { vm.applyTemplate(t); templateDialog = null }) { Text(t.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { templateDialog = null }) { Text("Schließen") } },
        )
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    if (showExport) state.project?.let { p ->
        ExportSheet(
            project = p,
            onExport = { settings ->
                showExport = false
                vm.setExportSettings(settings)
                // Für die Fortschritts-Benachrichtigung (Android 13+); Export läuft auch ohne
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                ExportManager.start(context, p.copy(export = settings))
            },
            onDismiss = { showExport = false },
        )
    }
    selectedClip?.let { idx ->
        if (idx < state.timeline.size) {
            ClipSheet(index = idx, state = state, vm = vm, onDismiss = { selectedClip = null; preview.pause() })
        }
    }

    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        vm.addImages(it)
    }
    val pickFromApp = rememberLauncherForActivityResult(PickImagesFromApp()) { vm.addImages(it) }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(vm::setMusic)
    }
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(vm::importLut)
    }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let(vm::importWatermarkLogo)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.project?.name ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true; vm.refreshTemplates() }) { Icon(Icons.Filled.MoreVert, "Menü") }
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Als Vorlage speichern") },
                            onClick = { menu = false; templateDialog = "save" },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Vorlage anwenden") },
                            onClick = { menu = false; templateDialog = "apply" },
                        )
                    }
                    val p = state.project
                    if (p != null && p.clips.isNotEmpty()) {
                        Button(
                            onClick = { preview.pause(); showExport = true },
                            enabled = state.analysisProgress == null && state.importingImages == 0,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(Icons.Filled.Movie, null); Spacer(Modifier.width(6.dp)); Text("Export")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val project = state.project ?: return@Scaffold
        val actions = EditorActions(
            pickGallery = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            pickGooglePhotos = { pickFromApp.launch(Unit) },
            pickMusic = { pickMusic.launch(arrayOf("audio/*")) },
            // .cube hat keinen festen MIME-Typ → alle Dateien zulassen, Prüfung beim Import
            pickLut = { pickLut.launch(arrayOf("*/*")) },
            pickLogo = { pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            currentTime = { preview.currentTime() },
            playPreview = { preview.play() },
        )
        ExportDialog(project.id)
        val hasPreview = project.clips.isNotEmpty()
        val openClip: (Int) -> Unit = { i ->
            selectedClip = i
            if (i > 0) vm.focusCut(i) else state.timeline.getOrNull(0)?.let { preview.playLoop(0, it.endMs) }
        }
        val manual: (Int) -> Boolean = { project.transitionOverrides.containsKey(it) }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth > maxHeight) {
                // Querformat: Einstellungen links, Vorschau + Timeline rechts
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        settingsSections(project, state, vm, actions)
                    }
                    Column(Modifier.weight(1f)) {
                        if (hasPreview) PreviewPane(preview, project, Modifier.fillMaxWidth().fillMaxHeight(0.55f).padding(top = 8.dp))
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            timelineSection(state, manual, openClip)
                        }
                    }
                }
            } else {
                val previewHeight = maxHeight * 0.42f
                Column(Modifier.fillMaxSize()) {
                    if (hasPreview) PreviewPane(preview, project, Modifier.fillMaxWidth().height(previewHeight).padding(top = 8.dp))
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        settingsSections(project, state, vm, actions)
                        timelineSection(state, manual, openClip)
                    }
                }
            }
        }
    }
}

private class EditorActions(
    val pickGallery: () -> Unit,
    val pickGooglePhotos: () -> Unit,
    val pickMusic: () -> Unit,
    val pickLut: () -> Unit,
    val pickLogo: () -> Unit,
    val currentTime: () -> Long,
    val playPreview: () -> Unit,
)

private fun LazyListScope.settingsSections(project: Project, state: EditorState, vm: EditorViewModel, a: EditorActions) {
    item { NameSection(project, vm) }
    item { ImagesSection(project, state, vm, a) }
    item { MusicSection(project, state, vm, a) }
    item { FormatStyleSection(project, vm) }
    item { ColorSection(project, vm, a.pickLut) }
    item { TextSection(project, vm, a.currentTime, a.pickLogo) }
    item { CutButton(project, state, vm) }
}

@Composable
internal fun Section(title: String, number: Int, color: Color, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color),
                    contentAlignment = Alignment.Center,
                ) { Text("$number", fontWeight = FontWeight.Bold, color = Color.Black) }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
            }
            content()
        }
    }
}

@Composable
private fun NameSection(project: Project, vm: EditorViewModel) {
    var name by remember(project.id) { mutableStateOf(project.name) }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it; vm.setName(it) },
        label = { Text("Projektname") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ImagesSection(project: Project, state: EditorState, vm: EditorViewModel, a: EditorActions) {
    Section("Bilder & Videos", 1, Rgb.Red) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = a.pickGallery) {
                Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Galerie")
            }
            FilledTonalButton(onClick = a.pickGooglePhotos) {
                Icon(Icons.Filled.Cloud, null); Spacer(Modifier.width(8.dp)); Text("Google Fotos")
            }
            if (project.images.size > 1) {
                OutlinedButton(onClick = vm::shuffleImages) {
                    Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(8.dp)); Text("Mischen")
                }
                OutlinedButton(onClick = vm::sortByDate) {
                    Icon(Icons.Filled.Event, null); Spacer(Modifier.width(8.dp)); Text("Nach Datum")
                }
            }
        }
        if (project.images.size > 1) {
            FilterChip(
                selected = project.bestOnHighlights,
                onClick = { vm.setBestOnHighlights(!project.bestOnHighlights) },
                label = { Text("Beste Bilder auf Anfang & Drops") },
                leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)) },
            )
        }
        val blurry = remember(project.images, project.imageInfo) { vm.blurryImages() }
        val dupes = remember(project.images, project.imageInfo) { vm.duplicateImages() }
        if (blurry.isNotEmpty() || dupes.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(
                        blurry.size.takeIf { it > 0 }?.let { "$it unscharf" },
                        dupes.size.takeIf { it > 0 }?.let { "$it doppelt" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Rgb.Orange,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::removeWeakImages) { Text("Entfernen") }
            }
        }
        if (state.analyzingFaces > 0) {
            Text("Erkenne Gesichter … (${state.analyzingFaces})", style = MaterialTheme.typography.bodySmall, color = Rgb.Green)
        }
        if (state.importingImages > 0) {
            Text("Importiere ${state.importingImages} Bild(er) …", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (project.images.isEmpty()) {
            Text(
                "Noch keine Bilder. In der Galerie erscheinen auch Google-Fotos-Bilder, wenn das in den Android-Einstellungen aktiviert ist.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("${project.images.size} Bilder", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(project.images, key = { _, p -> p }) { i, path ->
                    val info = project.imageInfo[path]
                    ImageThumb(
                        path = path,
                        index = i,
                        faces = info?.faces?.size,
                        warning = when (path) { in dupes -> "doppelt"; in blurry -> "unscharf"; else -> null },
                        videoMs = info?.video?.trimmedMs,
                        fitLabel = fitLabel(info?.fit, vm.resolvedFit(path)),
                        onFit = { vm.cycleFit(path) },
                        isFirst = i == 0,
                        isLast = i == project.images.lastIndex,
                        onLeft = { vm.moveImage(i, i - 1) },
                        onRight = { vm.moveImage(i, i + 1) },
                        onRemove = { vm.removeImage(i) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageThumb(
    path: String, index: Int, faces: Int?, warning: String?, videoMs: Long?, fitLabel: String, onFit: () -> Unit,
    isFirst: Boolean, isLast: Boolean,
    onLeft: () -> Unit, onRight: () -> Unit, onRemove: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 84.dp, height = 112.dp).clip(RoundedCornerShape(10.dp)),
            )
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(4.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
            )
            IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                Icon(Icons.Filled.Close, "Entfernen", modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)))
            }
            if (videoMs != null) {
                Text(
                    "▶ ${formatMs(videoMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                )
            }
            if (warning != null) {
                Text(
                    warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(Rgb.Orange, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                )
            }
            if (faces != null && faces > 0) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Face, "Gesichter", Modifier.size(12.dp), tint = Rgb.Green)
                    Text(" $faces", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            fitLabel,
            style = MaterialTheme.typography.labelSmall,
            color = Rgb.Blue,
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onFit)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Row {
            IconButton(onClick = onLeft, enabled = !isFirst, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Nach links")
            }
            IconButton(onClick = onRight, enabled = !isLast, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nach rechts")
            }
        }
    }
}

@Composable
private fun MusicSection(project: Project, state: EditorState, vm: EditorViewModel, a: EditorActions) {
    Section("Musik", 2, Rgb.Green) {
        FilledTonalButton(onClick = a.pickMusic, enabled = state.analysisProgress == null) {
            Icon(Icons.Filled.LibraryMusic, null); Spacer(Modifier.width(8.dp))
            Text(if (project.music == null) "Musik wählen" else "Andere Musik wählen")
        }
        state.musicTitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
        state.analysisProgress?.let { p ->
            Text("Analysiere Beats … ${(p * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }
        val music = project.music ?: return@Section
        val grid = music.beats
        if (grid != null && state.analysisProgress == null) {
            val dur = state.musicDurationMs.takeIf { it > 0 } ?: (grid.beatsMs.lastOrNull() ?: 0L)
            Text(
                "%.1f BPM · %d Beats · %d Drop(s)".format(grid.bpm, grid.beatsMs.size, grid.dropIndices.size),
                style = MaterialTheme.typography.labelLarge,
                color = Rgb.Green,
            )
            var range by remember(music.uri, dur, music.startMs, music.endMs) {
                mutableStateOf(music.startMs.toFloat()..(music.endMs ?: dur).toFloat().coerceAtMost(dur.toFloat()))
            }
            BeatStrip(grid, dur, range.start.toLong(), range.endInclusive.toLong(), Modifier.clip(RoundedCornerShape(8.dp)), music.waveform)
            if (dur > 0) {
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = 0f..dur.toFloat(),
                    onValueChangeFinished = { vm.setMusicRange(range.start.roundToLong(), range.endInclusive.roundToLong()) },
                )
                // Feinschnitt: beatweise verschieben
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.nudgeStart(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Start einen Beat früher") }
                    Text("Start ${formatMs(music.startMs)}", style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { vm.nudgeStart(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Start einen Beat später") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.nudgeEnd(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Ende einen Beat früher") }
                    Text("Ende ${formatMs(music.endMs ?: dur)}", style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { vm.nudgeEnd(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ende einen Beat später") }
                }
                Text(
                    "Pfeile verschieben Start/Ende um genau einen Beat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BeatTools(project, vm, a)
            LabeledSlider("Lautstärke", music.volume, 0f..1f, { "${(it * 100).toInt()} %" }) { vm.setVolume(it) }
            LabeledSlider("Fade-In", music.fadeInMs / 1000f, 0f..5f, { "%.1f s".format(it) }) {
                vm.setFades((it * 1000).roundToLong(), music.fadeOutMs)
            }
            LabeledSlider("Fade-Out", music.fadeOutMs / 1000f, 0f..5f, { "%.1f s".format(it) }) {
                vm.setFades(music.fadeInMs, (it * 1000).roundToLong())
            }
        }
    }
}

/** Beat-Kontrolle: dynamischer Schnitt, Tempo ×2/÷2, Verschieben, Tap-Tempo, Neu-Analyse. */
@Composable
private fun BeatTools(project: Project, vm: EditorViewModel, a: EditorActions) {
    var taps by remember { mutableStateOf(listOf<Long>()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = project.dynamicCut,
            onClick = { vm.setDynamicCut(!project.dynamicCut) },
            label = { Text("Dynamischer Schnitt: ruhige Stellen länger halten") },
        )
        Text(
            "Beats prüfen: Vorschau abspielen – der Punkt neben der Zeit leuchtet auf jedem Beat. Passt es nicht, hier korrigieren:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = vm::tempoHalf) { Text("Tempo ÷2") }
            OutlinedButton(onClick = vm::tempoDouble) { Text("Tempo ×2") }
            OutlinedButton(onClick = { vm.shiftBeats(-20) }) { Text("◀ 20 ms früher") }
            OutlinedButton(onClick = { vm.shiftBeats(20) }) { Text("20 ms später ▶") }
        }
        // Tap-Tempo: während die Vorschau läuft im Takt tippen
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (taps.isEmpty()) a.playPreview()
                taps = taps + a.currentTime()
            }) { Text(if (taps.isEmpty()) "Tap-Tempo starten" else "Tap (${taps.size})") }
            if (taps.size >= 4) {
                TextButton(onClick = { if (vm.applyTaps(taps)) taps = emptyList() }) { Text("Übernehmen") }
            }
            if (taps.isNotEmpty()) TextButton(onClick = { taps = emptyList() }) { Text("Verwerfen") }
        }
        if (taps.isNotEmpty() && taps.size < 4) {
            Text("Mindestens 4× im Takt auf jeden Schlag tippen – der erste Tap wird zur Takt-Eins.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { vm.reanalyzeMusic() }) { Text("Beats neu erkennen") }
    }
}

@Composable
internal fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String, onDone: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(format(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = v, onValueChange = { v = it }, valueRange = range, onValueChangeFinished = { onDone(v) })
    }
}

@Composable
private fun FormatStyleSection(project: Project, vm: EditorViewModel) {
    Section("Format & Stil", 3, Rgb.Blue) {
        val formats = listOf(AspectFormat.PORTRAIT_9_16 to "9:16 Reel", AspectFormat.LANDSCAPE_16_9 to "16:9 Slideshow")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            formats.forEachIndexed { i, (f, label) ->
                SegmentedButton(
                    selected = project.format == f,
                    onClick = { vm.setFormat(f) },
                    shape = SegmentedButtonDefaults.itemShape(i, formats.size),
                ) { Text(label) }
            }
        }
        Text("Stil", style = MaterialTheme.typography.labelLarge)
        Styles.all.forEach { s ->
            val selected = project.styleId == s.id
            Card(
                onClick = { vm.setStyle(s.id) },
                border = if (selected) BorderStroke(2.dp, Rgb.gradient) else null,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold)
                    Text(s.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val style = Styles.byId(project.styleId)
        val bpc = project.beatsPerCut ?: style.beatsPerCut
        Text("Bildwechsel alle …", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 4, 8).forEach { n ->
                FilterChip(selected = bpc == n, onClick = { vm.setBeatsPerCut(n) }, label = { Text(if (n == 1) "1 Beat" else "$n Beats") })
            }
        }
        Text("3D-Parallax (Personen)", style = MaterialTheme.typography.labelLarge)
        val masked = project.imageInfo.values.count { it.maskPath != null }
        LabeledSlider(
            if (masked > 0) "Tiefenwirkung · $masked Foto(s) mit freigestellter Person" else "Tiefenwirkung (wirkt auf Fotos mit Personen)",
            project.parallax, 0f..1f, { "${(it * 100).toInt()} %" },
        ) { vm.setParallax(it) }
        Text("Videolänge", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = project.lengthMode == LengthMode.FIT_IMAGES,
                onClick = { vm.setLengthMode(LengthMode.FIT_IMAGES) },
                label = { Text("Jedes Bild einmal") },
            )
            FilterChip(
                selected = project.lengthMode == LengthMode.FIT_MUSIC,
                onClick = { vm.setLengthMode(LengthMode.FIT_MUSIC) },
                label = { Text("Ganzer Musikausschnitt") },
            )
        }
    }
}

@Composable
private fun CutButton(project: Project, state: EditorState, vm: EditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (project.clips.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = Rgb.Green, modifier = Modifier.size(18.dp))
                Text(
                    "  Auto-Schnitt wird bei jeder Änderung sofort aktualisiert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = vm::reshuffle, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Casino, null); Spacer(Modifier.width(8.dp)); Text("Neu würfeln (andere Übergänge)")
            }
            if (project.transitionOverrides.isNotEmpty()) {
                OutlinedButton(onClick = vm::resetManualTransitions, modifier = Modifier.fillMaxWidth()) {
                    Text("${project.transitionOverrides.size} Handauswahl(en) zurücksetzen")
                }
            }
        }
        if (project.music == null && project.images.isNotEmpty()) {
            Text(
                "Ohne Musik wird jedes Bild 2 Sekunden gezeigt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.timelineSection(state: EditorState, manual: (Int) -> Boolean, onClipClick: (Int) -> Unit) {
    if (state.timeline.isEmpty()) return
    item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Timeline", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "${state.timeline.size} Clips · ${formatMs(state.timeline.last().endMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Tippe auf einen Clip, um Übergang und Bildmodus zu bearbeiten.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } }
    itemsIndexed(state.timeline, key = { _, t -> t.clip.id }) { i, t ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)
                .clickable { onClipClick(i) }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = File(t.clip.imageUri),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(44.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    "${i + 1}. ${formatMs(t.startMs)} – ${formatMs(t.endMs)} · ${t.clip.durationBeats} Beat(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    (if (i == 0) "Start" else "Übergang: ${t.clip.transitionIn.type.label()}") +
                        if (manual(i)) "  ✋" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = familyColor(t.clip.transitionIn.type.family.ordinal),
                )
            }
        }
    }
}

/** Beschriftung des Modus-Umschalters; bei Auto steht der aufgelöste Modus dahinter. */
private fun fitLabel(fit: FitMode?, resolved: FitMode?): String {
    fun name(f: FitMode?) = when (f) {
        FitMode.FILL -> "Füllen"
        FitMode.BLUR -> "Blur"
        FitMode.PAN -> "Schwenk"
        else -> "…"
    }
    return when (fit) {
        null -> "Analyse …"
        FitMode.AUTO -> "Auto: ${name(resolved)}"
        else -> name(fit)
    }
}

internal fun familyColor(ordinal: Int): Color = when (ordinal) {
    0 -> Rgb.Red
    1 -> Rgb.Blue
    2 -> Rgb.Orange
    3 -> Rgb.Green
    else -> Color(0xFFA9A9BC)
}
