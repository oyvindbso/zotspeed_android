package com.zotspeed.reader

import com.zotspeed.epub.Chapter

/** A word shown in one-word (RSVP) mode. [start] is its offset in [Chapter.text]. */
data class RsvpWord(
    val text: String,
    val start: Int,
    /** Display-time multiplier relative to the base word time (60 000 / wpm ms). */
    val pause: Float,
    val paragraph: Int,
    val sentenceStart: Boolean,
)

object Rsvp {
    private val WORD = Regex("\\S+")
    private val TRAILING_CLOSERS = "\"'”’»)]}*"

    fun tokenize(chapter: Chapter): List<RsvpWord> {
        val out = ArrayList<RsvpWord>(chapter.length / 5)
        var nextIsSentenceStart = true
        chapter.paragraphs.forEachIndexed { pi, para ->
            val base = chapter.paragraphStarts[pi]
            val matches = WORD.findAll(para.text).toList()
            matches.forEachIndexed { wi, m ->
                val w = m.value
                val core = w.trimEnd { it in TRAILING_CLOSERS }
                val last = core.lastOrNull()
                var pause = 1f
                if (w.length >= 8) pause = 1.25f
                if (w.length >= 13) pause = 1.5f
                val endsSentence = last != null && last in ".!?…" && !isAbbreviation(core)
                when {
                    endsSentence -> pause = maxOf(pause, 2.0f)
                    last != null && last in ",;:–—" -> pause = maxOf(pause, 1.5f)
                }
                if (wi == matches.lastIndex) pause = maxOf(pause, if (para.heading) 3f else 2.5f)
                if (para.heading) pause = maxOf(pause, 1.5f)
                out += RsvpWord(w, base + m.range.first, pause, pi, nextIsSentenceStart)
                nextIsSentenceStart = endsSentence || wi == matches.lastIndex
            }
        }
        return out
    }

    private val ABBREVIATIONS = setOf(
        "mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "vs.", "etc.", "e.g.", "i.e.", "cf.", "jr.", "sr.",
        "no.", "vol.", "pp.", "p.", "fig.", "ed.", "eds.", "al.",
    )

    private fun isAbbreviation(word: String): Boolean {
        val w = word.trimStart { !it.isLetterOrDigit() }.lowercase()
        if (w in ABBREVIATIONS) return true
        // Single capital initial such as "J."
        return w.length == 2 && w[0].isLetter() && w[1] == '.'
    }

    /**
     * Optimal recognition point: the letter the eye should fixate on. Slightly left of the
     * centre of the word, ignoring leading punctuation.
     */
    fun pivotIndex(word: String): Int {
        if (word.isEmpty()) return 0
        val first = word.indexOfFirst { it.isLetterOrDigit() }
        if (first < 0) return (word.length - 1) / 2
        val last = word.indexOfLast { it.isLetterOrDigit() }
        val n = last - first + 1
        val p = when {
            n <= 1 -> 0
            n <= 5 -> 1
            n <= 9 -> 2
            n <= 13 -> 3
            else -> 4
        }
        return first + p.coerceAtMost(n - 1)
    }

    /** Index of the word at or before [offset]. */
    fun indexAt(words: List<RsvpWord>, offset: Int): Int {
        if (words.isEmpty()) return 0
        var lo = 0
        var hi = words.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (words[mid].start <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun previousSentenceStart(words: List<RsvpWord>, index: Int): Int {
        var i = (index - 1).coerceAtLeast(0)
        // If we're at the beginning of a sentence, go to the one before.
        while (i > 0 && !words[i].sentenceStart) i--
        return i
    }

    fun nextSentenceStart(words: List<RsvpWord>, index: Int): Int {
        var i = index + 1
        while (i < words.size && !words[i].sentenceStart) i++
        return i.coerceAtMost(words.size - 1)
    }

    fun millisFor(word: RsvpWord, wpm: Int): Long = (60_000f / wpm.coerceAtLeast(50) * word.pause).toLong()
}
