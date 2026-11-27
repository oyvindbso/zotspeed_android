package com.zotspeed

import com.zotspeed.epub.Chapter
import com.zotspeed.epub.Paragraph
import com.zotspeed.reader.Rsvp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RsvpTest {
    private val chapter = Chapter(
        "c",
        listOf(
            Paragraph("Title", heading = true),
            Paragraph("Dr. Smith went home, slowly. Then \"he slept.\" End"),
        ),
    )

    @Test
    fun tokenizesWithOffsetsAndPauses() {
        val words = Rsvp.tokenize(chapter)
        assertEquals(listOf("Title", "Dr.", "Smith", "went", "home,", "slowly.", "Then", "\"he", "slept.\"", "End"), words.map { it.text })
        words.forEach { assertEquals(it.text, chapter.text.substring(it.start, it.start + it.text.length)) }

        val byText = words.associateBy { it.text }
        assertEquals(1f, byText.getValue("Smith").pause)
        assertEquals(1.5f, byText.getValue("home,").pause)
        assertEquals(2f, byText.getValue("slowly.").pause)
        assertEquals(2f, byText.getValue("slept.\"").pause)
        assertEquals(1f, byText.getValue("Dr.").pause) // abbreviation, not a sentence end
        assertTrue(byText.getValue("End").pause >= 2.5f) // paragraph end

        assertTrue(byText.getValue("Dr.").sentenceStart)
        assertTrue(!byText.getValue("Smith").sentenceStart)
        assertTrue(byText.getValue("Then").sentenceStart)
        assertTrue(byText.getValue("End").sentenceStart)
    }

    @Test
    fun navigatesSentences() {
        val words = Rsvp.tokenize(chapter)
        val went = words.indexOfFirst { it.text == "went" }
        assertEquals("Dr.", words[Rsvp.previousSentenceStart(words, went)].text)
        assertEquals("Then", words[Rsvp.nextSentenceStart(words, went)].text)
        assertEquals(went, Rsvp.indexAt(words, words[went].start + 2))
    }

    @Test
    fun pivotSkipsPunctuation() {
        assertEquals(0, Rsvp.pivotIndex("a"))
        assertEquals(1, Rsvp.pivotIndex("word"))
        assertEquals(2, Rsvp.pivotIndex("\"word"))
        assertEquals(2, Rsvp.pivotIndex("reading"))
        assertEquals(4, Rsvp.pivotIndex("internationalization"))
    }
}
