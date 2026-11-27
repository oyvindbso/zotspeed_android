package com.zotspeed.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.zotspeed.epub.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Book covers. Preference order: the cover inside the downloaded EPUB, then Open Library's
 * cover for the item's ISBN (if online covers are enabled). Results — including misses —
 * are cached on disk so each source is asked once.
 */
class CoverRepository(context: Context, private val library: LibraryRepository) {
    private val dir = File(context.filesDir, "covers").apply { mkdirs() }
    private val memory = LruCache<String, ImageBitmap>(80)
    private val network = Semaphore(3)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun file(bookId: String, source: String) = File(dir, "$bookId.$source")
    private fun miss(bookId: String, source: String) = File(dir, "$bookId.$source.none")

    /** Returns the cover, or null when there is none (callers show a generated placeholder). */
    suspend fun cover(bookId: String, entry: BookEntry, online: Boolean): ImageBitmap? {
        val downloaded = library.isDownloaded(bookId)
        val memKey = "$bookId/$downloaded/$online"
        memory.get(memKey)?.let { return it }
        val bmp = withContext(Dispatchers.IO) { load(bookId, entry, downloaded, online) }
        if (bmp != null) memory.put(memKey, bmp)
        return bmp
    }

    private suspend fun load(bookId: String, entry: BookEntry, downloaded: Boolean, online: Boolean): ImageBitmap? {
        // 1. Cover embedded in the EPUB.
        if (downloaded) {
            val f = file(bookId, "epub")
            if (!f.exists() && !miss(bookId, "epub").exists()) {
                val bytes = EpubParser.coverImage(library.bookFile(bookId))
                if (bytes != null && decode(bytes) != null) f.writeBytes(bytes) else miss(bookId, "epub").createNewFile()
            }
            if (f.exists()) decode(f.readBytes())?.let { return it }
        }
        // 2. Open Library by ISBN.
        if (entry.isbn.isEmpty()) return null
        val f = file(bookId, "ol")
        if (f.exists()) return decode(f.readBytes())
        val m = miss(bookId, "ol")
        val retryAfter = 7L * 24 * 3600 * 1000
        if (!online || (m.exists() && System.currentTimeMillis() - m.lastModified() < retryAfter)) return null
        val bytes = network.withPermit { fetchOpenLibrary(entry.isbn) }
        if (bytes != null && decode(bytes) != null) {
            f.writeBytes(bytes)
            m.delete()
            return decode(bytes)
        }
        m.createNewFile()
        m.setLastModified(System.currentTimeMillis())
        return null
    }

    private fun fetchOpenLibrary(isbn: String): ByteArray? = runCatching {
        // default=false makes Open Library return 404 instead of a blank image when it has no cover.
        val req = Request.Builder().url("https://covers.openlibrary.org/b/isbn/$isbn-M.jpg?default=false").build()
        http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()

    /** Decodes, downsampled to roughly thumbnail size to keep memory low. */
    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= TARGET_HEIGHT) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

    /** Forget the EPUB-derived cover (when the local copy is removed). */
    fun deleteEpubCover(bookId: String) {
        file(bookId, "epub").delete()
        miss(bookId, "epub").delete()
        memory.evictAll()
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
        memory.evictAll()
    }

    private companion object {
        const val TARGET_HEIGHT = 360
    }
}
