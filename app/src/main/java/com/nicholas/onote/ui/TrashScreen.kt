package com.nicholas.onote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nicholas.onote.data.NoteDocument
import com.nicholas.onote.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    documents: List<NoteDocument>,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { innerPadding ->
        if (documents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Trash is empty", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Deleted notebooks live here until restored or removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(documents, key = { it.id }) { doc ->
                    TrashCard(
                        doc = doc,
                        onRestore = { onRestore(doc.id) },
                        onDeleteForever = { onDeleteForever(doc.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashCard(
    doc: NoteDocument,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    if (doc.pageMode == com.nicholas.onote.data.PageMode.PAGES) {
                        "${doc.pageMode.displayName} · ${doc.pages.size} pages"
                    } else {
                        "${doc.pageBackground.displayName} · ${doc.strokes.size} strokes"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRestore) { Text("Restore") }
            TextButton(onClick = onDeleteForever) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}