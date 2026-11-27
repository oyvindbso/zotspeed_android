package com.zotspeed

import com.zotspeed.data.ZoteroClient
import com.zotspeed.data.ZoteroLibrary
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Full and incremental sync against a fake Zotero API (Robolectric for Android's org.json). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZoteroSyncTest {
    private lateinit var server: MockWebServer
    private val paths = ArrayList<String>()
    private val lib = ZoteroLibrary("user", 1, "My Library")

    private fun att(key: String, parent: String?, ct: String, file: String, cols: String = "[]", link: String = "imported_file") =
        """{"key":"$key","data":{"key":"$key","itemType":"attachment","parentItem":${parent?.let { "\"$it\"" } ?: "false"},
           "linkMode":"$link","contentType":"$ct","filename":"$file","title":"$file","md5":"m$key","collections":$cols}}"""

    private var attachments = ""
    private var parents = ""
    private var changedVersions = "{}"
    private var deleted = """{"items":[]}"""

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                paths += p
                fun json(body: String, total: Int = 0) = MockResponse().setBody(body)
                    .addHeader("Total-Results", total).addHeader("Last-Modified-Version", 42)
                return when {
                    p.startsWith("/users/1/items?itemType=attachment") -> json("[$attachments]", attachments.split("\"itemType\"").size - 1)
                    p.startsWith("/users/1/items?itemKey=") -> json("[$parents]")
                    p.contains("format=versions") -> json(changedVersions)
                    p.startsWith("/users/1/deleted") -> json(deleted)
                    p.startsWith("/users/1/collections") -> json(
                        """[{"key":"C1","data":{"key":"C1","name":"Novels","parentCollection":false}},
                            {"key":"C2","data":{"key":"C2","name":"Sci-fi","parentCollection":"C1"}}]""", 2,
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    @Test
    fun fullThenIncrementalSync() = runBlocking {
        attachments = listOf(
            att("A1", "P1", "application/epub+zip", "wells.epub"),
            att("A2", "P2", "application/pdf", "paper.pdf"),
            att("A3", null, "application/epub+zip", "loose.epub", cols = """["C2"]"""),
            att("A4", "P1", "application/epub+zip", "linked.epub", link = "linked_file"),
        ).joinToString(",")
        parents = """{"key":"P1","data":{"key":"P1","title":"The War of the Worlds","date":"1898",
            "ISBN":"978-0-14-144103-9 0141441038","collections":["C1"],
            "creators":[{"creatorType":"author","firstName":"H. G.","lastName":"Wells"}]}}"""

        val client = ZoteroClient("k", server.url("/").toString().trimEnd('/'))
        val full = client.syncEpubs(lib, null, 0)
        assertEquals(42L, full.version)
        assertEquals(listOf("A3", "A1"), full.entries.map { it.key }) // sorted by title: "loose" < "The War…"
        val wells = full.entries.first { it.key == "A1" }
        assertEquals("The War of the Worlds", wells.title)
        assertEquals("Wells", wells.authors)
        assertEquals("1898", wells.year)
        assertEquals("9780141441039", wells.isbn)
        assertEquals(listOf("C1"), wells.collections)
        assertEquals(listOf("C2"), full.entries.first { it.key == "A3" }.collections)
        assertEquals(listOf("Novels", "Sci-fi"), full.collections.map { it.name })
        assertEquals("C1", full.collections.first { it.key == "C2" }.parent)
        assertEquals(null, full.collections.first { it.key == "C1" }.parent)

        // Incremental: P1 moved to C2, A3 deleted, nothing else changed.
        paths.clear()
        attachments = ""
        changedVersions = """{"P1":43}"""
        deleted = """{"items":["A3"]}"""
        parents = parents.replace("""["C1"]""", """["C2"]""")
        val inc = client.syncEpubs(lib, full.entries, full.version)
        assertEquals(listOf("A1"), inc.entries.map { it.key })
        assertEquals(listOf("C2"), inc.entries.single().collections)
        assertEquals(true, paths.any { it.contains("since=42") })
    }
}
