package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.SlotRole
import com.example.data.slots.SlotStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiKeyRepositoryTest {

    private lateinit var context: Context
    private lateinit var storage: SlotStorage

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = SlotStorage.getInstance(context)
    }

    @Test
    fun testInitialSlotsExist() {
        val slots = storage.loadSlots()
        assertTrue(slots.size >= 2)
        assertEquals(ApiProvider.OPENROUTER, slots[0].provider)
        assertEquals(ApiProvider.GEMINI, slots[1].provider)
    }

    @Test
    fun testVisionCapabilitiesPerProvider() {
        assertTrue(ApiProvider.GEMINI.isVisionCapable)
        assertTrue(ApiProvider.OPENROUTER.isVisionCapable)
        assertFalse(ApiProvider.GROQ.isVisionCapable)
    }

    @Test
    fun testSaveAndRetrieveSlotApiKey() {
        val geminiSlot = storage.loadSlots().first { it.provider == ApiProvider.GEMINI }
        val testKey = "AIzaSyTestKey12345"

        val updatedSlot = geminiSlot.copy(apiKey = testKey)
        storage.updateSlot(updatedSlot)

        val retrieved = storage.loadSlots().first { it.id == geminiSlot.id }
        assertEquals(testKey, retrieved.apiKey)
    }

    @Test
    fun testToggleSlotEnabled() {
        val slot = storage.loadSlots().first()
        val initialEnabled = slot.enabled

        storage.updateSlot(slot.copy(enabled = !initialEnabled))
        val updated = storage.loadSlots().first { it.id == slot.id }
        assertEquals(!initialEnabled, updated.enabled)
    }

    @Test
    fun testSlotCooldownCalculation() {
        val slot = storage.loadSlots().first()
        val cooldownDuration = 30_000L // 30s

        storage.setSlotCooldown(slot.id, cooldownDuration)
        val coolingSlot = storage.loadSlots().first { it.id == slot.id }

        assertTrue(coolingSlot.isCoolingDown())
        assertTrue(coolingSlot.remainingCooldownSeconds() > 0)
        assertEquals(1, coolingSlot.consecutiveRateLimits)

        storage.resetSlotCooldown(slot.id)
        val resetSlot = storage.loadSlots().first { it.id == slot.id }
        assertFalse(resetSlot.isCoolingDown())
        assertEquals(0, resetSlot.consecutiveRateLimits)
    }

    @Test
    fun testMarkSlotInvalidKey() {
        val slot = storage.loadSlots().first()
        storage.markSlotInvalidKey(slot.id, true)

        val updated = storage.loadSlots().first { it.id == slot.id }
        assertTrue(updated.isInvalidKey)

        storage.markSlotInvalidKey(slot.id, false)
        val restored = storage.loadSlots().first { it.id == slot.id }
        assertFalse(restored.isInvalidKey)
    }
}
