package com.zotspeed.widget

import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import com.zotspeed.MainActivity
import com.zotspeed.R
import com.zotspeed.data.LibraryRepository
import com.zotspeed.data.Position
import com.zotspeed.data.Prefs
import com.zotspeed.data.ThemeMode
import com.zotspeed.epub.Book
import com.zotspeed.epub.EpubParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reader widget for the Galaxy Z Flip cover screen (Flex Window). Shows the current page of
 * the book you last read, turns pages on tap, and shares the reading position with the app.
 * Widgets on the Flex Window work without Good Lock, unlike full apps.
 */
class CoverWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runAsync { ids.forEach { render(context, manager, it) } }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        runAsync { render(context, manager, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val delta = when (intent.action) {
            ACTION_NEXT -> 1
            ACTION_PREV -> -1
            else -> return
        }
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        runAsync {
            val manager = AppWidgetManager.getInstance(context)
            turnPage(context, manager, id, delta)
            allIds(context).forEach { render(context, manager, it) }
        }
    }

    /** Runs [block] off the main thread while keeping the broadcast alive. */
    private fun runAsync(block: suspend () -> Unit) {
        val pending: PendingResult? = goAsync() // null when not called from a broadcast
        scope.launch {
            try {
                block()
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val ACTION_NEXT = "com.zotspeed.widget.NEXT"
        const val ACTION_PREV = "com.zotspeed.widget.PREV"
        const val EXTRA_OPEN_LAST = "com.zotspeed.OPEN_LAST_BOOK"

        /** Display id of the Flex Window, per Samsung's guide (main screen = 0). */
        const val COVER_DISPLAY_ID = 1

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Volatile
        private var cached: Pair<String, Book>? = null

        /** Asks all ZotSpeed widgets to redraw (e.g. after reading in the app). */
        fun refresh(context: Context) {
            val ids = allIds(context)
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, CoverWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }

        private fun allIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, CoverWidgetProvider::class.java))

        private fun book(context: Context, bookId: String): Book? {
            cached?.let { (id, b) -> if (id == bookId) return b }
            val file = LibraryRepository(context).bookFile(bookId)
            if (!file.exists()) return null
            return runCatching { EpubParser.parse(file) }.getOrNull()?.takeIf { it.chapters.isNotEmpty() }
                ?.also { cached = bookId to it }
        }

        /** Size of the text area in px, from the widget's reported size (portrait: minWidth × maxHeight). */
        private fun pager(context: Context, manager: AppWidgetManager, id: Int, textSp: Int): WidgetPager {
            val opts = manager.getAppWidgetOptions(id)
            val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 352
            val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 339
            val dm = context.resources.displayMetrics
            val res = context.resources
            val padH = res.getDimension(R.dimen.widget_pad_h)
            val chrome = res.getDimension(R.dimen.widget_header) + res.getDimension(R.dimen.widget_footer) +
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f + 2f + 4f, dm) // padding + safety
            val w = (wDp * dm.density - 2 * padH).toInt()
            val h = (hDp * dm.density - chrome).toInt()
            val textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp.toFloat(), dm)
            return WidgetPager(textPx, w, h)
        }

        private fun textSp(prefs: Prefs) = (prefs.reader.textFontSp - 1).coerceIn(12, 28)

        private fun turnPage(context: Context, manager: AppWidgetManager, id: Int, delta: Int) {
            val prefs = Prefs(context)
            val bookId = prefs.lastBook.ifEmpty { return }
            val book = book(context, bookId) ?: return
            val pos = prefs.position(bookId)
            val ch = pos.chapter.coerceIn(0, book.chapters.lastIndex)
            val text = book.chapters[ch].text
            val offset = pos.offset.coerceIn(0, text.length)
            val pager = pager(context, manager, id, textSp(prefs))
            val next: Position = if (delta > 0) {
                val end = pager.pageEnd(text, offset)
                when {
                    end < text.length -> Position(ch, end)
                    ch + 1 < book.chapters.size -> Position(ch + 1, 0)
                    else -> pos
                }
            } else {
                when {
                    offset > 0 -> Position(ch, pager.pageStartBefore(text, offset))
                    ch > 0 -> {
                        val prev = book.chapters[ch - 1].text
                        Position(ch - 1, pager.pageStartBefore(prev, prev.length))
                    }
                    else -> pos
                }
            }
            prefs.setPosition(bookId, next)
            prefs.setProgress(bookId, book.progress(next.chapter, next.offset))
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = Prefs(context)
            val views = RemoteViews(context.packageName, R.layout.widget_cover_reader)
            applyTheme(context, views, prefs.reader.theme)
            views.setOnClickPendingIntent(R.id.widget_open, openAppIntent(context))

            val bookId = prefs.lastBook
            val book = bookId.takeIf { it.isNotEmpty() }?.let { book(context, it) }
            if (book == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_name))
                views.setTextViewText(R.id.widget_page, context.getString(R.string.widget_empty))
                views.setTextViewText(R.id.widget_percent, "")
                views.setTextViewText(R.id.widget_status, "")
                views.setOnClickPendingIntent(R.id.widget_tap_next, openAppIntent(context))
                views.setOnClickPendingIntent(R.id.widget_tap_prev, openAppIntent(context))
                manager.updateAppWidget(id, views)
                return
            }

            val pos = prefs.position(bookId)
            val ch = pos.chapter.coerceIn(0, book.chapters.lastIndex)
            val chapter = book.chapters[ch]
            val start = pos.offset.coerceIn(0, chapter.length)
            val sp = textSp(prefs)
            val end = pager(context, manager, id, sp).pageEnd(chapter.text, start)

            views.setTextViewTextSize(R.id.widget_page, TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
            views.setTextViewText(R.id.widget_page, chapter.text.substring(start, end).trimEnd('\n'))
            views.setTextViewText(R.id.widget_title, prefs.lastBookTitle.ifEmpty { book.title })
            views.setTextViewText(R.id.widget_percent, "${(book.progress(ch, start) * 100).toInt()}%")
            views.setTextViewText(R.id.widget_status, chapter.title.ifEmpty { "Chapter ${ch + 1}" })

            val next = turnIntent(context, id, ACTION_NEXT)
            val prev = turnIntent(context, id, ACTION_PREV)
            views.setOnClickPendingIntent(R.id.widget_tap_next, next)
            views.setOnClickPendingIntent(R.id.widget_next, next)
            views.setOnClickPendingIntent(R.id.widget_tap_prev, prev)
            views.setOnClickPendingIntent(R.id.widget_prev, prev)
            manager.updateAppWidget(id, views)
        }

        private fun turnIntent(context: Context, id: Int, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                id * 2 + if (action == ACTION_NEXT) 1 else 0,
                Intent(context, CoverWidgetProvider::class.java)
                    .setAction(action)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /**
         * Opens the current book in the app on the cover screen (Samsung's launchDisplayId).
         * Samsung only allows this for apps permitted on the cover screen (e.g. via Good Lock
         * MultiStar); otherwise One UI asks you to open the phone.
         */
        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_LAST, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val hasCover = context.getSystemService(DisplayManager::class.java)?.getDisplay(COVER_DISPLAY_ID) != null
            val options = if (hasCover) ActivityOptions.makeBasic().setLaunchDisplayId(COVER_DISPLAY_ID).toBundle() else null
            return PendingIntent.getActivity(
                context, 9999, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options,
            )
        }

        private fun applyTheme(context: Context, views: RemoteViews, theme: ThemeMode) {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val (bg, fg) = when (theme) {
                ThemeMode.LIGHT -> 0xFFFDFDFC.toInt() to 0xFF1A1C1E.toInt()
                ThemeMode.SEPIA -> 0xFFF4ECD8.toInt() to 0xFF3B2F22.toInt()
                ThemeMode.DARK -> 0xFF16181B.toInt() to 0xFFE3E3E3.toInt()
                ThemeMode.BLACK -> 0xFF000000.toInt() to 0xFFDADADA.toInt()
                ThemeMode.SYSTEM -> if (night) 0xFF16181B.toInt() to 0xFFE3E3E3.toInt() else 0xFFFDFDFC.toInt() to 0xFF1A1C1E.toInt()
            }
            val dim = (fg and 0x00FFFFFF) or 0x99000000.toInt()
            views.setInt(R.id.widget_root, "setBackgroundColor", bg)
            views.setTextColor(R.id.widget_page, fg)
            views.setTextColor(R.id.widget_title, dim)
            views.setTextColor(R.id.widget_percent, dim)
            views.setTextColor(R.id.widget_status, dim)
            listOf(R.id.widget_prev, R.id.widget_next, R.id.widget_open).forEach {
                views.setInt(it, "setColorFilter", fg)
            }
        }
    }
}
