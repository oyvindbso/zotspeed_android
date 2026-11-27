package com.zotspeed

import com.zotspeed.epub.EpubParser
import com.zotspeed.reader.Rsvp
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Parses every EPUB in $SAMPLE_EPUB_DIR (skipped when unset). */
class SampleEpubsTest {
    @Test
    fun parsesSamples() {
        val dir = System.getenv("SAMPLE_EPUB_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        dir!!.listFiles { f -> f.extension == "epub" }!!.forEach { f ->
            val t0 = System.nanoTime()
            val book = EpubParser.parse(f)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("${f.name}: '${book.title}' by ${book.author} — ${book.chapters.size} chapters, ${book.totalChars} chars, ${ms}ms")
            book.chapters.take(8).forEach { c ->
                println("   [${c.title}] ${c.paragraphs.size} paras, ${Rsvp.tokenize(c).size} words: ${c.text.take(90).replace('\n', '|')}")
            }
            assertTrue(book.chapters.isNotEmpty())
        }
    }
}
