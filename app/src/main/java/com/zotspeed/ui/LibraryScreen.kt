package com.zotspeed.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zotspeed.data.BookEntry

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: AppViewModel) {
    val ui by vm.library.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var downloadedOnly by rememberSaveable { mutableStateOf(false) }
    var libMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<BookEntry?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    val filtered = remember(ui.entries, query, downloadedOnly, ui.downloaded) {
        val q = query.trim().lowercase()
        ui.entries.filter { e ->
            (q.isEmpty() || e.title.lowercase().contains(q) || e.authors.lowercase().contains(q)) &&
                (!downloadedOnly || vm.bookIdFor(e) in ui.downloaded)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
    ) {
        // Compact header: library picker, refresh, overflow.
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { libMenu = true }) {
                    Text(
                        ui.current?.name ?: "Library",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = libMenu, onDismissRequest = { libMenu = false }) {
                    ui.libraries.forEach { lib ->
                        DropdownMenuItem(
                            text = { Text(lib.name) },
                            onClick = { libMenu = false; vm.selectLibrary(lib) },
                            trailingIcon = if (lib == ui.current) ({ Icon(Icons.Default.CheckCircle, null) }) else null,
                        )
                    }
                }
            }
            if (ui.syncing) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { vm.sync() }) { Icon(Icons.Default.Refresh, "Sync") }
            }
            Box {
                IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(text = { Text("Full re-sync") }, onClick = { overflow = false; vm.sync(full = true) })
                    DropdownMenuItem(
                        text = { Text("Sign out (${vm.username})") },
                        onClick = { overflow = false; confirmSignOut = true },
                    )
                }
            }
        }

        // Search + downloaded filter.
        Row(
            Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = colors.surfaceContainerHigh,
                modifier = Modifier.weight(1f).height(38.dp),
            ) {
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = colors.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("Search", color = colors.onSurface.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close, "Clear",
                            Modifier.size(18.dp).combinedClickable(onClick = { query = "" }),
                        )
                    }
                }
            }
            IconToggleButton(checked = downloadedOnly, onCheckedChange = { downloadedOnly = it }) {
                Icon(
                    Icons.Default.DownloadDone,
                    "Downloaded only",
                    tint = if (downloadedOnly) colors.primary else colors.onSurface.copy(alpha = 0.5f),
                )
            }
        }

        ui.syncMessage?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        ui.error?.let {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.dismissError() }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Dismiss") }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            ui.lastBook?.let { (_, title) ->
                if (query.isEmpty()) item("continue") {
                    Surface(
                        color = colors.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .combinedClickable(onClick = { vm.openLast() }),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = colors.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Continue reading", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            if (filtered.isEmpty() && !ui.syncing) {
                item("empty") {
                    Text(
                        when {
                            ui.entries.isEmpty() -> "No EPUB files found in this library. EPUBs must be stored in Zotero storage (not WebDAV or linked files)."
                            else -> "No matches."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(filtered, key = { it.key }) { e ->
                val id = vm.bookIdFor(e)
                BookRow(
                    entry = e,
                    downloaded = id in ui.downloaded,
                    progress = id?.let { ui.progress[it] },
                    downloading = id?.let { ui.downloading[it] },
                    onClick = { vm.open(e) },
                    onLongClick = { if (id in ui.downloaded) confirmDelete = e },
                )
                HorizontalDivider(color = colors.onSurface.copy(alpha = 0.08f))
            }
        }
    }

    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove download?") },
            text = { Text("“${e.title}” will be removed from this device. It stays in Zotero.") },
            confirmButton = { TextButton(onClick = { vm.deleteDownload(e); confirmDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Removes the API key and downloaded books from this device.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    entry: BookEntry,
    downloaded: Boolean,
    progress: Float?,
    downloading: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOf(entry.authors, entry.year).filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            when {
                downloading != null -> if (downloading < 0f) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(progress = { downloading }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                downloaded && progress != null && progress > 0f -> Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                )
                downloaded -> Icon(Icons.Default.DownloadDone, "Downloaded", tint = colors.primary, modifier = Modifier.size(20.dp))
                else -> Icon(
                    Icons.Default.CloudDownload, "Download",
                    tint = colors.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
