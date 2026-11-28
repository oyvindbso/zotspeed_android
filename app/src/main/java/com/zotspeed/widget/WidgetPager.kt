package com.zotspeed.widget

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Splits text into pages that fit the widget's TextView. The settings here must mirror
 * widget_cover_reader.xml (serif, line spacing 1.15, simple breaking, no hyphenation,
 * no font padding) so the TextView wraps lines exactly as measured.
 */
class WidgetPager(textSizePx: Float, private val widthPx: Int, private val heightPx: Int) {
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx
        typeface = Typeface.SERIF
    }

    private fun layout(text: CharSequence): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setUseLineSpacingFromFallbacks(true)
            .build()

    /** End offset (exclusive) of the page that starts at [start]. Always advances at least one line. */
    fun pageEnd(text: String, start: Int): Int {
        if (start >= text.length) return text.length
        var window = WINDOW
        while (true) {
            val end = (start + window).coerceAtMost(text.length)
            val l = layout(text.substring(start, end))
            var last = -1
            for (i in 0 until l.lineCount) {
                if (l.getLineBottom(i) <= heightPx) last = i else break
            }
            if (last == l.lineCount - 1 && end < text.length) {
                window *= 2 // everything fitted: the page may hold more text
                continue
            }
            return start + l.getLineEnd(last.coerceAtLeast(0))
        }
    }

    /** Start offset of the page that ends at [end] (i.e. the previous page). */
    fun pageStartBefore(text: String, end: Int): Int {
        if (end <= 0) return 0
        // Lay out from a paragraph start so wrapping matches reading forward.
        val from = (end - WINDOW).coerceAtLeast(0)
        var winStart = if (from == 0) 0 else text.lastIndexOf('\n', from) + 1
        if (end - winStart > WINDOW * 3) winStart = from
        // A trailing newline would add an empty line to the layout; the page doesn't show it.
        var e = end
        while (e > winStart && text[e - 1] == '\n') e--
        if (e == winStart) return winStart
        val l = layout(text.substring(winStart, e))
        val bottom = l.getLineBottom(l.lineCount - 1)
        var first = l.lineCount - 1
        while (first > 0 && bottom - l.getLineTop(first - 1) <= heightPx) first--
        return winStart + l.getLineStart(first)
    }

    companion object {
        const val LINE_SPACING = 1.15f
        private const val WINDOW = 2500
    }
}
