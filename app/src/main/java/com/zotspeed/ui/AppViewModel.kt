package com.zotspeed.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zotspeed.data.BookEntry
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
)

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
                repo.save(lib, LibraryRepository.Cached(res.entries, res.version, keepGroups))
                if (lib.type != "user" && groups != null) {
                    // Group list is kept with the user's own library cache.
                    val u = userCache ?: LibraryRepository.Cached(emptyList(), 0, groups)
                    repo.save(userLibrary(), u.copy(groups = groups))
                }
                _library.update {
                    it.copy(
                        entries = if (it.current == lib) res.entries else it.entries,
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
        refreshLocalState()
    }

    fun open(entry: BookEntry) {
        val lib = _library.value.current ?: return
        val c = client ?: return
        val id = repo.bookId(lib, entry.key)
        if (id in _library.value.downloading) return
        viewModelScope.launch {
            val needsDownload = !repo.isDownloaded(id) ||
                (entry.md5 != null && repo.localMd5(id) != null && repo.localMd5(id) != entry.md5)
            if (needsDownload) {
                _library.update { it.copy(downloading = it.downloading + (id to -1f), error = null) }
                try {
                    c.download(lib, entry.key, repo.bookFile(id)) { p ->
                        _library.update { it.copy(downloading = it.downloading + (id to p)) }
                    }
                    repo.setLocalMd5(id, entry.md5)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = if (e is com.zotspeed.data.ZoteroException && e.code == 404) {
                        "File not found on zotero.org. Books stored via WebDAV or as linked files can't be downloaded through the Zotero API."
                    } else e.message ?: e.toString()
                    if (!repo.isDownloaded(id)) {
                        _library.update { it.copy(downloading = it.downloading - id, error = msg) }
                        return@launch
                    }
                } finally {
                    _library.update { it.copy(downloading = it.downloading - id) }
                }
                refreshLocalState()
            }
            openBook(id, entry.title)
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
