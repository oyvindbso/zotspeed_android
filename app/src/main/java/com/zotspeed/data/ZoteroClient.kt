package com.zotspeed.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ZoteroException(message: String, val code: Int = 0) : IOException(message)

data class KeyInfo(val userId: Long, val username: String)

/** A Zotero library: the user's own library or a group library. */
data class ZoteroLibrary(val type: String, val id: Long, val name: String) {
    /** URL path prefix, e.g. "users/123" or "groups/456". */
    val prefix: String get() = "${if (type == "group") "groups" else "users"}/$id"
    val storageId: String get() = "${type}_$id"
}

/** An EPUB attachment in a Zotero library, with its parent item's metadata. */
data class BookEntry(
    val key: String,
    val parentKey: String?,
    val title: String,
    val authors: String,
    val year: String,
    val filename: String,
    val md5: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("parentKey", parentKey ?: JSONObject.NULL).put("title", title)
        .put("authors", authors).put("year", year).put("filename", filename)
        .put("md5", md5 ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = BookEntry(
            key = o.getString("key"),
            parentKey = o.optStringOrNull("parentKey"),
            title = o.optString("title"),
            authors = o.optString("authors"),
            year = o.optString("year"),
            filename = o.optString("filename"),
            md5 = o.optStringOrNull("md5"),
        )
    }
}

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).ifEmpty { null }

/**
 * Small client for the Zotero Web API v3 (https://www.zotero.org/support/dev/web_api/v3/start).
 */
class ZoteroClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun request(url: String): Request = Request.Builder()
        .url(if (url.startsWith("http")) url else "$BASE/$url")
        .header("Zotero-API-Version", "3")
        .header("Zotero-API-Key", apiKey)
        .build()

    /** Executes with simple handling of Zotero's Backoff / Retry-After rate limiting. */
    private suspend fun execute(url: String): Response {
        repeat(4) { attempt ->
            val resp = withContext(Dispatchers.IO) { http.newCall(request(url)).execute() }
            val retryAfter = resp.header("Retry-After")?.toLongOrNull()
            if ((resp.code == 429 || resp.code == 503) && attempt < 3) {
                resp.close()
                delay((retryAfter ?: (2L shl attempt)) * 1000)
                return@repeat
            }
            resp.header("Backoff")?.toLongOrNull()?.let { delay(it.coerceAtMost(30) * 1000) }
            if (!resp.isSuccessful) {
                val code = resp.code
                val body = runCatching { resp.body?.string() }.getOrNull().orEmpty().take(200)
                resp.close()
                throw ZoteroException(
                    when (code) {
                        403 -> "Access denied (403). Check that the API key allows library access. $body"
                        404 -> "Not found (404). $body"
                        else -> "Zotero returned HTTP $code. $body"
                    }.trim(),
                    code,
                )
            }
            return resp
        }
        throw ZoteroException("Zotero is rate limiting requests; try again later")
    }

    private suspend fun getJson(url: String): Pair<String, Response> {
        val resp = execute(url)
        val body = withContext(Dispatchers.IO) { resp.body?.string().orEmpty() }
        resp.close()
        return body to resp
    }

    suspend fun keyInfo(): KeyInfo {
        val (body, _) = try {
            getJson("keys/current")
        } catch (e: ZoteroException) {
            if (e.code == 404 || e.code == 400) getJson("keys/$apiKey") else throw e
        }
        val o = JSONObject(body)
        return KeyInfo(o.getLong("userID"), o.optString("username"))
    }

    /** Revokes this API key (used on sign-out for keys created through OAuth). */
    suspend fun revokeKey() = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/keys/current")
            .header("Zotero-API-Version", "3").header("Zotero-API-Key", apiKey)
            .delete().build()
        http.newCall(req).execute().close()
    }

    suspend fun groups(userId: Long): List<ZoteroLibrary> {
        val (body, _) = getJson("users/$userId/groups?limit=100")
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val data = o.optJSONObject("data")
            ZoteroLibrary("group", o.getLong("id"), data?.optString("name") ?: "Group ${o.getLong("id")}")
        }
    }

    data class SyncResult(val entries: List<BookEntry>, val version: Long)

    /**
     * Returns all EPUB attachments in [lib]. When [previous] is given together with its
     * [sinceVersion], only changes since that version are fetched and merged in.
     */
    suspend fun syncEpubs(
        lib: ZoteroLibrary,
        previous: List<BookEntry>?,
        sinceVersion: Long,
        onProgress: (String) -> Unit = {},
    ): SyncResult {
        val incremental = previous != null && sinceVersion > 0
        val since = if (incremental) "&since=$sinceVersion" else ""

        // 1. Attachments (all, or only the ones changed since last sync).
        val changedAttachments = ArrayList<JSONObject>()
        var start = 0
        var total = Int.MAX_VALUE
        var libraryVersion = sinceVersion
        while (start < total) {
            onProgress(if (total == Int.MAX_VALUE) "Fetching attachments…" else "Fetching attachments $start/$total…")
            val (body, resp) = getJson("${lib.prefix}/items?itemType=attachment&limit=100&start=$start$since")
            total = resp.header("Total-Results")?.toIntOrNull() ?: 0
            resp.header("Last-Modified-Version")?.toLongOrNull()?.let { libraryVersion = maxOf(libraryVersion, it) }
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) changedAttachments += arr.getJSONObject(i)
            if (arr.length() == 0) break
            start += arr.length()
        }

        val result = LinkedHashMap<String, BookEntry>()
        previous?.takeIf { incremental }?.forEach { result[it.key] = it }

        val newEpubs = ArrayList<JSONObject>()
        for (item in changedAttachments) {
            val data = item.getJSONObject("data")
            val key = data.getString("key")
            if (isDownloadableEpub(data)) newEpubs += data else result.remove(key)
        }

        // 2. Deletions since last sync.
        if (incremental) {
            onProgress("Checking deletions…")
            val (body, resp) = getJson("${lib.prefix}/deleted?since=$sinceVersion")
            resp.header("Last-Modified-Version")?.toLongOrNull()?.let { libraryVersion = maxOf(libraryVersion, it) }
            val deleted = JSONObject(body).optJSONArray("items") ?: JSONArray()
            for (i in 0 until deleted.length()) result.remove(deleted.getString(i))
        }

        // 3. Parent metadata: for new attachments, and for parents edited since last sync.
        val parentKeys = LinkedHashSet<String>()
        newEpubs.mapNotNullTo(parentKeys) { it.optStringOrNull("parentItem") }
        if (incremental) {
            val (body, _) = getJson("${lib.prefix}/items?since=$sinceVersion&format=versions")
            val changed = JSONObject(body).keys().asSequence().toSet()
            result.values.mapNotNull { it.parentKey }.filterTo(parentKeys) { it in changed }
        }
        val parents = HashMap<String, JSONObject>()
        parentKeys.chunked(50).forEachIndexed { i, chunk ->
            onProgress("Fetching metadata ${i * 50}/${parentKeys.size}…")
            val (body, _) = getJson("${lib.prefix}/items?itemKey=${chunk.joinToString(",")}&limit=50")
            val arr = JSONArray(body)
            for (j in 0 until arr.length()) {
                val d = arr.getJSONObject(j).getJSONObject("data")
                parents[d.getString("key")] = d
            }
        }

        for (data in newEpubs) {
            val parentKey = data.optStringOrNull("parentItem")
            result[data.getString("key")] = entryFrom(data, parentKey?.let { parents[it] })
        }
        // Refresh metadata of existing entries whose parent changed.
        for ((k, e) in result.entries.toList()) {
            val p = e.parentKey?.let { parents[it] } ?: continue
            if (newEpubs.none { it.getString("key") == k }) {
                result[k] = e.copy(title = parentTitle(p) ?: e.title, authors = authors(p), year = year(p))
            }
        }

        return SyncResult(result.values.sortedBy { it.title.lowercase() }, libraryVersion)
    }

    private fun isDownloadableEpub(data: JSONObject): Boolean {
        val ct = data.optString("contentType")
        val fn = data.optString("filename")
        val isEpub = ct == "application/epub+zip" || fn.endsWith(".epub", ignoreCase = true)
        val link = data.optString("linkMode")
        return isEpub && (link == "imported_file" || link == "imported_url")
    }

    private fun entryFrom(data: JSONObject, parent: JSONObject?): BookEntry {
        val attTitle = data.optString("title").ifEmpty { data.optString("filename") }
        return BookEntry(
            key = data.getString("key"),
            parentKey = data.optStringOrNull("parentItem"),
            title = parent?.let { parentTitle(it) } ?: attTitle.removeSuffix(".epub"),
            authors = parent?.let { authors(it) }.orEmpty(),
            year = parent?.let { year(it) }.orEmpty(),
            filename = data.optString("filename"),
            md5 = data.optStringOrNull("md5"),
        )
    }

    private fun parentTitle(p: JSONObject): String? = p.optString("title").ifEmpty { null }

    private fun authors(p: JSONObject): String {
        val creators = p.optJSONArray("creators") ?: return ""
        val names = (0 until creators.length()).map { creators.getJSONObject(it) }
            .sortedBy { if (it.optString("creatorType") == "author") 0 else 1 }
            .map { c -> c.optString("lastName").ifEmpty { c.optString("name") } }
            .filter { it.isNotEmpty() }
        return when {
            names.size > 3 -> "${names.first()} et al."
            else -> names.joinToString(", ")
        }
    }

    private fun year(p: JSONObject): String =
        Regex("\\b(\\d{4})\\b").find(p.optString("date"))?.groupValues?.get(1).orEmpty()

    /** Downloads the attachment file to [dest], reporting progress as 0..1 (or -1 if unknown). */
    suspend fun download(lib: ZoteroLibrary, key: String, dest: File, onProgress: (Float) -> Unit) {
        val resp = execute("${lib.prefix}/items/$key/file")
        withContext(Dispatchers.IO) {
            resp.use { r ->
                val body = r.body ?: throw ZoteroException("Empty response")
                val len = body.contentLength()
                val tmp = File(dest.parentFile, dest.name + ".part")
                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            onProgress(if (len > 0) read.toFloat() / len else -1f)
                        }
                    }
                }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    companion object {
        const val BASE = "https://api.zotero.org"
    }
}
