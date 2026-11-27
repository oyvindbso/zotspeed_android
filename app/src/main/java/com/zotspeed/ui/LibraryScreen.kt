package com.zotspeed.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zotspeed.data.BookEntry
import com.zotspeed.data.SortKey
import com.zotspeed.data.Sorting
import com.zotspeed.data.ZoteroCollection

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: AppViewModel) {
    val ui by vm.library.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var downloadedOnly by rememberSaveable { mutableStateOf(false) }
    var libMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var showCollections by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<BookEntry?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    val inCollection = remember(ui.entries, ui.collections, ui.collection) { ui.visibleEntries }
    val filtered = remember(inCollection, query, downloadedOnly, ui.downloaded, ui.sort) {
        val q = query.trim().lowercase()
        Sorting.sort(
            inCollection.filter { e ->
                (q.isEmpty() || e.title.lowercase().contains(q) || e.authors.lowercase().contains(q)) &&
                    (!downloadedOnly || vm.bookIdFor(e) in ui.downloaded)
            },
            ui.sort,
        )
    }
    val notDownloaded = filtered.count { vm.bookIdFor(it) !in ui.downloaded }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
    ) {
        // Compact header: library picker, view toggle, refresh, overflow.
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
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    Text(
                        "Sort by",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    SortKey.entries.forEach { key ->
                        val active = ui.sort.key == key
                        DropdownMenuItem(
                            text = {
                                Text(
                                    key.label + if (active) "  " + sortDirectionLabel(key, ui.sort.descending) else "",
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (active && ui.sort.descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                    null,
                                    tint = if (active) colors.primary else Color.Transparent,
                                )
                            },
                            // Tapping the active key again reverses it; the menu stays open to show the change.
                            onClick = { vm.setSort(key) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Reverse order") },
                        trailingIcon = { Checkbox(checked = ui.sort.descending, onCheckedChange = null) },
                        onClick = { vm.setSortOrder(ui.sort.copy(descending = !ui.sort.descending)) },
                    )
                }
            }
            IconButton(onClick = { vm.setGridView(!ui.gridView) }) {
                Icon(
                    if (ui.gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                    if (ui.gridView) "Show as list" else "Show covers",
                )
            }
            if (ui.syncing) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { vm.sync() }) { Icon(Icons.Default.Refresh, "Sync") }
            }
            Box {
                IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(
                        text = { Text("Download all shown ($notDownloaded)") },
                        enabled = notDownloaded > 0 && ui.batchRemaining == 0,
                        onClick = { overflow = false; vm.downloadAll() },
                    )
                    DropdownMenuItem(
                        text = { Text("Covers from Open Library") },
                        trailingIcon = { Checkbox(checked = ui.onlineCovers, onCheckedChange = null) },
                        onClick = { vm.setOnlineCovers(!ui.onlineCovers) },
                    )
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
                        Icon(Icons.Default.Close, "Clear", Modifier.size(18.dp).clickable { query = "" })
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

        // Collection tabs: one per open collection, "+" opens another.
        if (ui.tabs.isNotEmpty()) {
            CollectionTabs(
                ui = ui,
                onSelect = vm::selectTab,
                onClose = vm::closeTab,
                onAdd = { showCollections = true },
            )
        }

        val status = when {
            ui.batchRemaining > 0 -> "Downloading… ${ui.batchRemaining} left"
            else -> ui.syncMessage
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        ui.error?.let {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.dismissError() }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Dismiss") }
            }
        }

        val showContinue = query.isEmpty() && ui.lastBook != null &&
            (ui.collection == null || inCollection.any { vm.bookIdFor(it) == ui.lastBook!!.first })
        val emptyText = when {
            filtered.isNotEmpty() || ui.syncing -> null
            ui.entries.isEmpty() -> "No EPUB files found in this library. EPUBs must be stored in Zotero storage (not WebDAV or linked files)."
            inCollection.isEmpty() -> "No EPUBs in this collection."
            else -> "No matches."
        }

        if (ui.gridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showContinue) item("continue", span = { GridItemSpan(maxLineSpan) }) {
                    ContinueCard(ui.lastBook!!.second) { vm.openLast() }
                }
                emptyText?.let { t -> item("empty", span = { GridItemSpan(maxLineSpan) }) { EmptyText(t) } }
                items(filtered, key = { it.key }) { e ->
                    val id = vm.bookIdFor(e)
                    BookTile(
                        vm = vm,
                        entry = e,
                        // Show the sort value under the title so the order is visible.
                        subtitle = when (ui.sort.key) {
                            SortKey.AUTHOR -> e.authors
                            SortKey.YEAR -> e.year
                            SortKey.TITLE -> ""
                        },
                        downloaded = id in ui.downloaded,
                        onlineCovers = ui.onlineCovers,
                        progress = id?.let { ui.progress[it] },
                        downloading = id?.let { ui.downloading[it] },
                        onClick = { vm.open(e) },
                        onLongClick = { if (id in ui.downloaded) confirmDelete = e },
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (showContinue) item("continue") {
                    Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) { ContinueCard(ui.lastBook!!.second) { vm.openLast() } }
                }
                emptyText?.let { t -> item("empty") { EmptyText(t) } }
                items(filtered, key = { it.key }) { e ->
                    val id = vm.bookIdFor(e)
                    BookRow(
                        vm = vm,
                        entry = e,
                        downloaded = id in ui.downloaded,
                        onlineCovers = ui.onlineCovers,
                        progress = id?.let { ui.progress[it] },
                        downloading = id?.let { ui.downloading[it] },
                        onClick = { vm.open(e) },
                        onLongClick = { if (id in ui.downloaded) confirmDelete = e },
                    )
                    HorizontalDivider(color = colors.onSurface.copy(alpha = 0.08f))
                }
            }
        }
    }

    if (showCollections || ui.needsCollectionChoice) {
        val prompt = ui.needsCollectionChoice
        CollectionDialog(
            title = if (prompt) "Choose a collection" else "Open a collection",
            entries = ui.entries,
            collections = ui.collections,
            openKeys = ui.tabs.toSet(),
            // Dismissing the first-time prompt falls back to all books.
            onDismiss = { showCollections = false; if (prompt) vm.openTab(LibraryUi.ALL_BOOKS) },
            onSelect = { showCollections = false; vm.openTab(it ?: LibraryUi.ALL_BOOKS) },
        )
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
            text = { Text("Removes the Zotero sign-in and downloaded books from this device.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

private fun sortDirectionLabel(key: SortKey, descending: Boolean): String = when (key) {
    SortKey.TITLE, SortKey.AUTHOR -> if (descending) "Z–A" else "A–Z"
    SortKey.YEAR -> if (descending) "newest first" else "oldest first"
}

@Composable
private fun EmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
}

@Composable
private fun ContinueCard(title: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = colors.primary.copy(alpha = 0.14f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
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

/** Cover image, or a generated cover (colour from the title) while loading / when none exists. */
@Composable
fun BookCover(vm: AppViewModel, entry: BookEntry, downloaded: Boolean, onlineCovers: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val image by produceState<ImageBitmap?>(null, entry.key, downloaded, onlineCovers) { value = vm.cover(entry) }
    Box(modifier.clip(RoundedCornerShape(if (compact) 3.dp else 6.dp))) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val hue = ((entry.title.hashCode() and 0x7fffffff) % 360).toFloat()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.hsl(hue, 0.35f, 0.32f))
                    .padding(if (compact) 2.dp else 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (!compact) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            entry.title,
                            color = Color.White,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (entry.authors.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                entry.authors,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(downloaded: Boolean, downloading: Float?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    if (downloading == null && downloaded) return
    Surface(shape = CircleShape, color = colors.surface.copy(alpha = 0.85f), modifier = modifier.size(26.dp)) {
        Box(contentAlignment = Alignment.Center) {
            when {
                downloading != null && downloading >= 0f ->
                    CircularProgressIndicator(progress = { downloading }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                downloading != null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> Icon(Icons.Default.CloudDownload, "Not downloaded", Modifier.size(16.dp), tint = colors.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    vm: AppViewModel,
    entry: BookEntry,
    subtitle: String,
    downloaded: Boolean,
    onlineCovers: Boolean,
    progress: Float?,
    downloading: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            BookCover(vm, entry, downloaded, onlineCovers, Modifier.fillMaxSize())
            StatusBadge(downloaded, downloading, Modifier.align(Alignment.TopEnd).padding(4.dp))
            if (progress != null && progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = colors.primary,
                    trackColor = Color.Black.copy(alpha = 0.4f),
                    drawStopIndicator = {},
                )
            }
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    vm: AppViewModel,
    entry: BookEntry,
    downloaded: Boolean,
    onlineCovers: Boolean,
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BookCover(vm, entry, downloaded, onlineCovers, Modifier.width(36.dp).aspectRatio(2f / 3f), compact = true)
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
                downloading != null -> StatusBadge(downloaded, downloading)
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

/** Collection tree; collections without any EPUBs (including subcollections) are hidden. */
@Composable
private fun CollectionDialog(
    title: String,
    entries: List<BookEntry>,
    collections: List<ZoteroCollection>,
    openKeys: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    val rows = remember(entries, collections) {
        val children = collections.groupBy { it.parent }
        val booksIn = HashMap<String, MutableSet<String>>()
        for (e in entries) for (c in e.collections) booksIn.getOrPut(c) { HashSet() }.add(e.key)
        fun subtree(key: String): Set<String> {
            val set = HashSet(booksIn[key].orEmpty())
            children[key]?.forEach { set.addAll(subtree(it.key)) }
            return set
        }
        val out = ArrayList<Triple<ZoteroCollection, Int, Int>>() // collection, depth, count
        fun walk(parent: String?, depth: Int) {
            for (c in children[parent].orEmpty().sortedBy { it.name.lowercase() }) {
                val count = subtree(c.key).size
                if (count == 0) continue
                out += Triple(c, depth, count)
                walk(c.key, depth + 1)
            }
        }
        walk(null, 0)
        out
    }
    val colors = MaterialTheme.colorScheme
    FullDialog(title, onDismiss) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                CollectionRow(
                    Icons.Default.LibraryBooks, "All books", entries.size, 0, LibraryUi.ALL_BOOKS in openKeys,
                ) { onSelect(null) }
            }
            items(rows, key = { it.first.key }) { (c, depth, count) ->
                val open = c.key in openKeys
                CollectionRow(
                    if (open) Icons.Default.FolderOpen else Icons.Default.Folder,
                    c.name, count, depth, open,
                ) { onSelect(c.key) }
            }
            if (rows.isEmpty()) {
                item {
                    Text(
                        "No collections contain EPUBs. If you just changed them in Zotero, tap the sync button.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onBackground.copy(alpha = 0.7f),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionTabs(
    ui: LibraryUi,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val state = rememberLazyListState()
    LaunchedEffect(ui.activeTab, ui.tabs.size) {
        if (ui.tabs.isNotEmpty()) state.animateScrollToItem(ui.activeTab)
    }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(ui.tabs, key = { _, k -> k }) { i, key ->
            val selected = i == ui.activeTab
            Surface(
                onClick = { onSelect(i) },
                shape = RoundedCornerShape(50),
                color = if (selected) colors.primary else colors.surfaceContainerHigh,
                contentColor = if (selected) colors.onPrimary else colors.onSurface,
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = if (selected) 4.dp else 12.dp).height(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ui.tabName(key),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    if (selected) {
                        Text(
                            " ${ui.entriesIn(key.takeIf { it != LibraryUi.ALL_BOOKS }).size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onPrimary.copy(alpha = 0.75f),
                        )
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).clickable { onClose(i) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Close, "Close tab", Modifier.size(16.dp)) }
                    }
                }
            }
        }
        item("add") {
            Surface(onClick = onAdd, shape = CircleShape, color = colors.surfaceContainer, modifier = Modifier.size(32.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, "Open another collection", Modifier.size(18.dp), tint = colors.primary)
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    count: Int,
    depth: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) colors.primary.copy(alpha = 0.14f) else Color.Transparent)
            .padding(start = 16.dp + 16.dp * depth.coerceAtMost(4), end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = colors.primary)
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$count", style = MaterialTheme.typography.labelSmall, color = colors.onBackground.copy(alpha = 0.55f))
    }
}
