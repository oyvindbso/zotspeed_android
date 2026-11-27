package com.zotspeed.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import com.zotspeed.data.BookEntry
import com.zotspeed.data.CoverRepository
import com.zotspeed.data.SortKey
import com.zotspeed.data.SortOrder
import com.zotspeed.data.ZoteroCollection
import com.zotspeed.data.LibraryRepository
import com.zotspeed.data.Position
import com.zotspeed.data.Prefs
import com.zotspeed.data.ReadMode
import com.zotspeed.data.ReaderSettings
import com.zotspeed.data.ZoteroClient
import com.zotspeed.data.ZoteroLibrary
import com.zotspeed.data.ZoteroOAuth
import com.zotspeed.BuildConfig
import com.zotspeed.epub.Book
import com.zotspeed.epub.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { SETUP, LIBRARY, READER }

enum class ReaderKey { NEXT, PREVIOUS }

data class LibraryUi(
    val libraries: List<ZoteroLibrary> = emptyList(),
    val current: ZoteroLibrary? = null,
    val entries: List<BookEntry> = emptyList(),
    val downloaded: Set<String> = emptySet(),
    val progress: Map<String, Float> = emptyMap(),
    val syncing: Boolean = false,
    val syncMessage: String? = null,
    val error: String? = null,
    /** bookId -> download progress (0..1, or -1 when unknown). */
    val downloading: Map<String, Float> = emptyMap(),
    val lastBook: Pair<String, String>? = null, // bookId to title
    val collections: List<ZoteroCollection> = emptyList(),
    /** Open tabs: collection keys, or [ALL_BOOKS]. Empty means the user still has to choose. */
    val tabs: List<String> = emptyList(),
    val activeTab: Int = 0,
    /** Set after the first sync of a library, so we know whether there are collections to choose from. */
    val synced: Boolean = false,
    val gridView: Boolean = true,
    val sort: SortOrder = SortOrder(),
    val onlineCovers: Boolean = true,
    /** Remaining books in a running "download all". */
    val batchRemaining: Int = 0,
) {
    /** Collection key of the active tab; null for all books. */
    val collection: String?
        get() = tabs.getOrNull(activeTab)?.takeIf { it != ALL_BOOKS }

    /** True when the user should be asked which collection to open. */
    val needsCollectionChoice: Boolean
        get() = current != null && tabs.isEmpty() && collections.isNotEmpty()

    fun tabName(key: String): String =
        if (key == ALL_BOOKS) "All books" else collections.firstOrNull { it.key == key }?.name ?: "…"

    /** Books in the active tab's collection, including its subcollections. */
    val visibleEntries: List<BookEntry>
        get() = entriesIn(collection)

    fun entriesIn(collectionKey: String?): List<BookEntry> {
        val root = collectionKey ?: return entries
        val keys = HashSet<String>().apply { add(root) }
        var grew = true
        while (grew) {
            grew = false
            for (c in collections) if (c.parent in keys && keys.add(c.key)) grew = true
        }
        return entries.filter { e -> e.collections.any { it in keys } }
    }

    companion object {
        const val ALL_BOOKS = "*"
    }
}

