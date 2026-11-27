package com.zotspeed.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zotspeed.data.ReaderSettings
import com.zotspeed.data.ThemeMode
import com.zotspeed.epub.Book
import kotlin.math.roundToInt

/** Body text style shared by the paged and scrolling readers. */
@Composable
fun bodyStyle(s: ReaderSettings): TextStyle = TextStyle(
    fontSize = s.textFontSp.sp,
    lineHeight = (s.textFontSp * s.lineSpacing).sp,
    fontFamily = if (s.serif) FontFamily.Serif else FontFamily.SansSerif,
    color = MaterialTheme.colorScheme.onBackground,
    textIndent = TextIndent(firstLine = 1.4.em),
    // Greedy line breaking: cheap for whole-chapter layouts and stable across pages.
    lineBreak = LineBreak.Simple,
)

/** Full-screen dialog: works better than a sheet on the small cover screen. */
@Composable
internal fun FullDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
        ) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }
            content()
        }
    }
}

@Composable
fun ChapterListDialog(book: Book, current: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    FullDialog(book.title, onDismiss) {
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(book.chapters) { i, ch ->
                val selected = i == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(i) }
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ch.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${((ch.wordCount / 250f).roundToInt()).coerceAtLeast(1)} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ReaderSettingsDialog(settings: ReaderSettings, onChange: ((ReaderSettings) -> ReaderSettings) -> Unit, onDismiss: () -> Unit) {
    FullDialog("Reading settings", onDismiss) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SliderRow("Speed: ${settings.wpm} wpm", settings.wpm.toFloat(), 100f..1000f, steps = 0) { v ->
                onChange { it.copy(wpm = (v / 25).roundToInt() * 25) }
            }
            SliderRow("One-word size: ${settings.rsvpFontSp}", settings.rsvpFontSp.toFloat(), 20f..80f, steps = 0) { v ->
                onChange { it.copy(rsvpFontSp = v.roundToInt()) }
            }
            SliderRow("Text size: ${settings.textFontSp}", settings.textFontSp.toFloat(), 12f..32f, steps = 0) { v ->
                onChange { it.copy(textFontSp = v.roundToInt()) }
            }
            SliderRow("Line spacing: ${"%.2f".format(settings.lineSpacing)}", settings.lineSpacing, 1.1f..2.0f, steps = 0) { v ->
                onChange { it.copy(lineSpacing = (v * 20).roundToInt() / 20f) }
            }
            Text("Font", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = settings.serif, onClick = { onChange { it.copy(serif = true) } }, label = { Text("Serif") })
                FilterChip(selected = !settings.serif, onClick = { onChange { it.copy(serif = false) } }, label = { Text("Sans") })
            }
            Text("Theme", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeMode.entries.forEach { t ->
                    FilterChip(
                        selected = settings.theme == t,
                        onClick = { onChange { it.copy(theme = t) } },
                        label = { Text(t.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                    )
                }
            }
            SwitchRow("Show sentence when paused (one-word)", settings.rsvpContext) { v -> onChange { it.copy(rsvpContext = v) } }
            SwitchRow("Volume keys turn pages / play", settings.volumeKeys) { v -> onChange { it.copy(volumeKeys = v) } }
            SwitchRow("Keep screen on while reading", settings.keepScreenOn) { v -> onChange { it.copy(keepScreenOn = v) } }
            SwitchRow("Show over lock screen (read on the cover screen without unlocking)", settings.showOverLockScreen) { v ->
                onChange { it.copy(showOverLockScreen = v) }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onValue: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onValue, valueRange = range, steps = steps, modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
