package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.models.Bubble
import com.example.pipeline.wipe.FlatWiper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scope regression: the wipe touches balloon interiors and nothing else.
 *
 * This is the one property that must never depend on detection quality. A sound effect lettered over
 * line art has no uniform background to restore and no wall to bound the wipe, so wiping it can only
 * smudge the artwork — and that is exactly what the field reported: erasure over faces, panel
 * borders and free-standing lettering while dialogue boxes stayed half-full.
 *
 * The page here is drawn from scratch, so the geometry is exact and the assertions do not depend on
 * a detector, an OCR pass or a downloaded fixture. Two regions are placed:
 *
 * - a walled balloon with three glyph-sized strokes inside it (a dialogue box), and
 * - a block of solid dark artwork (a lettered effect over line art).
 *
 * The wipe must empty the first and must not touch a single pixel of the second. If that ever flips,
 * the wipe has grown past its scope again and this test fails before the build reaches a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WipeBalloonScopeTest {

    private val pageW = 480
    private val pageH = 320

    /** Balloon on the left: white interior, black wall, three strokes inside. */
    private val balloonRect = intArrayOf(45, 65, 255, 235)

    /** Artwork on the right: solid dark block standing in for a lettered effect. */
    private val artworkRect = intArrayOf(330, 80, 440, 240)

    /** Strokes only — no wall, so a count here measures dialogue ink alone. */
    private val glyphRect = intArrayOf(100, 110, 180, 160)

    private fun page(): Bitmap {
        // Built pixel by pixel rather than through Canvas: Robolectric's canvas is a no-op for
        // some draw calls, which silently produced an all-black page and hid what the wipe did.
        val px = IntArray(pageW * pageH) { Color.WHITE }

        // Walled balloon: white interior, 5 px solid wall.
        val cx = (balloonRect[0] + balloonRect[2]) / 2.0
        val cy = (balloonRect[1] + balloonRect[3]) / 2.0
        val rx = (balloonRect[2] - balloonRect[0]) / 2.0
        val ry = (balloonRect[3] - balloonRect[1]) / 2.0
        for (y in balloonRect[1]..balloonRect[3]) {
            for (x in balloonRect[0]..balloonRect[2]) {
                val outer = ((x - cx) / rx).let { it * it } + ((y - cy) / ry).let { it * it }
                if (outer > 1.0) continue
                val inner = ((x - cx) / (rx - 5.0)).let { it * it } + ((y - cy) / (ry - 5.0)).let { it * it }
                px[y * pageW + x] = if (inner <= 1.0) Color.WHITE else Color.BLACK
            }
        }

        // Three glyph-sized strokes: small, solid, filled — what lettering looks like to the wipe.
        for (i in 0 until 3) {
            val x0 = glyphRect[0] + i * 22
            for (y in glyphRect[1] until glyphRect[3]) {
                for (x in x0 until x0 + 14) {
                    px[y * pageW + x] = Color.BLACK
                }
            }
        }

        // Lettered effect over artwork: solid dark block, no uniform background.
        for (y in artworkRect[1] until artworkRect[3]) {
            for (x in artworkRect[0] until artworkRect[2]) {
                px[y * pageW + x] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(px, pageW, pageH, Bitmap.Config.ARGB_8888)
    }

    private fun boxNorm(r: IntArray) = listOf(
        r[0].toFloat() / pageW, r[1].toFloat() / pageH,
        r[2].toFloat() / pageW, r[3].toFloat() / pageH
    )

    private fun darkPixels(bmp: Bitmap, r: IntArray): Int {
        var n = 0
        for (y in r[1] until r[3]) {
            for (x in r[0] until r[2]) {
                val c = bmp.getPixel(x, y)
                val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
                if (lum < 0.45) n++
            }
        }
        return n
    }

    @Test
    fun wipesBalloonInteriorAndLeavesLetteringOverArtworkAlone() {
        val src = page()
        // Defensive copy: if the wiper writes through the source, measuring `src` later would
        // silently compare the wiped page against itself.
        val before = src.copy(Bitmap.Config.ARGB_8888, false)
        val dialogue = Bubble(id = 1, text = "テスト", box = boxNorm(balloonRect), type = "bubble")
        val effect = Bubble(id = 2, text = "ドカッ", box = boxNorm(artworkRect), type = "floating")

        val wiped = runBlocking { FlatWiper.wipeBubbles(src, listOf(dialogue, effect)) }
        val mutated = (0 until pageW step 7).sumOf { x ->
            (0 until pageH step 7).count { y -> src.getPixel(x, y) != before.getPixel(x, y) }
        }

        // 1. Dialogue inside the walled balloon is erased.
        val glyphsBefore = darkPixels(before, glyphRect)
        val glyphsAfter = darkPixels(wiped, glyphRect)
        assertTrue("page must contain drawn glyph strokes (found $glyphsBefore)", glyphsBefore > 500)
        assertTrue(
            "balloon glyphs must be wiped, but $glyphsAfter of $glyphsBefore ink pixels survived",
            glyphsAfter <= glyphsBefore / 4
        )

        // 2. Erasure is confined to that lettering: the page loses the glyph strokes and nothing
        //    else. A tolerance covers the small anti-halo dilation around each stroke.
        val wholePage = intArrayOf(0, 0, pageW, pageH)
        val erased = darkPixels(before, wholePage) - darkPixels(wiped, wholePage)
        assertEquals(
            "the wipe must erase the balloon's lettering and nothing else on the page",
            glyphsBefore.toDouble(), erased.toDouble(), glyphsBefore * 0.02
        )

        // 3. The wall survives: the wipe repaints text, never the artwork that bounds it.
        val wallBand = intArrayOf(balloonRect[0], balloonRect[1], balloonRect[2], balloonRect[1] + 15)
        val wallBefore = darkPixels(before, wallBand)
        val wallAfter = darkPixels(wiped, wallBand)
        assertTrue("balloon wall must be drawn in the page (found $wallBefore px)", wallBefore > 100)
        assertTrue(
            "balloon wall was erased: $wallBefore px before, only $wallAfter after",
            wallAfter >= wallBefore - 10
        )

        // 4. Non-balloon lettering over artwork is untouched, pixel for pixel.
        var changed = 0
        for (y in artworkRect[1] until artworkRect[3]) {
            for (x in artworkRect[0] until artworkRect[2]) {
                if (wiped.getPixel(x, y) != src.getPixel(x, y)) changed++
            }
        }
        assertEquals("lettering over artwork must not be touched at all", 0, changed)

        // 5. The artwork block keeps every pixel of its ink.
        val artworkArea = (artworkRect[2] - artworkRect[0]) * (artworkRect[3] - artworkRect[1])
        assertEquals(
            "artwork block must keep every pixel of its ink",
            artworkArea, darkPixels(wiped, artworkRect)
        )

        // 6. A clean read-through of the API: the wiper returns a new bitmap and leaves its input
        //    alone, so a caller can still use the original page.
        assertEquals("wiper must not write through its input bitmap", 0, mutated)
    }
}