data class ReaderUi(
    val bookId: String = "",
    val title: String = "",
    val book: Book? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val repo = LibraryRepository(app)
    private val covers = CoverRepository(app, repo)

    private val _screen = MutableStateFlow(if (prefs.apiKey.isBlank()) Screen.SETUP else Screen.LIBRARY)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _setupBusy = MutableStateFlow(false)
    val setupBusy: StateFlow<Boolean> = _setupBusy.asStateFlow()
    private val _setupError = MutableStateFlow<String?>(null)
    val setupError: StateFlow<String?> = _setupError.asStateFlow()

    private val _library = MutableStateFlow(LibraryUi())
    val library: StateFlow<LibraryUi> = _library.asStateFlow()

    private val _reader = MutableStateFlow(ReaderUi())
    val reader: StateFlow<ReaderUi> = _reader.asStateFlow()

    private val _position = MutableStateFlow(Position(0, 0))
    val position: StateFlow<Position> = _position.asStateFlow()

    private val _mode = MutableStateFlow(prefs.readMode)
    val mode: StateFlow<ReadMode> = _mode.asStateFlow()

    private val _settings = MutableStateFlow(prefs.reader)
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    private val _keys = MutableSharedFlow<ReaderKey>(extraBufferCapacity = 8)
    val keys: SharedFlow<ReaderKey> = _keys

    private var client: ZoteroClient? = prefs.apiKey.takeIf { it.isNotBlank() }?.let { ZoteroClient(it) }
    private var syncJob: Job? = null
    private var lastSaved = 0L

    val username: String get() = prefs.username

    init {
        if (_screen.value == Screen.LIBRARY) initLibrary()
    }

    private fun userLibrary() = ZoteroLibrary("user", prefs.userId, "My Library")

    private fun initLibrary() {
        val selected = prefs.libraryId
        val lib = if (selected.startsWith("group_")) {
            ZoteroLibrary("group", selected.removePrefix("group_").toLong(), prefs.libraryName)
        } else userLibrary()
        viewModelScope.launch {
            val userCache = repo.load(userLibrary())
            val groups = userCache?.groups.orEmpty()
            _library.update { it.copy(libraries = listOf(userLibrary()) + groups) }
            showLibrary(lib, syncAfter = true)
        }
    }

    private suspend fun showLibrary(lib: ZoteroLibrary, syncAfter: Boolean) {
        val cached = repo.load(lib)
        _library.update {
            it.copy(
                current = lib,
                entries = cached?.entries.orEmpty(),
                collections = cached?.collections.orEmpty(),
                tabs = prefs.tabs(lib.storageId),
                activeTab = prefs.activeTab(lib.storageId),
                synced = cached != null && cached.version > 0,
                gridView = prefs.gridView,
                sort = prefs.sortOrder,
                onlineCovers = prefs.onlineCovers,
                error = null,
            )
        }
        refreshLocalState()
        if (syncAfter) sync()
    }

    private fun refreshLocalState() {
        val downloaded = repo.downloadedIds()
        val lib = _library.value.current
        val progress = if (lib == null) emptyMap() else _library.value.entries.associate {
            val id = repo.bookId(lib, it.key)
            id to prefs.progress(id)
        }.filterValues { it >= 0f }
        val last = prefs.lastBook.takeIf { it.isNotEmpty() && it in downloaded }
        val lastTitle = last?.let { id ->
            _library.value.entries.firstOrNull { lib != null && repo.bookId(lib, it.key) == id }?.title
        }
        _library.update {
            it.copy(
                downloaded = downloaded,
                progress = progress,
                lastBook = if (last != null && lastTitle != null) last to lastTitle else null,
            )
        }
    }

    // ---- Setup ----

    fun connect(apiKey: String) {
        val key = apiKey.trim()
        if (key.isEmpty()) return
        _setupBusy.value = true
        _setupError.value = null
        viewModelScope.launch {
            try {
                signIn(key, method = "key")
            } catch (e: Exception) {
                _setupError.value = e.message ?: e.toString()
            } finally {
                _setupBusy.value = false
            }
        }
    }

    private suspend fun signIn(key: String, method: String) {
        val c = ZoteroClient(key)
        val info = c.keyInfo()
        prefs.apiKey = key
        prefs.authMethod = method
        prefs.userId = info.userId
        prefs.username = info.username
        prefs.libraryId = "user_${info.userId}"
        prefs.libraryName = "My Library"
        client = c
        _screen.value = Screen.LIBRARY
        initLibrary()
    }

    // ---- OAuth ----

    private val builtInClient = BuildConfig.ZOTERO_CLIENT_KEY to BuildConfig.ZOTERO_CLIENT_SECRET

    /** True when OAuth client credentials are built into this APK. */
    val oauthBuiltIn: Boolean get() = builtInClient.first.isNotBlank() && builtInClient.second.isNotBlank()

    private val _oauthClient = MutableStateFlow(prefs.oauthClientKey to prefs.oauthClientSecret)
    /** Client key/secret entered in the app (used when none are built in). */
    val oauthClient: StateFlow<Pair<String, String>> = _oauthClient.asStateFlow()

    fun setOAuthClient(key: String, secret: String) {
        prefs.oauthClientKey = key.trim()
        prefs.oauthClientSecret = secret.trim()
        _oauthClient.value = prefs.oauthClientKey to prefs.oauthClientSecret
    }

    private fun oauth(): ZoteroOAuth? {
        val (k, s) = _oauthClient.value.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }
            ?: builtInClient.takeIf { oauthBuiltIn }
            ?: return null
        return ZoteroOAuth(k, s)
    }

    val oauthAvailable: Boolean get() = oauth() != null

    /** Starts "Sign in with Zotero": fetches a request token, then [openUrl] opens the approval page. */
    fun startOAuth(openUrl: (String) -> Unit) {
        val o = oauth() ?: run {
            _setupError.value = "Enter the OAuth client key and secret first."
            return
        }
        _setupBusy.value = true
        _setupError.value = null
        viewModelScope.launch {
            try {
                val rt = o.requestToken()
                prefs.oauthPending = rt.token to rt.secret
                openUrl(o.authorizeUrl(rt.token))
            } catch (e: Exception) {
                _setupError.value = e.message ?: e.toString()
            } finally {
                _setupBusy.value = false
            }
        }
    }

    /** Handles zotspeed://oauth?oauth_token=…&oauth_verifier=… after the user approves access. */
    fun handleOAuthCallback(token: String?, verifier: String?) {
        val pending = prefs.oauthPending
        val o = oauth()
        if (pending == null || o == null) return
        if (token == null || verifier == null || token != pending.first) {
            prefs.oauthPending = null
            _setupError.value = "Zotero sign-in was cancelled or did not complete."
            return
        }
        _setupBusy.value = true
        _setupError.value = null
        viewModelScope.launch {
            try {
                val at = o.accessToken(ZoteroOAuth.RequestToken(pending.first, pending.second), verifier)
                prefs.oauthPending = null
                signIn(at.apiKey, method = "oauth")
            } catch (e: Exception) {
                _setupError.value = e.message ?: e.toString()
            } finally {
                prefs.oauthPending = null
                _setupBusy.value = false
            }
        }
    }

    fun signOut() {
        syncJob?.cancel()
        // Keys created by "Sign in with Zotero" are revoked; pasted keys are left alone.
        val revoke = client.takeIf { prefs.authMethod == "oauth" }
        if (revoke != null) {
            // Outlives the ViewModel scope so the revoke isn't cancelled by navigation.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
                .launch { runCatching { revoke.revokeKey() } }
        }
        prefs.clearAccount()
        repo.clearAll()
        covers.clearAll()
        client = null
        _library.value = LibraryUi()
        _reader.value = ReaderUi()
        _screen.value = Screen.SETUP
    }

    // ---- Library ----

    fun selectLibrary(lib: ZoteroLibrary) {
        prefs.libraryId = lib.storageId
        prefs.libraryName = lib.name
        syncJob?.cancel()
        viewModelScope.launch { showLibrary(lib, syncAfter = true) }
    }

    fun sync(full: Boolean = false) {
        val c = client ?: return
        val lib = _library.value.current ?: return
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _library.update { it.copy(syncing = true, error = null, syncMessage = "Syncing…") }
            try {
                val cached = if (full) null else repo.load(lib)
                // Groups are listed alongside the user's library.
                val groups = runCatching { c.groups(prefs.userId) }.getOrNull()
                val res = c.syncEpubs(lib, cached?.entries, cached?.version ?: 0) { msg ->
                    _library.update { it.copy(syncMessage = msg) }
                }
                val userCache = repo.load(userLibrary())
                val keepGroups = groups ?: userCache?.groups.orEmpty()
                repo.save(lib, LibraryRepository.Cached(res.entries, res.version, keepGroups, res.collections))
                if (lib.type != "user" && groups != null) {
                    // Group list is kept with the user's own library cache.
                    val u = userCache ?: LibraryRepository.Cached(emptyList(), 0, groups)
                    repo.save(userLibrary(), u.copy(groups = groups))
                }
                _library.update {
                    if (it.current != lib) return@update it.copy(libraries = listOf(userLibrary()) + keepGroups)
                    // Close tabs whose collection was deleted in Zotero.
                    val active = it.tabs.getOrNull(it.activeTab)
                    var tabs = it.tabs.filter { k -> k == LibraryUi.ALL_BOOKS || res.collections.any { c -> c.key == k } }
                    // A library without collections has nothing to choose: show all books.
                    if (tabs.isEmpty() && res.collections.isEmpty()) tabs = listOf(LibraryUi.ALL_BOOKS)
                    val activeTab = tabs.indexOf(active).coerceAtLeast(0)
                    prefs.setTabs(lib.storageId, tabs, activeTab)
                    it.copy(
                        entries = res.entries,
                        collections = res.collections,
                        tabs = tabs,
                        activeTab = activeTab,
                        synced = true,
                        libraries = listOf(userLibrary()) + keepGroups,
                    )
                }
                refreshLocalState()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _library.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _library.update { it.copy(syncing = false, syncMessage = null) }
            }
        }
    }

    fun dismissError() = _library.update { it.copy(error = null) }

    fun bookIdFor(entry: BookEntry): String? = _library.value.current?.let { repo.bookId(it, entry.key) }

    fun deleteDownload(entry: BookEntry) {
        val id = bookIdFor(entry) ?: return
        repo.delete(id)
        covers.deleteEpubCover(id)
        refreshLocalState()
    }

    // ---- Collections, covers, view ----

    /** Opens [key] (a collection key or [LibraryUi.ALL_BOOKS]) in a tab, or switches to it if already open. */
    fun openTab(key: String) = updateTabs { tabs, _ ->
        val i = tabs.indexOf(key)
        if (i >= 0) tabs to i else (tabs + key) to tabs.size
    }

    fun selectTab(index: Int) = updateTabs { tabs, _ -> tabs to index.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)) }

    /** Closing the last tab brings back the collection prompt. */
    fun closeTab(index: Int) = updateTabs { tabs, active ->
        val rest = tabs.filterIndexed { i, _ -> i != index }
        val newActive = when {
            index < active -> active - 1
            index == active -> (active - 1).coerceAtLeast(0)
            else -> active
        }.coerceIn(0, (rest.size - 1).coerceAtLeast(0))
        rest to newActive
    }

    private fun updateTabs(change: (List<String>, Int) -> Pair<List<String>, Int>) {
        val lib = _library.value.current ?: return
        _library.update {
            val (tabs, active) = change(it.tabs, it.activeTab)
            prefs.setTabs(lib.storageId, tabs, active)
            it.copy(tabs = tabs, activeTab = active)
        }
    }

    /** Picking the current key again flips the direction. */
    fun setSort(key: SortKey) {
        val cur = _library.value.sort
        setSortOrder(if (cur.key == key) cur.copy(descending = !cur.descending) else SortOrder(key, cur.descending))
    }

    fun setSortOrder(order: SortOrder) {
        prefs.sortOrder = order
        _library.update { it.copy(sort = order) }
    }

    fun setGridView(grid: Boolean) {
        prefs.gridView = grid
        _library.update { it.copy(gridView = grid) }
    }

    fun setOnlineCovers(on: Boolean) {
        prefs.onlineCovers = on
        _library.update { it.copy(onlineCovers = on) }
    }

    suspend fun cover(entry: BookEntry): ImageBitmap? {
        val id = bookIdFor(entry) ?: return null
        return covers.cover(id, entry, _library.value.onlineCovers)
    }

    /** Downloads every book in the current view that isn't on the device yet. */
    fun downloadAll() {
        val lib = _library.value.current ?: return
        val todo = _library.value.visibleEntries.filter { !repo.isDownloaded(repo.bookId(lib, it.key)) }
        if (todo.isEmpty() || _library.value.batchRemaining > 0) return
        viewModelScope.launch {
            _library.update { it.copy(batchRemaining = todo.size) }
            var failed = 0
            for (e in todo) {
                if (!ensureDownloaded(lib, e, reportErrors = false)) failed++
                _library.update { it.copy(batchRemaining = it.batchRemaining - 1) }
            }
            refreshLocalState()
            if (failed > 0) {
                _library.update { it.copy(error = "$failed of ${todo.size} books could not be downloaded (WebDAV or linked files?).") }
            }
        }
    }

    /** Downloads [entry] if needed (or if it changed in Zotero). Returns true when a local copy exists. */
    private suspend fun ensureDownloaded(lib: ZoteroLibrary, entry: BookEntry, reportErrors: Boolean): Boolean {
        val c = client ?: return false
        val id = repo.bookId(lib, entry.key)
        val needsDownload = !repo.isDownloaded(id) ||
            (entry.md5 != null && repo.localMd5(id) != null && repo.localMd5(id) != entry.md5)
        if (!needsDownload) return true
        _library.update { it.copy(downloading = it.downloading + (id to -1f), error = if (reportErrors) null else it.error) }
        try {
            c.download(lib, entry.key, repo.bookFile(id)) { p ->
                _library.update { it.copy(downloading = it.downloading + (id to p)) }
            }
            repo.setLocalMd5(id, entry.md5)
            covers.deleteEpubCover(id) // re-extract from the new file
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reportErrors && !repo.isDownloaded(id)) {
                val msg = if (e is com.zotspeed.data.ZoteroException && e.code == 404) {
                    "File not found on zotero.org. Books stored via WebDAV or as linked files can't be downloaded through the Zotero API."
                } else e.message ?: e.toString()
                _library.update { it.copy(error = msg) }
            }
        } finally {
            _library.update { it.copy(downloading = it.downloading - id) }
        }
        refreshLocalState()
        return repo.isDownloaded(id)
    }

    fun open(entry: BookEntry) {
        val lib = _library.value.current ?: return
        val id = repo.bookId(lib, entry.key)
        if (id in _library.value.downloading) return
        viewModelScope.launch {
            if (ensureDownloaded(lib, entry, reportErrors = true)) openBook(id, entry.title)
        }
    }

    fun openLast() {
        val (id, title) = _library.value.lastBook ?: return
        viewModelScope.launch { openBook(id, title) }
    }

    internal suspend fun openBook(id: String, title: String) {
        prefs.lastBook = id
        _reader.value = ReaderUi(bookId = id, title = title, loading = true)
        _position.value = prefs.position(id)
        _screen.value = Screen.READER
        try {
            val book = withContext(Dispatchers.Default) { EpubParser.parse(repo.bookFile(id)) }
            if (book.chapters.isEmpty()) throw IllegalStateException("No readable text found in this EPUB")
            val p = _position.value
            _position.value = Position(p.chapter.coerceIn(0, book.chapters.lastIndex), p.offset.coerceAtLeast(0))
            _reader.value = ReaderUi(bookId = id, title = title, book = book)
        } catch (e: Exception) {
            _reader.value = ReaderUi(bookId = id, title = title, error = "Could not open book: ${e.message ?: e}")
        }
    }

    fun closeBook() {
        persistPosition()
        _screen.value = Screen.LIBRARY
        refreshLocalState()
    }

    // ---- Reader ----

    fun setPosition(p: Position) {
        if (p == _position.value) return
        _position.value = p
        val now = System.currentTimeMillis()
        if (now - lastSaved > 3000) persistPosition()
    }

    fun goToChapter(index: Int, atEnd: Boolean = false) {
        val book = _reader.value.book ?: return
        if (index !in book.chapters.indices) return
        _position.value = Position(index, if (atEnd) book.chapters[index].length else 0)
        persistPosition()
    }

    fun persistPosition() {
        val r = _reader.value
        if (r.bookId.isEmpty()) return
        val p = _position.value
        prefs.setPosition(r.bookId, p)
        r.book?.let { prefs.setProgress(r.bookId, it.progress(p.chapter, p.offset)) }
        lastSaved = System.currentTimeMillis()
    }

    fun setMode(m: ReadMode) {
        _mode.value = m
        prefs.readMode = m
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.reader = s
    }

    /** Called from the Activity for volume / page keys. Returns true if consumed. */
    fun onKey(key: ReaderKey): Boolean {
        if (_screen.value != Screen.READER || !_settings.value.volumeKeys) return false
        return _keys.tryEmit(key)
    }

    fun onBack(): Boolean = when (_screen.value) {
        Screen.READER -> {
            closeBook(); true
        }
        else -> false
    }
}
