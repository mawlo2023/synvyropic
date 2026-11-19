package de.synvyropic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.synvyropic.app.R
import de.synvyropic.app.ui.theme.Rgb

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(onNewProject: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(stringResource(R.string.projects_title), fontWeight = FontWeight.Bold)
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewProject,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_project)) },
            )
        },
    ) { padding ->
        // M0: Noch keine Persistenz – immer leerer Zustand.
        EmptyState(Modifier.padding(padding))
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
