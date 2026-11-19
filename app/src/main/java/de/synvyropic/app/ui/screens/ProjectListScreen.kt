@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.synvyropic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import de.synvyropic.app.R
import de.synvyropic.app.ui.editor.formatMs
import de.synvyropic.app.ui.theme.Rgb
import de.synvyropic.model.AspectFormat
import de.synvyropic.model.Project
import de.synvyropic.model.Timeline
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(onOpen: (String) -> Unit, onVideos: () -> Unit, vm: ProjectListViewModel = viewModel()) {
    val projects by vm.projects.collectAsState()
    var toDelete by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.projects_title), fontWeight = FontWeight.Bold) })
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ExtendedFloatingActionButton(
                    onClick = onVideos,
                    icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = null) },
                    text = { Text("Meine Videos") },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                ExtendedFloatingActionButton(
                    onClick = { vm.create(onOpen) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_project)) },
                )
            }
        },
    ) { padding ->
        if (projects.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(projects, key = { it.id }) { p ->
                    ProjectCard(p, onClick = { onOpen(p.id) }, onDelete = { toDelete = p })
                }
            }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Projekt löschen?") },
            text = { Text("„${p.name}“ und alle importierten Medien werden gelöscht.") },
            confirmButton = { TextButton(onClick = { vm.delete(p.id); toDelete = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ProjectCard(p: Project, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick) {
        Box {
            val thumb = p.images.firstOrNull()
            if (thumb != null) {
                AsyncImage(
                    model = File(thumb),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                )
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(Rgb.gradient), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MovieFilter, null, Modifier.size(40.dp))
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Filled.Delete, "Löschen", modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(4.dp))
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val format = if (p.format == AspectFormat.PORTRAIT_9_16) "9:16" else "16:9"
            val info = if (p.clips.isEmpty()) "${p.images.size} Bilder · Entwurf"
                       else "$format · ${formatMs(Timeline.durationMs(p))}"
            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(Rgb.gradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MovieFilter, contentDescription = null, modifier = Modifier.size(48.dp))
        }
        Text(
            stringResource(R.string.projects_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            stringResource(R.string.projects_empty_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
