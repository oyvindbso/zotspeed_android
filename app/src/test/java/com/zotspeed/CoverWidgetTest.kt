package com.zotspeed

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import com.zotspeed.data.Position
import com.zotspeed.data.Prefs
import com.zotspeed.epub.EpubParser
import com.zotspeed.widget.CoverWidgetProvider
import com.zotspeed.widget.WidgetPager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The cover-screen widget: pagination, rendering and page turns (needs SAMPLE_EPUB). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h400dp-xxhdpi")
class CoverWidgetTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sample = System.getenv("SAMPLE_EPUB")?.let(::File)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.resources.displayMetrics)

    @Test
    fun pagesForwardAndBackConsistently() {
        assumeTrue(sample != null && sample.exists())
        val text = EpubParser.parse(sample!!).chapters[3].text
        val pager = WidgetPager(dp(17f) * 1.0f, dp(324f).toInt(), dp(260f).toInt())
        val starts = ArrayList<Int>()
        var s = 0
        while (s < text.length) {
            starts += s
            val e = pager.pageEnd(text, s)
            assertTrue("page must advance", e > s)
            s = e
        }
        assertTrue("several pages", starts.size > 5)
        // Going back from each page start lands exactly on the previous page start.
        for (i in 1 until starts.size) assertEquals("page $i", starts[i - 1], pager.pageStartBefore(text, starts[i]))
    }

    private fun waitFor(cond: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(20)
        }
        error("timed out")
    }

    private fun layoutAndCapture(view: View, name: String?) {
        val w = dp(352f).toInt()
        val h = dp(339f).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val out = System.getenv("SCREENSHOT_DIR")?.let(::File) ?: return
        if (name == null) return
        out.mkdirs()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The rendered TextView must show the whole page: nothing cut off, and no room for another line. */
    private fun assertPageFits(view: View) {
        val tv = view.findViewById<TextView>(R.id.widget_page)
        val l = tv.layout
        val textHeight = tv.height - tv.paddingTop - tv.paddingBottom
        val bottom = l.getLineBottom(l.lineCount - 1)
        assertTrue("page overflows: $bottom > $textHeight", bottom <= textHeight)
        assertTrue("page leaves a gap of more than one line", textHeight - bottom < tv.lineHeight * 1.5f)
    }

    @Test
    fun rendersAndTurnsPages() {
        assumeTrue(sample != null && sample.exists())
        File(app.filesDir, "books").mkdirs()
        sample!!.copyTo(File(app.filesDir, "books/test.epub"), overwrite = true)
        val prefs = Prefs(app)
        prefs.lastBook = "test"
        prefs.lastBookTitle = "The War of the Worlds"
        prefs.setPosition("test", Position(3, 0))

        val manager = AppWidgetManager.getInstance(app)
        val shadow = shadowOf(manager)
        val id = shadow.createWidget(CoverWidgetProvider::class.java, R.layout.widget_cover_reader)
        waitFor { shadow.getViewFor(id).findViewById<TextView>(R.id.widget_page).text.startsWith("Chapter 1") }
        val view = shadow.getViewFor(id)
        layoutAndCapture(view, "07_widget_page1")
        assertPageFits(view)
        val firstPage = view.findViewById<TextView>(R.id.widget_page).text.toString()

        fun turn(action: String) {
            val before = prefs.position("test")
            CoverWidgetProvider().onReceive(
                app,
                Intent(app, CoverWidgetProvider::class.java).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            )
            waitFor { prefs.position("test") != before }
            waitFor { shadow.getViewFor(id).findViewById<TextView>(R.id.widget_page).text.toString() != firstPage || action == CoverWidgetProvider.ACTION_PREV }
        }

        turn(CoverWidgetProvider.ACTION_NEXT)
        val second = prefs.position("test")
        assertEquals(3, second.chapter)
        val v2 = shadow.getViewFor(id)
        layoutAndCapture(v2, "07_widget_page2")
        assertPageFits(v2)
        // The second page continues exactly where the first ended.
        val chapterText = EpubParser.parse(sample).chapters[3].text
        assertTrue(chapterText.substring(0, second.offset).trimEnd('\n') == firstPage)

        turn(CoverWidgetProvider.ACTION_PREV)
        assertEquals(Position(3, 0), prefs.position("test"))

        // Previous from the chapter start goes to the end of the previous chapter.
        turn(CoverWidgetProvider.ACTION_PREV)
        assertEquals(2, prefs.position("test").chapter)
    }
}
