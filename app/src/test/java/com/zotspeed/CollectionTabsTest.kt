package com.zotspeed

import com.zotspeed.data.Prefs
import com.zotspeed.ui.AppViewModel
import com.zotspeed.ui.LibraryUi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CollectionTabsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun setUpLibrary() {
        Prefs(app).apply { apiKey = "dummy"; userId = 1; username = "u" }
        fun book(key: String, col: String) = JSONObject().put("key", key).put("title", key).put("authors", "")
            .put("year", "").put("filename", "$key.epub").put("md5", JSONObject.NULL)
            .put("collections", JSONArray().put(col))
        File(app.filesDir, "library_user_1.json").writeText(
            JSONObject().put("schema", 2).put("version", 5).put("groups", JSONArray())
                .put("entries", JSONArray().put(book("A", "C1")).put(book("B", "C2")).put(book("C", "C3")))
                .put(
                    "collections", JSONArray()
                        .put(JSONObject().put("key", "C1").put("name", "Novels").put("parent", JSONObject.NULL))
                        .put(JSONObject().put("key", "C2").put("name", "Papers").put("parent", JSONObject.NULL))
                        .put(JSONObject().put("key", "C3").put("name", "Sub").put("parent", "C2"))
                ).toString()
        )
    }

    /** Lets the ViewModel finish loading the cached library (disk reads run on a background thread). */
    private fun awaitLoaded(vm: AppViewModel) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (vm.library.value.collections.isNotEmpty()) return
            Thread.sleep(20)
        }
        error("library never loaded")
    }

    @Test
    fun promptsThenManagesTabs() {
        setUpLibrary()
        val vm = AppViewModel(app)
        awaitLoaded(vm)
        var ui = vm.library.value
        assertTrue("should ask for a collection", ui.needsCollectionChoice)
        assertEquals(3, ui.visibleEntries.size) // no tab yet: everything

        vm.openTab("C2")
        ui = vm.library.value
        assertFalse(ui.needsCollectionChoice)
        assertEquals(listOf("B", "C"), ui.visibleEntries.map { it.key }) // includes subcollection

        vm.openTab("C1")
        vm.openTab("C2") // already open: just switches
        ui = vm.library.value
        assertEquals(listOf("C2", "C1"), ui.tabs)
        assertEquals(0, ui.activeTab)

        vm.openTab(LibraryUi.ALL_BOOKS)
        assertEquals(2, vm.library.value.activeTab)
        assertEquals(3, vm.library.value.visibleEntries.size)

        vm.closeTab(0) // close a tab before the active one
        ui = vm.library.value
        assertEquals(listOf("C1", LibraryUi.ALL_BOOKS), ui.tabs)
        assertEquals(1, ui.activeTab)

        // Tabs survive restarts.
        val vm2 = AppViewModel(app)
        awaitLoaded(vm2)
        assertEquals(listOf("C1", LibraryUi.ALL_BOOKS), vm2.library.value.tabs)
        assertEquals(1, vm2.library.value.activeTab)

        vm2.closeTab(1)
        vm2.closeTab(0)
        assertTrue("closing the last tab asks again", vm2.library.value.needsCollectionChoice)
    }
}
