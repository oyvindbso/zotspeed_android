package com.zotspeed.data

import java.text.Collator
import java.util.Locale

enum class SortKey(val label: String) { TITLE("Title"), AUTHOR("Author"), YEAR("Year") }

data class SortOrder(val key: SortKey = SortKey.TITLE, val descending: Boolean = false)

object Sorting {
    /**
     * Sorts books by [order]. Uses the device locale's collation (so æ/ø/å, accents and case
     * sort naturally). Books missing the sort value (no author / no year) always go last,
     * whichever direction; ties are broken by title, then author.
     */
    fun sort(entries: List<BookEntry>, order: SortOrder, locale: Locale = Locale.getDefault()): List<BookEntry> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        val byTitle = compareBy(collator) { e: BookEntry -> e.title }
        val tieBreak = byTitle.thenBy(collator) { it.authors }

        fun value(e: BookEntry): String = when (order.key) {
            SortKey.TITLE -> e.title
            SortKey.AUTHOR -> e.authors
            SortKey.YEAR -> e.year
        }

        val (present, missing) = entries.partition { value(it).isNotBlank() }
        val primary: Comparator<BookEntry> = when (order.key) {
            SortKey.TITLE -> byTitle
            SortKey.AUTHOR -> compareBy(collator) { it.authors }
            SortKey.YEAR -> compareBy { it.year.toIntOrNull() ?: Int.MAX_VALUE }
        }
        val directed = if (order.descending) primary.reversed() else primary
        return present.sortedWith(directed.then(tieBreak)) + missing.sortedWith(tieBreak)
    }
}
