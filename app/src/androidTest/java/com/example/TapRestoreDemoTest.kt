package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.db.AppDatabase
import com.example.data.db.JobEntity
import com.example.data.db.PageEntity
import com.example.data.db.PageStatus
import com.example.data.models.Bubble
import com.example.data.models.UnitState
import com.example.pipeline.detect.TextClassifier
import com.example.pipeline.image.ImageScaler
import com.example.pipeline.typeset.Typesetter
import com.example.pipeline.wipe.MaskWiper
import com.example.ui.editor.EditorScreen
import com.example.ui.editor.EditorViewModel
import com.example.ui.theme.BubbleforgeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Judgement step 2 demo (prompt 20261118, Part 3): tap-to-restore through
 * the real editor UI on the emulator. Seeds a completed page-09 job, taps
 * one dialogue unit, restores it via the action sheet (Japanese returns,
 * state persists in the page's bubblesJson), then taps again and
 * re-processes it (English returns). Screenshots land in tapdemo/.
 */
class TapRestoreDemoTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tapRestoreThenReprocessOneUnit() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val base = File(ctx.getExternalFilesDir(null), "maskproof")
        val outDir = File(ctx.getExternalFilesDir(null), "tapdemo").apply { mkdirs() }
        fun shot(name: String) {
            val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (bmp != null) File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // --- Seed a completed page from the accepted page-09 sidecar. ---
        val bubblesJson = File(base, "class20261122/bubbles_page-09.json").readText()
        val bubbles = TextClassifier.classifyAll(Bubble.parseListFromJson(bubblesJson))
        val origFile = File(base, "all11/pages/page-09.png")
        val origBmp = BitmapFactory.decodeFile(origFile.absolutePath)
        val wiped = runBlocking { MaskWiper.wipeBubbles(ctx, origBmp, bubbles) }!!
        val wipedFile = File(outDir, "orig_wiped.png"); runBlocking { ImageScaler.saveBitmapPng(wiped, wipedFile) }
        val finalBmp = runBlocking { Typesetter.typesetBubbles(ctx, wiped, bubbles).bitmap }
        val finalFile = File(outDir, "orig_final.png"); runBlocking { ImageScaler.saveBitmapPng(finalBmp, finalFile) }
        val origCopy = File(outDir, "page-09.png"); origFile.copyTo(origCopy, overwrite = true)

        val db = AppDatabase.getInstance(ctx)
        val pageId = runBlocking {
            val jobId = db.jobDao().insertJob(JobEntity(pageCount = 1, status = "COMPLETED"))
            db.jobDao().insertPages(
                listOf(
                    PageEntity(
                        jobId = jobId, pageIndex = 0,
                        originalImageUri = origCopy.absolutePath,
                        originalCachePath = origCopy.absolutePath,
                        wipedImagePath = wipedFile.absolutePath,
                        finalImagePath = finalFile.absolutePath,
                        status = PageStatus.DONE,
                        bubblesJson = Bubble.listToJsonString(bubbles),
                        width = origBmp.width, height = origBmp.height
                    )
                )
            ).first()
        }

        val target = bubbles.first { it.id == 11 }
        assertEquals(UnitState.PROCESSED, target.unitState)

        // --- Open the editor on the seeded page. ---
        val vm = EditorViewModel(composeRule.activity.application, pageId)
        composeRule.setContent {
            BubbleforgeTheme {
                EditorScreen(viewModel = vm, onNavigateBack = {})
            }
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            vm.uiState.value.bubbles.isNotEmpty()
        }

        fun dismissAnr() {
            // "System UI isn't responding" dialog: tap its Wait row (centered
            // card on the 1080x2400 emulator). No-op when no dialog is up.
            // Several rows probed: exact geometry shifts with font scale.
            for (y in listOf(1330, 1400, 1470)) {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("input tap 540 $y").close()
                Thread.sleep(700)
            }
        }

        fun tapTarget() {
            val cx = (target.x1 + target.x2) / 2f
            val cy = (target.y1 + target.y2) / 2f
            val bounds = composeRule.onNodeWithContentDescription("Manga Page Canvas")
                .fetchSemanticsNode().boundsInRoot
            val cw = bounds.width
            val ch = bounds.height
            val fitScale = kotlin.math.min(cw / origBmp.width.toFloat(), ch / origBmp.height.toFloat())
            val fittedW = origBmp.width.toFloat() * fitScale
            val fittedH = origBmp.height.toFloat() * fitScale
            val offsetX = (cw - fittedW) / 2f
            val offsetY = (ch - fittedH) / 2f
            val canvasX = offsetX + fittedW * cx
            val canvasY = offsetY + fittedH * cy
            // The shared emulator throws transient system ANR dialogs under
            // load; a tap can land on one. Retry, dismissing the dialog
            // between attempts, until the selection actually changes.
            dismissAnr()
            repeat(6) { attempt ->
                if (attempt < 2) {
                    composeRule.onNodeWithContentDescription("Manga Page Canvas")
                        .performTouchInput {
                            click(Offset(canvasX, canvasY))
                        }
                } else {
                    // Real input injection at the canvas's absolute bounds:
                    // Compose-test injection has proven unreliable here.
                    val absX = (bounds.left + canvasX).toInt()
                    val absY = (bounds.top + canvasY).toInt()
                    InstrumentationRegistry.getInstrumentation().uiAutomation
                        .executeShellCommand("input tap $absX $absY").close()
                }
                try {
                    composeRule.waitUntil(timeoutMillis = 8_000) {
                        vm.uiState.value.selectedBubbleId == target.id
                    }
                    return
                } catch (e: Throwable) {
                    shot("diag_attempt.png")
                    dismissAnr()
                }
            }
            throw AssertionError("tap never selected unit ${target.id}")
        }

        fun persistedState(): UnitState? = runBlocking {
            db.jobDao().getPageByIdDirect(pageId)
                ?.getBubbles()?.first { it.id == target.id }?.unitState
        }

        fun boxDifferenceFromOriginal(): Double {
            val page = runBlocking { db.jobDao().getPageByIdDirect(pageId) }!!
            val fin = BitmapFactory.decodeFile(page.finalImagePath)
            val w = origBmp.width; val h = origBmp.height
            val x1 = (target.x1 * w).toInt() + 3; val y1 = (target.y1 * h).toInt() + 3
            val x2 = (target.x2 * w).toInt() - 3; val y2 = (target.y2 * h).toInt() - 3
            var diff = 0; var total = 0
            for (y in y1 until y2) for (x in x1 until x2) {
                if (origBmp.getPixel(x, y) != fin.getPixel(x, y)) diff++
                total++
            }
            return diff.toDouble() / total
        }

        // --- Tap: one action sheet. ---
        tapTarget()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithTag("restore_original").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        shot("shot1_sheet.png")

        // --- Restore: state persists, Japanese returns bit-for-bit. ---
        composeRule.onNodeWithTag("restore_original").performTouchInput { click() }
        composeRule.waitUntil(timeoutMillis = 240_000) {
            persistedState() == UnitState.RESTORED && !vm.uiState.value.isRendering
        }
        shot("shot2_restored.png")
        val restoredDiff = boxDifferenceFromOriginal()
        // Unit 10's detector box overlaps unit 11's right edge, so its wipe
        // legitimately touches a strip of this box; restored must be near-
        // identical, not bit-identical.
        assertTrue("restored unit must match original, diff=$restoredDiff", restoredDiff < 0.05)

        // --- Tap again: re-process. English returns, state back to PROCESSED. ---
        tapTarget()
        composeRule.onNodeWithTag("reprocess_unit").assertExists()
        composeRule.onNodeWithTag("reprocess_unit").performTouchInput { click() }
        composeRule.waitUntil(timeoutMillis = 240_000) {
            persistedState() == UnitState.PROCESSED && !vm.uiState.value.isRendering
        }
        shot("shot3_reprocessed.png")
        val reprocessedDiff = boxDifferenceFromOriginal()
        assertTrue("re-processed unit must differ from original, diff=$reprocessedDiff", reprocessedDiff > 0.10)

        File(outDir, "demo_report.txt").writeText(
            "target unit ${target.id}: restoredDiff=$restoredDiff reprocessedDiff=$reprocessedDiff\n" +
                "persisted states: RESTORED then PROCESSED, in pages.bubblesJson\n"
        )
    }
}
