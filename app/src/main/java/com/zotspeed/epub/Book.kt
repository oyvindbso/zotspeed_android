package com.zotspeed.epub

/** One block of text (a paragraph or a heading). */
data class Paragraph(val text: String, val heading: Boolean = false)

/**
 * A chapter, flattened to plain paragraphs.
 *
 * All reading modes address a position inside a chapter as a character offset into [text],
 * which is the paragraphs joined with '\n'. That keeps the position consistent when the
 * reader switches between one-word, paged and scrolling modes.
 */
class Chapter(val title: String, val paragraphs: List<Paragraph>) {
    val text: String
    val paragraphStarts: IntArray

    init {
        val sb = StringBuilder()
        paragraphStarts = IntArray(paragraphs.size)
        paragraphs.forEachIndexed { i, p ->
            if (i > 0) sb.append('\n')
            paragraphStarts[i] = sb.length
            sb.append(p.text)
        }
        text = sb.toString()
    }

    val length: Int get() = text.length

    /** Index of the paragraph containing [offset] (clamped). */
    fun paragraphAt(offset: Int): Int {
        if (paragraphs.isEmpty()) return 0
        var lo = 0
        var hi = paragraphStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (paragraphStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    val wordCount: Int by lazy { text.split(WHITESPACE).count { it.isNotEmpty() } }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

class Book(
    val title: String,
    val author: String?,
    val chapters: List<Chapter>,
) {
    val totalChars: Int = chapters.sumOf { it.length }

    /** Overall progress 0..1 for a position. */
    fun progress(chapter: Int, offset: Int): Float {
        if (totalChars == 0) return 0f
        var before = 0
        for (i in 0 until chapter.coerceIn(0, chapters.size)) before += chapters[i].length
        val inChapter = offset.coerceIn(0, chapters.getOrNull(chapter)?.length ?: 0)
        return (before + inChapter).toFloat() / totalChars
    }
}
