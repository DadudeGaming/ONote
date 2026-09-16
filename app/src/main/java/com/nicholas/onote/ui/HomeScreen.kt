package com.nicholas.onote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nicholas.onote.data.NoteDocument
import com.nicholas.onote.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    documents: List<NoteDocument>,
    darkTheme: Boolean,
    onNew: () -> Unit,
    onOpen: (NoteDocument) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ONote") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                actions = {
                    TextButton(onClick = onOpenTrash) { Text("Trash") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                    TextButton(onClick = onNew) { Text("New") }
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
                    Text(
                        "No notebooks yet",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "Tap New to start writing",
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(documents, key = { it.id }) { doc ->
                    DocumentCard(doc, darkTheme, onClick = { onOpen(doc) })
                }
            }
        }
    }
}

@Composable
private fun DocumentCard(
    doc: NoteDocument,
    darkTheme: Boolean,
    onClick: () -> Unit
) {
    val paper =
        if (darkTheme) AppSettings.PaperDark else AppSettings.PaperLight
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .size(110.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(paper))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    if (doc.pageMode == com.nicholas.onote.data.PageMode.PAGES)
                        "${doc.pageMode.displayName} · ${doc.pages.size} pages"
                    else "${doc.pageBackground.displayName} · ${doc.strokes.size} strokes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    relativeTime(doc.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun relativeTime(time: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - time).coerceAtLeast(0L)
    val min = diff / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} h ago"
        else -> "${min / (60 * 24)} d ago"
    }
}