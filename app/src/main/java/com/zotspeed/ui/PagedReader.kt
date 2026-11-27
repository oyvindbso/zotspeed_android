package com.zotspeed.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.zotspeed.data.Position
import com.zotspeed.data.ReaderSettings
import com.zotspeed.epub.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** A chapter laid out once at the current width, split into pages at line boundaries. */
class Pagination(
    val layout: TextLayoutResult,
    /** Y coordinate of the top of each page inside [layout]. */
    val pageTops: FloatArray,
    /** Character offset at the start of each page. */
    val pageStarts: IntArray,
    val pageHeight: Float,
) {
    val pageCount get() = pageTops.size

    fun pageFor(offset: Int): Int {
        var lo = 0
        var hi = pageStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pageStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Height of page [i]: up to the top of the next page so partial lines never show. */
    fun visibleHeight(i: Int): Float =
        if (i + 1 < pageTops.size) pageTops[i + 1] - pageTops[i] else pageHeight

    companion object {
        fun compute(layout: TextLayoutResult, pageHeight: Float): Pagination {
            val tops = ArrayList<Float>()
            val starts = ArrayList<Int>()
            tops += 0f
            starts += 0
            var pageTop = 0f
            for (line in 0 until layout.lineCount) {
                val lineTop = layout.getLineTop(line)
                if (layout.getLineBottom(line) - pageTop > pageHeight && lineTop > pageTop) {
                    pageTop = lineTop
                    tops += pageTop
                    starts += layout.getLineStart(line)
                }
            }
            return Pagination(layout, tops.toFloatArray(), starts.toIntArray(), pageHeight)
        }
    }
}

fun chapterAnnotated(chapter: Chapter): AnnotatedString = buildAnnotatedString {
    // Same characters as Chapter.text, so layout offsets are chapter offsets.
    chapter.paragraphs.forEachIndexed { i, p ->
        if (i > 0) append('\n')
        if (p.heading) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.15.em)) { append(p.text) }
        } else append(p.text)
    }
}

@Composable
fun PagedReader(
    chapter: Chapter,
    chapterIndex: Int,
    chapterCount: Int,
    position: Position,
    settings: ReaderSettings,
    keys: Flow<ReaderKey>,
    onPosition: (Position) -> Unit,
    onChapter: (index: Int, atEnd: Boolean) -> Unit,
    onToggleChrome: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val style = bodyStyle(settings)
    val density = LocalDensity.current
    val fontResolver = LocalFontFamilyResolver.current
    val layoutDirection = LocalLayoutDirection.current

    val footerHeight = 18.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hPad = with(density) { 14.dp.roundToPx() }
        val vPad = with(density) { 6.dp.roundToPx() }
        val w = constraints.maxWidth - 2 * hPad
        val h = (constraints.maxHeight - 2 * vPad - with(density) { footerHeight.roundToPx() }).toFloat()
        val measureStyle = style.copy(color = colors.onBackground)
        val pagination by produceState<Pagination?>(null, chapter, w, h, measureStyle) {
            value = null
            value = withContext(Dispatchers.Default) {
                val measurer = TextMeasurer(fontResolver, density, layoutDirection, cacheSize = 0)
                val layout = measurer.measure(
                    chapterAnnotated(chapter),
                    style = measureStyle,
                    constraints = Constraints(maxWidth = w.coerceAtLeast(1)),
                )
                Pagination.compute(layout, h.coerceAtLeast(1f))
            }
        }
        val pg = pagination
        if (pg == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }

        var page by remember(pg) { mutableIntStateOf(pg.pageFor(position.offset)) }

        fun go(delta: Int) {
            val target = page + delta
            when {
                target in 0 until pg.pageCount -> {
                    page = target
                    onPosition(Position(chapterIndex, pg.pageStarts[target]))
                }
                target < 0 && chapterIndex > 0 -> onChapter(chapterIndex - 1, true)
                target >= pg.pageCount && chapterIndex + 1 < chapterCount -> onChapter(chapterIndex + 1, false)
            }
        }

        // External jumps (chapter list, other modes).
        LaunchedEffect(position.offset, pg) {
            val p = pg.pageFor(position.offset)
            if (p != page) page = p
        }
        LaunchedEffect(keys, pg) {
            keys.collect { k -> go(if (k == ReaderKey.NEXT) 1 else -1) }
        }

        Column(Modifier.fillMaxSize()) {
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(pg) {
                        detectTapGestures { o ->
                            val x = o.x / size.width
                            when {
                                x < 0.3f -> go(-1)
                                x > 0.7f -> go(1)
                                else -> onToggleChrome()
                            }
                        }
                    }
                    .pointerInput(pg) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total < -48.dp.toPx()) go(1) else if (total > 48.dp.toPx()) go(-1)
                            },
                            onHorizontalDrag = { change, dx -> change.consume(); total += dx },
                        )
                    }
            ) {
                val p = page.coerceIn(0, pg.pageCount - 1)
                translate(left = hPad.toFloat(), top = vPad.toFloat()) {
                    clipRect(0f, 0f, w.toFloat(), pg.visibleHeight(p)) {
                        translate(top = -pg.pageTops[p]) {
                            drawText(pg.layout, color = colors.onBackground, topLeft = Offset.Zero)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(footerHeight).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Ch ${chapterIndex + 1}/$chapterCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onBackground.copy(alpha = 0.45f),
                )
                Box(Modifier.weight(1f))
                Text(
                    "${page + 1} / ${pg.pageCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onBackground.copy(alpha = 0.45f),
                )
            }
        }
    }
}
