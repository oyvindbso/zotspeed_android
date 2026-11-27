package com.zotspeed.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zotspeed.data.Position
import com.zotspeed.data.ReaderSettings
import com.zotspeed.epub.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Shows one chapter as a continuous scrollable column of paragraphs. */
@Composable
fun ScrollReader(
    chapter: Chapter,
    chapterIndex: Int,
    chapterCount: Int,
    position: Position,
    settings: ReaderSettings,
    keys: Flow<ReaderKey>,
    onPosition: (Position) -> Unit,
    onChapter: (Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    val style = bodyStyle(settings)
    val headingStyle = style.copy(fontWeight = FontWeight.Bold, fontSize = style.fontSize * 1.15f, textIndent = TextIndent.None)
    // Item 0 is the "previous chapter" row; paragraphs start at item 1.
    val state = remember(chapter) {
        androidx.compose.foundation.lazy.LazyListState(firstVisibleItemIndex = chapter.paragraphAt(position.offset) + 1)
    }
    val currentChapterIndex by rememberUpdatedState(chapterIndex)
    val currentOffset by rememberUpdatedState(position.offset)

    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { item ->
                val para = (item - 1).coerceIn(0, (chapter.paragraphs.size - 1).coerceAtLeast(0))
                if (chapter.paragraphs.isNotEmpty() && item >= 1) {
                    val current = chapter.paragraphAt(currentOffset)
                    if (current != para) onPosition(Position(currentChapterIndex, chapter.paragraphStarts[para]))
                }
            }
    }
    // External jumps within the same chapter.
    LaunchedEffect(position.offset) {
        val target = chapter.paragraphAt(position.offset) + 1
        val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
        if (visible.isNotEmpty() && target !in visible) state.scrollToItem(target)
    }
    LaunchedEffect(keys, state) {
        keys.collect { k ->
            val h = state.layoutInfo.viewportSize.height * 0.85f
            state.animateScrollBy(if (k == ReaderKey.NEXT) h else -h, tween(220))
        }
    }

    LazyColumn(
        state = state,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onToggleChrome() }) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item("prev") {
            if (chapterIndex > 0) {
                ChapterNavButton(back = true, label = "Previous chapter") { onChapter(chapterIndex - 1) }
            }
        }
        itemsIndexed(chapter.paragraphs) { _, p ->
            Text(
                p.text,
                style = if (p.heading) headingStyle else style,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (p.heading) 8.dp else 0.dp),
            )
        }
        item("next") {
            if (chapterIndex + 1 < chapterCount) {
                ChapterNavButton(back = false, label = "Next chapter") { onChapter(chapterIndex + 1) }
            } else {
                Text(
                    "The end",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun ChapterNavButton(back: Boolean, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
        OutlinedButton(onClick = onClick) {
            if (back) Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
            Text(" $label ", maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!back) Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
        }
    }
}
