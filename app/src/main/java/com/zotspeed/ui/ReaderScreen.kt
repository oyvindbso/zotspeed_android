package com.zotspeed.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zotspeed.data.ReadMode

@Composable
fun ReaderScreen(vm: AppViewModel) {
    val r by vm.reader.collectAsStateWithLifecycle()
    val pos by vm.position.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var rsvpPlaying by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
    ) {
        val book = r.book
        when {
            r.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            r.error != null || book == null -> Column(
                Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(r.error ?: "Nothing to show", color = colors.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.closeBook() }) { Text("Back") }
            }
            else -> {
                val chapterIndex = pos.chapter.coerceIn(0, book.chapters.lastIndex)
                val chapter = book.chapters[chapterIndex]
                val showChrome = if (mode == ReadMode.RSVP) !rsvpPlaying else chromeVisible
                Column(Modifier.fillMaxSize()) {
                    if (showChrome) {
                        TopBar(
                            mode = mode,
                            onBack = { vm.closeBook() },
                            onMode = { vm.setMode(it) },
                            onToc = { showToc = true },
                            onSettings = { showSettings = true },
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (mode) {
                            ReadMode.RSVP -> RsvpReader(
                                chapter = chapter,
                                chapterIndex = chapterIndex,
                                chapterCount = book.chapters.size,
                                position = pos,
                                settings = settings,
                                keys = vm.keys,
                                onPosition = vm::setPosition,
                                onPlayingChange = { rsvpPlaying = it; if (!it) vm.persistPosition() },
                                onSettings = vm::updateSettings,
                            )
                            ReadMode.PAGED -> PagedReader(
                                chapter = chapter,
                                chapterIndex = chapterIndex,
                                chapterCount = book.chapters.size,
                                position = pos,
                                settings = settings,
                                keys = vm.keys,
                                onPosition = vm::setPosition,
                                onChapter = { i, atEnd -> vm.goToChapter(i, atEnd) },
                                onToggleChrome = { chromeVisible = !chromeVisible },
                            )
                            ReadMode.SCROLL -> ScrollReader(
                                chapter = chapter,
                                chapterIndex = chapterIndex,
                                chapterCount = book.chapters.size,
                                position = pos,
                                settings = settings,
                                keys = vm.keys,
                                onPosition = vm::setPosition,
                                onChapter = { i -> vm.goToChapter(i) },
                                onToggleChrome = { chromeVisible = !chromeVisible },
                            )
                        }
                    }
                    if (showChrome) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                chapter.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onBackground.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${(book.progress(chapterIndex, pos.offset) * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onBackground.copy(alpha = 0.6f),
                            )
                        }
                    }
                }

                if (showToc) {
                    ChapterListDialog(
                        book = book,
                        current = chapterIndex,
                        onDismiss = { showToc = false },
                        onSelect = { showToc = false; vm.goToChapter(it) },
                    )
                }
                if (showSettings) {
                    ReaderSettingsDialog(settings = settings, onChange = vm::updateSettings, onDismiss = { showSettings = false })
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    mode: ReadMode,
    onBack: () -> Unit,
    onMode: (ReadMode) -> Unit,
    onToc: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Library") }
        Spacer(Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row {
                ModeButton(Icons.Default.Bolt, "One word", mode == ReadMode.RSVP) { onMode(ReadMode.RSVP) }
                ModeButton(Icons.AutoMirrored.Filled.MenuBook, "Pages", mode == ReadMode.PAGED) { onMode(ReadMode.PAGED) }
                ModeButton(Icons.Default.SwapVert, "Scroll", mode == ReadMode.SCROLL) { onMode(ReadMode.SCROLL) }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToc) { Icon(Icons.AutoMirrored.Filled.ViewList, "Chapters") }
        IconButton(onClick = onSettings) { Icon(Icons.Default.FormatSize, "Reading settings") }
    }
}

@Composable
private fun ModeButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) colors.primary else colors.surfaceContainerHigh,
        modifier = Modifier.size(width = 44.dp, height = 34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon, label,
                tint = if (selected) colors.onPrimary else colors.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
