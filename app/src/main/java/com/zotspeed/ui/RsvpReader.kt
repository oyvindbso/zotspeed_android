package com.zotspeed.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zotspeed.data.Position
import com.zotspeed.data.ReaderSettings
import com.zotspeed.epub.Chapter
import com.zotspeed.reader.Rsvp
import com.zotspeed.reader.RsvpWord
import com.zotspeed.ui.theme.PivotRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlin.math.abs

@Composable
fun RsvpReader(
    chapter: Chapter,
    chapterIndex: Int,
    chapterCount: Int,
    position: Position,
    settings: ReaderSettings,
    keys: Flow<ReaderKey>,
    onPosition: (Position) -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    onSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    val words = remember(chapter) { Rsvp.tokenize(chapter) }
    var index by remember(chapter) { mutableIntStateOf(Rsvp.indexAt(words, position.offset)) }
    var playing by rememberSaveable { mutableStateOf(false) }
    val wpm by rememberUpdatedState(settings.wpm)
    val colors = MaterialTheme.colorScheme

    fun setIndex(i: Int) {
        if (words.isEmpty()) return
        index = i.coerceIn(0, words.lastIndex)
        onPosition(Position(chapterIndex, words[index].start))
    }

    fun setPlaying(p: Boolean) {
        playing = p
        onPlayingChange(p)
    }

    // External jumps (chapter list, mode switch) move the word index.
    LaunchedEffect(position.offset, words) {
        val i = Rsvp.indexAt(words, position.offset)
        if (words.isNotEmpty() && words[index].start != position.offset && i != index) index = i
    }

    LaunchedEffect(playing, words) {
        onPlayingChange(playing)
        if (!playing || words.isEmpty()) return@LaunchedEffect
        var warmup = 4 // ease in over the first few words after (re)starting
        while (isActive) {
            val w = words[index]
            val factor = if (warmup > 0) 1f + 0.25f * warmup else 1f
            delay((Rsvp.millisFor(w, wpm) * factor).toLong())
            warmup--
            if (index < words.lastIndex) {
                setIndex(index + 1)
            } else if (chapterIndex + 1 < chapterCount) {
                onPosition(Position(chapterIndex + 1, 0)) // keeps playing in the next chapter
                break
            } else {
                setPlaying(false)
                break
            }
        }
    }

    LaunchedEffect(keys) {
        keys.collect { k ->
            when (k) {
                ReaderKey.NEXT -> setPlaying(!playing)
                ReaderKey.PREVIOUS -> {
                    setIndex(Rsvp.previousSentenceStart(words, index))
                }
            }
        }
    }

    if (words.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Empty chapter") }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // Word area: tap to play/pause, swipe to step through words while paused.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(words) {
                    detectTapGestures(onTap = { setPlaying(!playing) })
                }
                .pointerInput(words) {
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { acc = 0f },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            acc += dx
                            val step = 28.dp.toPx()
                            while (abs(acc) > step) {
                                if (playing) setPlaying(false)
                                setIndex(index + if (acc < 0) 1 else -1)
                                acc -= if (acc < 0) -step else step
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            WordDisplay(words[index].text, settings)
        }

        if (!playing) {
            if (settings.rsvpContext) {
                SentenceContext(words, index, Modifier.padding(horizontal = 12.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { setIndex(Rsvp.previousSentenceStart(words, index)) }) {
                    Icon(Icons.Default.FastRewind, "Previous sentence")
                }
                IconButton(onClick = { setIndex(index - 1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous word")
                }
                FilledIconButton(onClick = { setPlaying(true) }) { Icon(Icons.Default.PlayArrow, "Play") }
                IconButton(onClick = { setIndex(index + 1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next word")
                }
                IconButton(onClick = { setIndex(Rsvp.nextSentenceStart(words, index)) }) {
                    Icon(Icons.Default.FastForward, "Next sentence")
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onSettings { it.copy(wpm = (it.wpm - 25).coerceAtLeast(100)) } }, Modifier.size(36.dp)) {
                    Icon(Icons.Default.Remove, "Slower")
                }
                Text("${settings.wpm} wpm", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(84.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { onSettings { it.copy(wpm = (it.wpm + 25).coerceAtMost(1000)) } }, Modifier.size(36.dp)) {
                    Icon(Icons.Default.Add, "Faster")
                }
                val minutesLeft = ((words.size - index) / settings.wpm.toFloat())
                Text(
                    " · ${if (minutesLeft < 1) "<1" else minutesLeft.toInt().toString()} min left",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onBackground.copy(alpha = 0.6f),
                )
            }
        }
        LinearProgressIndicator(
            progress = { (index + 1f) / words.size },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.primary.copy(alpha = 0.7f),
            trackColor = colors.onBackground.copy(alpha = 0.08f),
            drawStopIndicator = {},
        )
    }
}

/**
 * Draws [word] so that its optimal recognition point sits at a fixed horizontal position,
 * marked by small guide ticks; the eye never has to move.
 */
@Composable
private fun WordDisplay(word: String, settings: ReaderSettings) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val pivot = Rsvp.pivotIndex(word)
    val text = remember(word, colors.onBackground) {
        buildAnnotatedString {
            append(word.substring(0, pivot))
            withStyle(SpanStyle(color = PivotRed)) { append(word.substring(pivot, pivot + 1)) }
            append(word.substring(pivot + 1))
        }
    }
    val family = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif
    BoxWithConstraints(Modifier.fillMaxWidth().height(with(density) { (settings.rsvpFontSp.sp.toDp() * 2.2f) })) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val guide = colors.onBackground.copy(alpha = 0.35f)
        Canvas(Modifier.fillMaxSize()) {
            val pivotX = widthPx * 0.38f
            val margin = 6.dp.toPx()
            fun measureAt(sizeSp: Float) = measurer.measure(
                text,
                style = TextStyle(fontSize = sizeSp.sp, fontFamily = family, fontWeight = FontWeight.Medium, color = colors.onBackground),
                softWrap = false,
                maxLines = 1,
                constraints = Constraints(),
            )
            var size = settings.rsvpFontSp.toFloat()
            var layout = measureAt(size)
            var box = layout.getBoundingBox(pivot)
            val center = (box.left + box.right) / 2
            val scale = minOf(
                1f,
                (pivotX - margin) / center.coerceAtLeast(1f),
                (widthPx - pivotX - margin) / (layout.size.width - center).coerceAtLeast(1f),
            )
            if (scale < 1f) {
                size *= scale
                layout = measureAt(size)
                box = layout.getBoundingBox(pivot)
            }
            val x = pivotX - (box.left + box.right) / 2
            val y = (heightPx - layout.size.height) / 2
            drawText(layout, topLeft = Offset(x, y))

            // Fixation guides above and below the pivot letter.
            val tick = 8.dp.toPx()
            val stroke = 1.5.dp.toPx()
            val top = y - 4.dp.toPx()
            val bottom = y + layout.size.height + 4.dp.toPx()
            drawLine(guide, Offset(margin, top), Offset(widthPx - margin, top), stroke)
            drawLine(guide, Offset(margin, bottom), Offset(widthPx - margin, bottom), stroke)
            drawLine(guide, Offset(pivotX, top), Offset(pivotX, top + tick), stroke)
            drawLine(guide, Offset(pivotX, bottom), Offset(pivotX, bottom - tick), stroke)
        }
    }
}

/** The sentence around the current word, so the reader can re-orient while paused. */
@Composable
private fun SentenceContext(words: List<RsvpWord>, index: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val start = remember(words, index) {
        var i = index
        while (i > 0 && !words[i].sentenceStart && index - i < 30) i--
        i
    }
    val end = remember(words, index) {
        var i = index + 1
        while (i < words.size && !words[i].sentenceStart && i - start < 45) i++
        i
    }
    val text: AnnotatedString = remember(words, index, start, end, colors.primary) {
        buildAnnotatedString {
            for (i in start until end) {
                if (i > start) append(' ')
                if (i == index) {
                    withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)) { append(words[i].text) }
                } else append(words[i].text)
            }
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onBackground.copy(alpha = 0.75f),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
