package com.zotspeed.data

import android.content.Context
import androidx.core.content.edit

enum class ReadMode { RSVP, PAGED, SCROLL }

enum class ThemeMode { SYSTEM, LIGHT, SEPIA, DARK, BLACK }

data class ReaderSettings(
    val wpm: Int = 300,
    val rsvpFontSp: Int = 40,
    val textFontSp: Int = 18,
    val serif: Boolean = true,
    val lineSpacing: Float = 1.45f,
    val theme: ThemeMode = ThemeMode.BLACK,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = true,
    val showOverLockScreen: Boolean = false,
    val rsvpContext: Boolean = true,
)

data class Position(val chapter: Int, val offset: Int)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("zotspeed", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "").orEmpty()
        set(v) = sp.edit { putString("apiKey", v) }

    var userId: Long
        get() = sp.getLong("userId", 0L)
        set(v) = sp.edit { putLong("userId", v) }

    var username: String
        get() = sp.getString("username", "").orEmpty()
        set(v) = sp.edit { putString("username", v) }

    /** Selected library as "user_123" / "group_456". */
    var libraryId: String
        get() = sp.getString("libraryId", "").orEmpty()
        set(v) = sp.edit { putString("libraryId", v) }

    var libraryName: String
        get() = sp.getString("libraryName", "My Library").orEmpty()
        set(v) = sp.edit { putString("libraryName", v) }

    var readMode: ReadMode
        get() = runCatching { ReadMode.valueOf(sp.getString("readMode", null)!!) }.getOrDefault(ReadMode.RSVP)
        set(v) = sp.edit { putString("readMode", v.name) }

    var lastBook: String
        get() = sp.getString("lastBook", "").orEmpty()
        set(v) = sp.edit { putString("lastBook", v) }

    var reader: ReaderSettings
        get() {
            val d = ReaderSettings()
            return ReaderSettings(
                wpm = sp.getInt("wpm", d.wpm),
                rsvpFontSp = sp.getInt("rsvpFontSp", d.rsvpFontSp),
                textFontSp = sp.getInt("textFontSp", d.textFontSp),
                serif = sp.getBoolean("serif", d.serif),
                lineSpacing = sp.getFloat("lineSpacing", d.lineSpacing),
                theme = runCatching { ThemeMode.valueOf(sp.getString("theme", null)!!) }.getOrDefault(d.theme),
                volumeKeys = sp.getBoolean("volumeKeys", d.volumeKeys),
                keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
                showOverLockScreen = sp.getBoolean("showOverLockScreen", d.showOverLockScreen),
                rsvpContext = sp.getBoolean("rsvpContext", d.rsvpContext),
            )
        }
        set(v) = sp.edit {
            putInt("wpm", v.wpm)
            putInt("rsvpFontSp", v.rsvpFontSp)
            putInt("textFontSp", v.textFontSp)
            putBoolean("serif", v.serif)
            putFloat("lineSpacing", v.lineSpacing)
            putString("theme", v.theme.name)
            putBoolean("volumeKeys", v.volumeKeys)
            putBoolean("keepScreenOn", v.keepScreenOn)
            putBoolean("showOverLockScreen", v.showOverLockScreen)
            putBoolean("rsvpContext", v.rsvpContext)
        }

    fun position(bookId: String): Position {
        val s = sp.getString("pos_$bookId", null) ?: return Position(0, 0)
        val parts = s.split(':')
        return Position(parts.getOrNull(0)?.toIntOrNull() ?: 0, parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun setPosition(bookId: String, p: Position) = sp.edit { putString("pos_$bookId", "${p.chapter}:${p.offset}") }

    fun progress(bookId: String): Float = sp.getFloat("progress_$bookId", -1f)

    fun setProgress(bookId: String, value: Float) = sp.edit { putFloat("progress_$bookId", value) }

    fun clearAccount() = sp.edit {
        remove("apiKey"); remove("userId"); remove("username"); remove("libraryId"); remove("libraryName")
    }
}
