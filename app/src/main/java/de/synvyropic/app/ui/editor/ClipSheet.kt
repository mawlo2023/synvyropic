@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.synvyropic.app.ui.theme.Rgb
import de.synvyropic.model.ColorGrade
import de.synvyropic.model.FitMode
import de.synvyropic.model.SpeedRamp
import de.synvyropic.model.Transition
import de.synvyropic.model.TransitionFamily
import de.synvyropic.model.TransitionType
import kotlin.math.abs
import kotlin.math.roundToLong

private fun TransitionFamily.label() = when (this) {
    TransitionFamily.BASIC -> "Basis"
    TransitionFamily.RGB_GLITCH -> "RGB/Glitch"
    TransitionFamily.MOTION_ZOOM -> "Bewegung"
    TransitionFamily.LIGHT_FLASH -> "Licht"
    TransitionFamily.SHAPE_WIPE -> "Formen"
}

private val flashColors = listOf(0xFFFFFFFF, 0xFF27D3FF, 0xFF3D7BFF, 0xFF9B4DFF, 0xFFE445E6, 0xFFFF8A3D)

/** Bearbeitung eines Clips: Bildmodus und Übergang hinein. Jede Änderung spielt die Stelle in der Vorschau ab. */
@Composable
fun ClipSheet(index: Int, state: EditorState, vm: EditorViewModel, onDismiss: () -> Unit) {
    val timed = state.timeline[index]
    val clip = timed.clip
    val project = state.project ?: return
    val info = project.imageInfo[clip.imageUri]
    val current = clip.transitionIn
    val manual = project.transitionOverrides.containsKey(index)
    val beatMs = vm.beatMs()
    var family by remember(index) { mutableStateOf(current.type.family) }

    fun update(t: Transition) = vm.setTransition(index, t)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Clip ${index + 1}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "${formatMs(timed.startMs)} – ${formatMs(timed.endMs)} · ${clip.durationBeats} Beat(s)" +
                    (info?.faces?.size?.takeIf { it > 0 }?.let { " · $it Gesicht(er)" } ?: "") +
                    (if (info?.maskPath != null) " · Person freigestellt ✓" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---------------- Bildmodus
            if (info != null) {
                Text("Bildmodus", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(FitMode.AUTO to "Auto", FitMode.FILL to "Füllen", FitMode.BLUR to "Blur", FitMode.PAN to "Schwenk").forEach { (m, l) ->
                        FilterChip(selected = info.fit == m, onClick = { vm.setFit(clip.imageUri, m) }, label = { Text(l) })
                    }
                }
            }

            // ---------------- Video: Ausschnitt & Ton
            info?.video?.let { v ->
                HorizontalDivider()
                Text("Video-Ausschnitt", style = MaterialTheme.typography.labelLarge)
                var trim by remember(clip.imageUri, v.trimStartMs, v.trimEndMs) {
                    mutableStateOf(v.trimStartMs.toFloat()..v.trimEnd.toFloat())
                }
                androidx.compose.material3.RangeSlider(
                    value = trim,
                    onValueChange = { trim = it },
                    valueRange = 0f..v.durationMs.toFloat().coerceAtLeast(1f),
                    onValueChangeFinished = {
                        val a = trim.start.roundToLong()
                        val b = trim.endInclusive.roundToLong().coerceAtLeast(a + 300)
                        vm.setTrim(clip.imageUri, a, b)
                    },
                )
                Text(
                    "${formatMs(trim.start.toLong())} – ${formatMs(trim.endInclusive.toLong())} von ${formatMs(v.durationMs)} · " +
                        "der Auto-Schnitt gibt dem Video so viele Beats, wie hineinpassen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (v.hasAudio) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Originalton", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = v.keepAudio, onCheckedChange = { vm.setKeepAudio(clip.imageUri, it) })
                    }
                    if (v.keepAudio) {
                        LabeledSlider("Lautstärke Originalton", v.audioVolume, 0f..1f, { "${(it * 100).toInt()} %" }) {
                            vm.setVideoVolume(clip.imageUri, it)
                        }
                        Text(
                            if ((info?.ramp ?: SpeedRamp.NONE) == SpeedRamp.NONE) "Die Musik wird währenddessen automatisch leiser."
                            else "Bei Tempo-Rampen bleibt der Originalton stumm (er klänge verzerrt).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------------- Tempo-Rampe
            if (info != null) {
                HorizontalDivider()
                Text(if (info.video != null) "Tempo" else "Tempo der Zoom-Fahrt", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        SpeedRamp.NONE to "Normal", SpeedRamp.SLOW_MO to "Zeitlupe", SpeedRamp.FAST to "Zeitraffer",
                        SpeedRamp.SLOW_TO_FAST to "Langsam → schnell", SpeedRamp.FAST_TO_SLOW to "Schnell → langsam",
                        SpeedRamp.BEAT_VELOCITY to "Beat-Velocity",
                    ).forEach { (r, l) ->
                        FilterChip(selected = info.ramp == r, onClick = { vm.setRamp(clip.imageUri, r) }, label = { Text(l) })
                    }
                }
                if (info.ramp == SpeedRamp.BEAT_VELOCITY) {
                    Text("Schneller Ruck auf jedem Beat, danach Zeitlupe – der typische Velocity-Edit.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ---------------- Untertitel dieses Bildes
            if (info != null) {
                var caption by remember(clip.imageUri) { mutableStateOf(info.caption.orEmpty()) }
                androidx.compose.material3.OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it; vm.setCaption(clip.imageUri, it) },
                    label = { Text("Untertitel (z. B. Ort, Datum)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
            }

            // ---------------- Farbkorrektur nur für dieses Bild
            if (info != null) {
                val ic = info.color ?: ColorGrade()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Farbkorrektur dieses Bildes", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    if (info.color != null) {
                        androidx.compose.material3.TextButton(onClick = { vm.setImageColor(clip.imageUri, null) }) { Text("Zurücksetzen") }
                    }
                }
                ColorSliders(ic, includeVignette = false) { f -> vm.setImageColor(clip.imageUri, f(ic)) }
                HorizontalDivider()
            }

            if (index == 0) {
                Text(
                    "Der erste Clip hat keinen Übergang hinein.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Übergang hinein", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    FilterChip(
                        selected = !manual,
                        onClick = { vm.setTransition(index, null) },
                        label = { Text(if (manual) "Wieder automatisch" else "Automatisch") },
                    )
                }

                // Familien
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransitionFamily.entries.forEach { f ->
                        FilterChip(
                            selected = family == f,
                            onClick = { family = f },
                            label = { Text(f.label()) },
                            leadingIcon = { Box(Modifier.size(10.dp).background(familyColor(f.ordinal), CircleShape)) },
                        )
                    }
                }
                // Übergänge der gewählten Familie
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TransitionType.entries.filter { it.family == family }.forEach { t ->
                        FilterChip(
                            selected = current.type == t,
                            onClick = {
                                val dur = if (current.durationMs <= 0) (beatMs * 0.5f).roundToLong() else current.durationMs
                                update(current.copy(type = t, durationMs = if (t == TransitionType.CUT) 0 else dur))
                            },
                            label = { Text(t.label()) },
                        )
                    }
                }

                if (current.type != TransitionType.CUT) {
                    // Dauer in Beats
                    Text("Dauer", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.25f to "¼ Beat", 0.5f to "½ Beat", 1f to "1 Beat", 2f to "2 Beats").forEach { (frac, l) ->
                            val ms = (beatMs * frac).roundToLong()
                            FilterChip(
                                selected = abs(current.durationMs - ms) < beatMs * 0.1f,
                                onClick = { update(current.copy(durationMs = ms)) },
                                label = { Text(l) },
                            )
                        }
                    }
                    Text("${current.durationMs} ms – der Beat liegt genau in der Mitte", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // Intensität
                    var intensity by remember(index, current.type) { mutableFloatStateOf(current.intensity) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Intensität", style = MaterialTheme.typography.labelLarge)
                        Text("${(intensity * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = intensity,
                        onValueChange = { intensity = it },
                        valueRange = 0.1f..1f,
                        onValueChangeFinished = { update(current.copy(intensity = intensity)) },
                    )
                    FilterChip(
                        selected = current.beatReactive,
                        onClick = { update(current.copy(beatReactive = !current.beatReactive)) },
                        label = { Text("Intensität folgt dem Beat") },
                    )

                    // Farbe (Farb-Flash)
                    if (current.type == TransitionType.COLOR_FLASH) {
                        Text("Farbe", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            flashColors.forEach { c ->
                                val selected = current.colorArgb == c
                                Box(
                                    Modifier.size(36.dp).background(Color(c.toInt()), CircleShape)
                                        .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color.Gray, CircleShape)
                                        .clickable { update(current.copy(colorArgb = c)) },
                                )
                            }
                        }
                    }
                    // Winkel (Wipes, RGB-Split)
                    if (current.type in setOf(TransitionType.LINE_WIPE, TransitionType.ZIGZAG_WIPE, TransitionType.RGB_SPLIT)) {
                        Text("Richtung", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0f to "→", 45f to "↗", 90f to "↑", 135f to "↖", 180f to "←", 225f to "↙", 270f to "↓", 315f to "↘").forEach { (a, l) ->
                                FilterChip(selected = current.angleDeg == a, onClick = { update(current.copy(angleDeg = a)) }, label = { Text(l) })
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { vm.focusCut(index) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stelle erneut abspielen", color = Rgb.Green)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
