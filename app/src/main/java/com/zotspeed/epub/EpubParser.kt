package com.zotspeed.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeFilter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * Minimal EPUB 2/3 reader: reads the OPF spine, the table of contents (EPUB 3 nav document
 * or EPUB 2 NCX) for chapter titles, and flattens each XHTML document to paragraphs.
 */
object EpubParser {

    fun parse(file: File): Book = ZipFile(file).use { zip ->
        parse { name -> zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }
    }

    /** [read] returns the bytes of a zip entry by its path, or null if it does not exist. */
    fun parse(read: (String) -> ByteArray?): Book {
        val container = read("META-INF/container.xml")
            ?: throw IOException("Not an EPUB: META-INF/container.xml is missing")
        val opfPath = xml(container).allByLocalName("rootfile")
            .firstOrNull { it.attr("full-path").isNotBlank() }
            ?.attr("full-path")
            ?: throw IOException("EPUB container has no rootfile")
        val opfBytes = read(opfPath) ?: throw IOException("Missing package document $opfPath")
        val opf = xml(opfBytes)
        val opfDir = opfPath.substringBeforeLast('/', "")

        val title = opf.allByLocalName("title").firstOrNull()?.text()?.trim().orEmpty()
        val author = opf.allByLocalName("creator").map { it.text().trim() }
            .filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }

        data class Item(val id: String, val path: String, val mediaType: String, val properties: String)

        val items = opf.allByLocalName("item").map {
            Item(
                id = it.attr("id"),
                path = resolve(opfDir, it.attr("href")),
                mediaType = it.attr("media-type"),
                properties = it.attr("properties"),
            )
        }
        val byId = items.associateBy { it.id }
        val spineEl = opf.allByLocalName("spine").firstOrNull()
        val spine = opf.allByLocalName("itemref")
            .filter { it.attr("linear") != "no" }
            .mapNotNull { byId[it.attr("idref")] }
            .filter { it.mediaType.contains("html") || it.path.endsWith("htm") || it.path.endsWith("html") }

        // Table of contents: path (without fragment) -> first title pointing at it.
        val toc = LinkedHashMap<String, String>()
        items.firstOrNull { it.properties.split(' ').contains("nav") }?.let { nav ->
            read(nav.path)?.let { parseNav(it, nav.path.substringBeforeLast('/', ""), toc) }
        }
        if (toc.isEmpty()) {
            val ncx = spineEl?.attr("toc")?.let { byId[it] }
                ?: items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            ncx?.let { n -> read(n.path)?.let { parseNcx(it, n.path.substringBeforeLast('/', ""), toc) } }
        }

        // Build chapters. Spine documents that the TOC does not mention are treated as
        // continuations of the previous chapter (common when books are split into many files).
        val useToc = spine.any { toc.containsKey(it.path) }
        val chapters = ArrayList<Chapter>()
        var curTitle: String? = null
        var curParas = ArrayList<Paragraph>()
        fun flush() {
            if (curParas.isNotEmpty()) {
                val t = curTitle
                    ?: curParas.firstOrNull { it.heading }?.text
                    ?: "Section ${chapters.size + 1}"
                chapters += Chapter(t.take(200), curParas)
            }
            curParas = ArrayList()
            curTitle = null
        }
        for (item in spine) {
            val bytes = read(item.path) ?: continue
            val paras = extractParagraphs(bytes)
            val tocTitle = toc[item.path]
            if (tocTitle != null || !useToc || chapters.isEmpty() && curParas.isEmpty()) {
                flush()
                curTitle = tocTitle
            }
            curParas.addAll(paras)
        }
        flush()

        return Book(title = title.ifEmpty { "Untitled" }, author = author, chapters = chapters)
    }

    private val BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "section", "article",
        "header", "footer", "aside", "pre", "tr", "dd", "dt", "figcaption", "br", "hr", "table",
        "ul", "ol", "dl", "nav", "figure", "body", "main", "caption", "td", "th",
    )
    private val SKIP_TAGS = setOf("script", "style", "head", "svg", "math", "rt", "rp")
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val SPACES = Regex("[\\s\\u00A0\\u200B]+")

    /** Flattens an (X)HTML document into paragraphs of plain text. */
    fun extractParagraphs(html: ByteArray): List<Paragraph> {
        val doc = Jsoup.parse(ByteArrayInputStream(html), null, "")
        val body = doc.body()
        val out = ArrayList<Paragraph>()
        val sb = StringBuilder()
        var headingDepth = 0
        var bufferHeading = false

        fun flush() {
            val text = sb.toString().replace(SPACES, " ").trim()
            if (text.isNotEmpty()) out += Paragraph(text, bufferHeading)
            sb.setLength(0)
            bufferHeading = headingDepth > 0
        }

        body.filter(object : NodeFilter {
            override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                when (node) {
                    is TextNode -> {
                        if (sb.isEmpty()) bufferHeading = headingDepth > 0
                        sb.append(node.wholeText)
                    }
                    is Element -> {
                        val tag = node.normalName()
                        if (tag in SKIP_TAGS) return NodeFilter.FilterResult.SKIP_ENTIRELY
                        if (tag in BLOCK_TAGS) flush()
                        if (tag in HEADING_TAGS) {
                            headingDepth++
                            bufferHeading = true
                        }
                    }
                }
                return NodeFilter.FilterResult.CONTINUE
            }

            override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
                if (node is Element) {
                    val tag = node.normalName()
                    if (tag in BLOCK_TAGS || tag in HEADING_TAGS) {
                        flush()
                    }
                    if (tag in HEADING_TAGS) {
                        headingDepth--
                        bufferHeading = headingDepth > 0
                    }
                }
                return NodeFilter.FilterResult.CONTINUE
            }
        })
        flush()
        return out
    }

    private fun parseNav(bytes: ByteArray, dir: String, toc: MutableMap<String, String>) {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
        val navs = doc.getElementsByTag("nav")
        val tocNav = navs.firstOrNull { it.attr("epub:type").split(' ').contains("toc") || it.attr("role") == "doc-toc" }
            ?: navs.firstOrNull()
            ?: return
        for (a in tocNav.select("a[href]")) {
            val label = a.text().replace(SPACES, " ").trim()
            if (label.isEmpty()) continue
            val path = resolve(dir, a.attr("href").substringBefore('#'))
            toc.putIfAbsent(path, label)
        }
    }

    private fun parseNcx(bytes: ByteArray, dir: String, toc: MutableMap<String, String>) {
        val doc = xml(bytes)
        // Document order of navPoints is the reading order, including nested ones.
        for (np in doc.allByLocalName("navPoint")) {
            val label = np.children().firstOrNull { it.localName() == "navlabel" }
                ?.text()?.replace(SPACES, " ")?.trim().orEmpty()
            val src = np.children().firstOrNull { it.localName() == "content" }?.attr("src").orEmpty()
            if (label.isEmpty() || src.isEmpty()) continue
            toc.putIfAbsent(resolve(dir, src.substringBefore('#')), label)
        }
    }

    private fun xml(bytes: ByteArray): Document =
        Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())

    private fun Element.localName(): String = tagName().substringAfter(':').lowercase()

    private fun Document.allByLocalName(name: String): List<Element> =
        getAllElements().filter { it.localName() == name.lowercase() }

    /** Resolves [href] relative to directory [dir] inside the zip, handling ./ ../ and %-escapes. */
    fun resolve(dir: String, href: String): String {
        val decoded = try {
            URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            href
        }
        val parts = ArrayList<String>()
        if (!decoded.startsWith("/")) dir.split('/').filter { it.isNotEmpty() }.forEach { parts += it }
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }
}
