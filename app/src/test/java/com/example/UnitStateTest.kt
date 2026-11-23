package com.example

import com.example.data.models.Bubble
import com.example.data.models.TextClass
import com.example.data.models.UnitState
import com.example.pipeline.detect.TextClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Part 3 (prompt 20261118): per-unit state on the existing Bubble record.
 * State must round-trip through the same bubblesJson store, legacy stores
 * without the field must parse to the state their classification implies,
 * and classification at detect must not clobber a Commander's RESTORED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnitStateTest {

    private fun bubble(id: Int, textClass: TextClass = TextClass.DIALOGUE, unitState: UnitState = UnitState.PROCESSED) =
        Bubble(
            id = id, text = "テスト", box = listOf(0.1f, 0.1f, 0.3f, 0.3f),
            vertical = true, translated = "Test", type = "bubble",
            textClass = textClass, unitState = unitState
        )

    @Test
    fun unitStateSurvivesTheBubblesJsonStore() {
        val bubbles = listOf(
            bubble(1),
            bubble(2, unitState = UnitState.RESTORED),
            bubble(7, textClass = TextClass.SKIP, unitState = UnitState.SKIPPED)
        )
        val parsed = Bubble.parseListFromJson(Bubble.listToJsonString(bubbles))
        assertEquals(UnitState.PROCESSED, parsed[0].unitState)
        assertEquals(UnitState.RESTORED, parsed[1].unitState)
        assertTrue(parsed[1].isRestored)
        assertEquals(UnitState.SKIPPED, parsed[2].unitState)
        assertFalse(parsed[0].isRestored)
    }

    @Test
    fun legacyStoreWithoutUnitStateParsesFromClassification() {
        val legacy = """[{"id":3,"text":"...","box":[0.0,0.0,0.1,0.1],"vertical":true,"translated":"","type":"bubble","textClass":"SKIP"}]"""
        val parsed = Bubble.parseListFromJson(legacy)
        assertEquals(TextClass.SKIP, parsed[0].textClass)
        assertEquals(UnitState.SKIPPED, parsed[0].unitState)

        val legacyDialogue = """[{"id":4,"text":"こんにちは","box":[0.0,0.0,0.1,0.1],"vertical":true,"translated":"Hi","type":"bubble"}]"""
        assertEquals(UnitState.PROCESSED, Bubble.parseListFromJson(legacyDialogue)[0].unitState)
    }

    @Test
    fun classifyAllMirrorsClassificationButPreservesRestored() {
        val dialogue = Bubble(
            id = 1, text = "こんにちは世界", box = listOf(0.1f, 0.1f, 0.3f, 0.4f),
            vertical = true, translated = "Hello", type = "bubble"
        )
        val skipped = dialogue.copy(id = 2, text = "...")
        val restored = dialogue.copy(id = 3, unitState = UnitState.RESTORED)
        val out = TextClassifier.classifyAll(listOf(dialogue, skipped, restored))
        assertEquals(UnitState.PROCESSED, out[0].unitState)
        assertEquals(TextClass.SKIP, out[1].textClass)
        assertEquals(UnitState.SKIPPED, out[1].unitState)
        assertEquals(UnitState.RESTORED, out[2].unitState)
    }
}
