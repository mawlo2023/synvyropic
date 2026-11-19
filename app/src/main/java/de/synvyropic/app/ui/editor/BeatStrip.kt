package de.synvyropic.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.synvyropic.app.ui.theme.Rgb
import de.synvyropic.model.BeatGrid

/**
 * Zeigt erkannte Beats als Striche: Höhe = Betonung, blau = Takt-Eins, rot = Drop.
 * Bereich außerhalb des gewählten Ausschnitts wird abgedunkelt.
 */
@Composable
fun BeatStrip(
    grid: BeatGrid, durationMs: Long, rangeStartMs: Long, rangeEndMs: Long,
    modifier: Modifier = Modifier, waveform: List<Float> = emptyList(),
) {
    val normal = MaterialTheme.colorScheme.onSurfaceVariant
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val downbeats = grid.downbeatIndices.toHashSet()
    val drops = grid.dropIndices.toHashSet()
    val waveColor = Rgb.ElectricBlue.copy(alpha = 0.55f)
    Canvas(modifier.fillMaxWidth().height(72.dp)) {
        drawRect(bg)
        if (durationMs <= 0) return@Canvas
        val w = size.width
        val h = size.height
        // Wellenform (gespiegelt um die Mitte)
        if (waveform.isNotEmpty()) {
            val bar = w / waveform.size
            val mid = h / 2f
            waveform.forEachIndexed { i, v ->
                val a = v * h * 0.45f
                drawRect(waveColor, Offset(i * bar, mid - a), Size(maxOf(1f, bar * 0.8f), a * 2f))
            }
        }
        grid.beatsMs.forEachIndexed { i, ms ->
            val x = ms.toFloat() / durationMs * w
            val strength = grid.strengths.getOrElse(i) { 0.5f }
            val color = when (i) {
                in drops -> Rgb.Red
                in downbeats -> Rgb.Blue
                else -> normal
            }
            val lh = h * (0.25f + 0.75f * strength)
            drawLine(color, Offset(x, h - lh), Offset(x, h), strokeWidth = if (i in drops) 4f else 2f)
        }
        val shade = Color.Black.copy(alpha = 0.55f)
        val sx = rangeStartMs.toFloat() / durationMs * w
        val ex = rangeEndMs.toFloat() / durationMs * w
        if (sx > 0) drawRect(shade, Offset.Zero, Size(sx, h))
        if (ex < w) drawRect(shade, Offset(ex, 0f), Size(w - ex, h))
    }
}
