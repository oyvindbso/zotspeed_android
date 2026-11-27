package com.zotspeed

import com.zotspeed.data.BookEntry
import com.zotspeed.data.SortKey
import com.zotspeed.data.SortOrder
import com.zotspeed.data.Sorting
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SortingTest {
    private fun b(title: String, authors: String, year: String) =
        BookEntry(key = title, parentKey = null, title = title, authors = authors, year = year, filename = "", md5 = null)

    private val books = listOf(
        b("the Origin of Species", "Darwin", "1859"),
        b("Øya", "Ødegård", "2001"),
        b("Anonymous pamphlet", "", ""),
        b("Brave New World", "Huxley", "1932"),
        b("Åpent landskap", "Aasen", "2001"),
        b("Zettelkasten", "Ahrens", "2017"),
    )

    private fun titles(order: SortOrder, locale: Locale = Locale.ENGLISH) = Sorting.sort(books, order, locale).map { it.title }

    @Test
    fun byTitleIgnoringCaseBothWays() {
        assertEquals(
            listOf("Anonymous pamphlet", "Brave New World", "the Origin of Species", "Zettelkasten"),
            titles(SortOrder(SortKey.TITLE)).filter { it[0].code < 128 },
        )
        assertEquals(titles(SortOrder(SortKey.TITLE)).reversed(), titles(SortOrder(SortKey.TITLE, descending = true)))
    }

    @Test
    fun norwegianLettersSortAfterZ() {
        val nb = Locale.forLanguageTag("nb-NO")
        assertEquals(listOf("Zettelkasten", "Øya", "Åpent landskap"), titles(SortOrder(SortKey.TITLE), nb).takeLast(3))
    }

    @Test
    fun byAuthorWithMissingLast() {
        assertEquals(
            listOf("Aasen", "Ahrens", "Darwin", "Huxley", "Ødegård", ""),
            Sorting.sort(books, SortOrder(SortKey.AUTHOR), Locale.ENGLISH).map { it.authors },
        )
        // Reversed, but books without an author still go last.
        assertEquals(
            listOf("Ødegård", "Huxley", "Darwin", "Ahrens", "Aasen", ""),
            Sorting.sort(books, SortOrder(SortKey.AUTHOR, descending = true), Locale.ENGLISH).map { it.authors },
        )
    }

    @Test
    fun byYearNumericWithTitleTieBreak() {
        assertEquals(
            listOf("the Origin of Species", "Brave New World", "Åpent landskap", "Øya", "Zettelkasten", "Anonymous pamphlet"),
            titles(SortOrder(SortKey.YEAR)),
        )
        assertEquals(
            listOf("Zettelkasten", "Åpent landskap", "Øya", "Brave New World", "the Origin of Species", "Anonymous pamphlet"),
            titles(SortOrder(SortKey.YEAR, descending = true)),
        )
    }
}
