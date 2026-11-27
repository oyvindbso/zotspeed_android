package com.zotspeed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.zotspeed.data.Position
import com.zotspeed.data.Prefs
import com.zotspeed.data.ReadMode
import com.zotspeed.data.ThemeMode
import com.zotspeed.ui.AppViewModel
import com.zotspeed.ui.LibraryScreen
import com.zotspeed.ui.ReaderScreen
import com.zotspeed.ui.SetupScreen
import com.zotspeed.ui.theme.ZotSpeedTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens at roughly the Galaxy Z Flip cover-screen size and writes PNGs to
 * $SCREENSHOT_DIR. Skipped unless SCREENSHOT_DIR (and SAMPLE_EPUB for the reader) are set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w340dp-h380dp-xxhdpi")
class CoverScreenRenderTest {
    @get:Rule val rule = createComposeRule()

    private val outDir = System.getenv("SCREENSHOT_DIR")?.let(::File)
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        assumeTrue(outDir != null)
        outDir!!.mkdirs()
    }

    @Suppress("UNCHECKED_CAST")
    private fun windowViews(): List<View> {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val instance = wmg.getMethod("getInstance").invoke(null)
        val field = wmg.getDeclaredField("mViews").apply { isAccessible = true }
        return (field.get(instance) as List<View>).toList()
    }

    /** Draws the top-most window (the dialog when [dialog]) into a PNG. */
    private fun save(name: String, dialog: Boolean = false) {
        rule.waitForIdle()
        val views = windowViews()
        val view = if (dialog) views.last() else views.first()
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bmp)) }
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun setup() {
        val vm = AppViewModel(app)
        rule.setContent { ZotSpeedTheme(ThemeMode.BLACK) { SetupScreen(vm) } }
        save("01_setup")
    }

    @Test
    fun library() {
        val prefs = Prefs(app)
        prefs.apiKey = "dummy"
        prefs.userId = 1
        prefs.username = "reader"
        val entries = JSONArray()
        listOf(
            Triple("The War of the Worlds", "Wells", "1898"),
            Triple("Thinking, Fast and Slow", "Kahneman", "2011"),
            Triple("The Structure of Scientific Revolutions", "Kuhn", "1962"),
            Triple("Gödel, Escher, Bach: An Eternal Golden Braid", "Hofstadter", "1979"),
            Triple("Seeing Like a State", "Scott", "1998"),
            Triple("The Origin of Species", "Darwin", "1859"),
        ).forEachIndexed { i, (t, a, y) ->
            entries.put(
                JSONObject().put("key", "K$i").put("parentKey", "P$i").put("title", t).put("authors", a)
                    .put("year", y).put("filename", "$i.epub").put("md5", JSONObject.NULL)
            )
        }
        File(app.filesDir, "library_user_1.json").writeText(
            JSONObject().put("version", 5).put("entries", entries)
                .put("groups", JSONArray().put(JSONObject().put("id", 9).put("name", "Reading group"))).toString()
        )
        File(app.filesDir, "books").mkdirs()
        File(app.filesDir, "books/user_1_K0.epub").writeText("x")
        File(app.filesDir, "books/user_1_K2.epub").writeText("x")
        prefs.setProgress("user_1_K0", 0.42f)
        prefs.lastBook = "user_1_K0"

        val vm = AppViewModel(app)
        rule.setContent { ZotSpeedTheme(ThemeMode.BLACK) { LibraryScreen(vm) } }
        rule.mainClock.advanceTimeBy(500)
        vm.dismissError()
        save("02_library")
    }

    private fun reader(mode: ReadMode, theme: ThemeMode, name: String, extra: (AppViewModel) -> Unit = {}) {
        val sample = System.getenv("SAMPLE_EPUB")?.let(::File)
        assumeTrue(sample != null && sample.exists())
        File(app.filesDir, "books").mkdirs()
        sample!!.copyTo(File(app.filesDir, "books/test.epub"), overwrite = true)
        Prefs(app).apiKey = "dummy"
        val vm = AppViewModel(app)
        vm.updateSettings { it.copy(theme = theme) }
        vm.setMode(mode)
        runBlocking { vm.openBook("test", "The War of the Worlds") }
        vm.setPosition(Position(3, 1520))
        rule.setContent { ZotSpeedTheme(theme) { ReaderScreen(vm) } }
        // Let background pagination finish.
        repeat(20) { Thread.sleep(100); rule.mainClock.advanceTimeBy(100); rule.waitForIdle() }
        extra(vm)
        save(name)
    }

    @Test fun rsvp() = reader(ReadMode.RSVP, ThemeMode.BLACK, "03_rsvp_paused")
    @Test fun paged() = reader(ReadMode.PAGED, ThemeMode.SEPIA, "04_paged")
    @Test fun scroll() = reader(ReadMode.SCROLL, ThemeMode.DARK, "05_scroll")

    @Test
    fun settingsDialog() {
        reader(ReadMode.RSVP, ThemeMode.BLACK, "06_tmp")
        rule.onNodeWithContentDescription("Reading settings").performClick()
        save("06_settings", dialog = true)
        File(outDir, "06_tmp.png").delete()
    }
}
