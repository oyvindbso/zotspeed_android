package com.zotspeed

import com.zotspeed.epub.EpubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    private fun epub(files: Map<String, String>): File {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/epub+zip".toByteArray()); z.closeEntry()
            files.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray()); z.closeEntry()
            }
        }
        return File.createTempFile("test", ".epub").apply { writeBytes(bos.toByteArray()); deleteOnExit() }
    }

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    private fun xhtml(body: String) = """<?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title><style>p{}</style></head>
        <body>$body</body></html>"""

    @Test
    fun parsesEpub3WithNavAndMergesUntitledSplits() {
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Test Book</dc:title><dc:creator>Ada Lovelace</dc:creator>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="cover" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1b" href="text/ch1b.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="cover"/><itemref idref="c1"/><itemref idref="c1b"/><itemref idref="c2"/></spine>
            </package>"""
        val nav = xhtml(
            """<nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops"><ol>
               <li><a href="text/ch%201.xhtml#start">One</a></li>
               <li><a href="text/ch2.xhtml">Two</a></li></ol></nav>"""
        )
        val file = epub(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf,
                "OEBPS/nav.xhtml" to nav,
                "OEBPS/text/cover.xhtml" to xhtml("<p>Cover page</p>"),
                "OEBPS/text/ch 1.xhtml" to xhtml("<h1>Chapter <em>One</em></h1><p>Hello <b>bold</b>   world.</p><script>bad()</script>"),
                "OEBPS/text/ch1b.xhtml" to xhtml("<div><p>Continued.</p>Loose text<br/>after break</div>"),
                "OEBPS/text/ch2.xhtml" to xhtml("<p>Second.</p>"),
            )
        )
        val book = EpubParser.parse(file)
        assertEquals("Test Book", book.title)
        assertEquals("Ada Lovelace", book.author)
        assertEquals(listOf("Section 1", "One", "Two"), book.chapters.map { it.title })

        val one = book.chapters[1]
        assertEquals("Chapter One", one.paragraphs[0].text)
        assertTrue(one.paragraphs[0].heading)
        assertEquals("Hello bold world.", one.paragraphs[1].text)
        assertEquals(
            listOf("Chapter One", "Hello bold world.", "Continued.", "Loose text", "after break"),
            one.paragraphs.map { it.text },
        )
        assertEquals(one.paragraphs.map { it.text }.joinToString("\n"), one.text)
        assertEquals(2, one.paragraphAt(one.paragraphStarts[2] + 3))
    }

    @Test
    fun parsesEpub2WithNcx() {
        val opf = """<?xml version="1.0"?>
            <opf:package xmlns:opf="http://www.idpf.org/2007/opf" version="2.0">
              <opf:metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Old</dc:title></opf:metadata>
              <opf:manifest>
                <opf:item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <opf:item id="a" href="a.html" media-type="application/xhtml+xml"/>
                <opf:item id="b" href="b.html" media-type="application/xhtml+xml"/>
              </opf:manifest>
              <opf:spine toc="ncx"><opf:itemref idref="a"/><opf:itemref idref="b"/></opf:spine>
            </opf:package>"""
        val ncx = """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
            <navPoint id="1"><navLabel><text>Alpha</text></navLabel><content src="a.html"/></navPoint>
            <navPoint id="2"><navLabel><text>Beta</text></navLabel><content src="b.html#x"/></navPoint>
            </navMap></ncx>"""
        val book = EpubParser.parse(
            epub(
                mapOf(
                    "META-INF/container.xml" to container,
                    "OEBPS/content.opf" to opf,
                    "OEBPS/toc.ncx" to ncx,
                    "OEBPS/a.html" to "<html><body><p>A text</p></body></html>",
                    "OEBPS/b.html" to "<html><body><p>B text</p></body></html>",
                )
            )
        )
        assertEquals("Old", book.title)
        assertEquals(listOf("Alpha", "Beta"), book.chapters.map { it.title })
        assertEquals("B text", book.chapters[1].text)
    }

    @Test
    fun findsCoverImages() {
        val opf3 = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><manifest>
            <item id="img" href="images/front.jpg" media-type="image/jpeg" properties="cover-image"/>
            <item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest>
            <spine><itemref idref="c"/></spine></package>"""
        val e3 = epub(mapOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf3, "OEBPS/images/front.jpg" to "JPEG3"))
        assertEquals("JPEG3", EpubParser.coverImage(e3)?.decodeToString())

        val opf2 = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0">
            <metadata><meta name="cover" content="cov"/></metadata><manifest>
            <item id="cov" href="pic.png" media-type="image/png"/>
            <item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest>
            <spine><itemref idref="c"/></spine></package>"""
        val e2 = epub(mapOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf2, "OEBPS/pic.png" to "PNG2"))
        assertEquals("PNG2", EpubParser.coverImage(e2)?.decodeToString())

        val opfPage = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0"><manifest>
            <item id="t" href="text/title.xhtml" media-type="application/xhtml+xml"/>
            <item id="i" href="img/a.gif" media-type="image/gif"/></manifest>
            <spine><itemref idref="t"/></spine></package>"""
        val ep = epub(
            mapOf(
                "META-INF/container.xml" to container, "OEBPS/content.opf" to opfPage,
                "OEBPS/text/title.xhtml" to xhtml("<div><img src=\"../img/a.gif\"/></div>"), "OEBPS/img/a.gif" to "GIF",
            )
        )
        assertEquals("GIF", EpubParser.coverImage(ep)?.decodeToString())
    }

    @Test
    fun resolvesRelativePaths() {
        assertEquals("OEBPS/img/a b.png", EpubParser.resolve("OEBPS/text", "../img/a%20b.png"))
        assertEquals("x.html", EpubParser.resolve("", "./x.html"))
    }
}
