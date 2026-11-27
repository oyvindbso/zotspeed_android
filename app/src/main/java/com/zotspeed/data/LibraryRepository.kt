package com.zotspeed.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Caches the library listing and the downloaded EPUB files on local storage. */
class LibraryRepository(context: Context) {
    private val root = context.filesDir
    private val booksDir = File(root, "books").apply { mkdirs() }

    data class Cached(val entries: List<BookEntry>, val version: Long, val groups: List<ZoteroLibrary>)

    private fun cacheFile(lib: ZoteroLibrary) = File(root, "library_${lib.storageId}.json")

    suspend fun load(lib: ZoteroLibrary): Cached? = withContext(Dispatchers.IO) {
        val f = cacheFile(lib)
        if (!f.exists()) return@withContext null
        runCatching {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("entries")
            val groups = o.optJSONArray("groups") ?: JSONArray()
            Cached(
                entries = (0 until arr.length()).map { BookEntry.fromJson(arr.getJSONObject(it)) },
                version = o.optLong("version"),
                groups = (0 until groups.length()).map {
                    val g = groups.getJSONObject(it)
                    ZoteroLibrary("group", g.getLong("id"), g.getString("name"))
                },
            )
        }.getOrNull()
    }

    suspend fun save(lib: ZoteroLibrary, c: Cached) = withContext(Dispatchers.IO) {
        val o = JSONObject()
            .put("version", c.version)
            .put("entries", JSONArray().apply { c.entries.forEach { put(it.toJson()) } })
            .put("groups", JSONArray().apply {
                c.groups.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
            })
        val f = cacheFile(lib)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    fun bookId(lib: ZoteroLibrary, key: String) = "${lib.storageId}_$key"

    fun bookFile(bookId: String) = File(booksDir, "$bookId.epub")

    fun isDownloaded(bookId: String) = bookFile(bookId).exists()

    fun downloadedIds(): Set<String> =
        booksDir.listFiles()?.filter { it.name.endsWith(".epub") }?.map { it.name.removeSuffix(".epub") }?.toSet()
            ?: emptySet()

    /** Records which md5 the local copy has, so changed files in Zotero get re-downloaded. */
    fun localMd5(bookId: String): String? = File(booksDir, "$bookId.md5").takeIf { it.exists() }?.readText()

    fun setLocalMd5(bookId: String, md5: String?) {
        val f = File(booksDir, "$bookId.md5")
        if (md5 == null) f.delete() else f.writeText(md5)
    }

    fun delete(bookId: String) {
        bookFile(bookId).delete()
        File(booksDir, "$bookId.md5").delete()
    }

    fun clearAll() {
        booksDir.listFiles()?.forEach { it.delete() }
        root.listFiles()?.filter { it.name.startsWith("library_") }?.forEach { it.delete() }
    }
}
