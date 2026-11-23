package com.example

import com.example.data.models.Bubble
import com.example.data.models.TextClass
import com.example.pipeline.wipe.WipeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parts 1-2 of prompt 20261118: SKIP pixel gate and mask coverage flag.
 * Synthetic pages; the page-09 id11 dash is the calibration shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WipeGateTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val w = 200
    private val h = 1000 // page-scale: the 77px dash is 7.7% of height, as on page 09

    private fun page(): IntArray = IntArray(w * h) { white }

    private fun fill(px: IntArray, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        for (y in y1 until y2) for (x in x1 until x2) px[y * w + x] = color
    }

    @Test
    fun skipGatePassesWhenSkipPixelsIdenticalAndFailsPerUnitWhenChanged() {
        val skip = Bubble(5, "．．．", listOf(0.05f, 0.05f, 0.25f, 0.25f), textClass = TextClass.SKIP)
        val original = page()
        fill(original, 15, 30, 18, 90, black) // the SKIP unit's own ink
        val intact = original.copyOf()
        assertTrue(WipeGate.skipViolations(original, intact, w, h, listOf(skip)).isEmpty())
        assertEquals(
            listOf("SKIP-GATE unit 5: PASS bit-identical"),
            WipeGate.skipGateLines(original, intact, w, h, listOf(skip))
        )

        val wiped = original.copyOf()
        fill(wiped, 15, 30, 18, 90, white) // regression: SKIP unit wiped
        val violations = WipeGate.skipViolations(original, wiped, w, h, listOf(skip))
        assertEquals(1, violations.size)
        assertEquals(5, violations[0].unitId)
        assertTrue(violations[0].changedPixels > 0)
        assertTrue(WipeGate.skipGateLines(original, wiped, w, h, listOf(skip))[0].contains("FAIL"))
    }

    @Test
    fun skipGateIgnoresPixelsInsideAProcessedUnitsBox() {
        // Page-09 shape: the SKIP title's rectangle contains a dialogue
        // bubble. Changes inside the dialogue box are that unit's own
        // business; SKIP-only territory must stay bit-identical.
        val skip = Bubble(7, "title", listOf(0.05f, 0.05f, 0.90f, 0.90f), textClass = TextClass.SKIP)
        val dialogue = Bubble(8, "text", listOf(0.40f, 0.10f, 0.70f, 0.60f), textClass = TextClass.DIALOGUE)
        val original = page()
        fill(original, 100, 150, 102, 227, black) // dialogue ink inside the title rectangle
        val rendered = original.copyOf()
        fill(rendered, 100, 150, 102, 227, white) // wiped, as it should be
        assertTrue(WipeGate.skipViolations(original, rendered, w, h, listOf(skip, dialogue)).isEmpty())
    }

    @Test
    fun coverageFlagFiresOnThinVerticalDashInsideProcessedUnit() {
        // Page-09 id11 shape: a 2px-wide, 77px-tall isolated stroke the
        // mask missed, inside a processed dialogue unit.
        val unit = Bubble(11, "text", listOf(0.40f, 0.10f, 0.70f, 0.60f), textClass = TextClass.DIALOGUE)
        val original = page()
        fill(original, 100, 150, 102, 227, black)
        val wiped = original.copyOf() // mask covered nothing of the dash
        val flags = WipeGate.coverageFlags(original, wiped, w, h, listOf(unit))
        assertEquals(1, flags.size)
        assertEquals(11, flags[0].unitId)
        assertEquals(154, flags[0].components[0].pixels)
        assertTrue(WipeGate.coverageLines(flags)[0].startsWith("COVERAGE-FLAG unit 11"))
    }

    @Test
    fun coverageFlagIgnoresCoveredInkEdgeTouchingInkAndSkipUnits() {
        val unit = Bubble(11, "text", listOf(0.40f, 0.10f, 0.70f, 0.60f), textClass = TextClass.DIALOGUE)
        val skip = Bubble(5, "．．．", listOf(0.05f, 0.05f, 0.25f, 0.25f), textClass = TextClass.SKIP)
        val original = page()
        fill(original, 100, 100, 102, 177, black) // dash inside unit 11
        fill(original, 80, 40, 140, 44, black) // outline touching unit 11's top edge area (interior stroke)
        fill(original, 15, 30, 18, 90, black) // ink inside the SKIP unit
        val wiped = original.copyOf()
        fill(wiped, 100, 100, 102, 177, white) // dash covered this time
        // Edge-touching: draw a component that touches the unit box border.
        fill(wiped, 80, 40, 83, 120, black)
        fill(original, 80, 40, 83, 120, black)
        val flags = WipeGate.coverageFlags(original, wiped, w, h, listOf(unit, skip))
        assertTrue("covered/edge/SKIP ink must not flag: $flags", flags.isEmpty())
    }
}
