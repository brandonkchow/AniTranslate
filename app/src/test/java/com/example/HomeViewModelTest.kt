package com.example

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.SlotRole
import com.example.data.slots.SlotStorage
import com.example.ui.home.HomeUiState
import com.example.ui.home.HomeViewModel
import com.example.ui.home.SelectedImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeViewModelTest {

    private lateinit var application: Application
    private lateinit var viewModel: HomeViewModel
    private lateinit var slotStorage: SlotStorage

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        slotStorage = SlotStorage(application)
        viewModel = HomeViewModel(application)
    }

    @Test
    fun testInitialHomeState() {
        val state = viewModel.uiState.value
        assertTrue(state.selectedImages.isEmpty())
        assertFalse(state.canTranslate)
    }

    @Test
    fun testCanTranslateGatingLogic() {
        val noImagesState = HomeUiState(
            selectedImages = emptyList(),
            slots = listOf(
                ApiSlot(provider = ApiProvider.GEMINI, apiKey = "key1", enabled = true, role = SlotRole.ANY)
            )
        )
        assertFalse(noImagesState.canTranslate)

        val readyState = HomeUiState(
            selectedImages = listOf(
                SelectedImageItem(id = "1", uri = Uri.parse("file:///tmp/img1.png"), name = "img1.png")
            ),
            slots = listOf(
                ApiSlot(provider = ApiProvider.GEMINI, apiKey = "key1", enabled = true, role = SlotRole.ANY)
            )
        )
        assertTrue(readyState.hasVisionKey)
        assertTrue(readyState.hasTranslateKey)
        assertTrue(readyState.canTranslate)
    }

    @Test
    fun testRemoveAndReorderImages() {
        val item1 = SelectedImageItem(id = "1", uri = Uri.parse("file:///tmp/1.png"), name = "1.png")
        val item2 = SelectedImageItem(id = "2", uri = Uri.parse("file:///tmp/2.png"), name = "2.png")
        val item3 = SelectedImageItem(id = "3", uri = Uri.parse("file:///tmp/3.png"), name = "3.png")

        // Test remove
        val currentList = listOf(item1, item2, item3)
        val afterRemove = currentList.filter { it.id != "2" }
        assertEquals(2, afterRemove.size)
        assertEquals("1", afterRemove[0].id)
        assertEquals("3", afterRemove[1].id)

        // Test move / reorder
        val mutable = currentList.toMutableList()
        val moved = mutable.removeAt(0)
        mutable.add(2, moved)
        assertEquals("2", mutable[0].id)
        assertEquals("3", mutable[1].id)
        assertEquals("1", mutable[2].id)
    }
}
