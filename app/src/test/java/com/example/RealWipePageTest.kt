package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.data.models.Bubble
import com.example.pipeline.wipe.FlatWiper
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the *shipped* [FlatWiper] over a real page and a real detector box list.
 *
 * [WiperHarnessTest] re-implements the wipe loop so it can assert on the masks inside it, which
 * means anything that drifts between the copy and the wiper silently stops being tested — and it
 * did: the copy never modelled the opaque shape fallback, and while it stayed green the device was
 * painting over artwork. This test shares no logic with the wiper. It hands real boxes to the real
 * entry point and writes what came out, so the file it produces is evidence about the build.
 *
 * Skipped unless a page is supplied:
 * {{{
 * ./gradlew testDebugUnitTest --tests '*RealWipePageTest*' \
 *   -Preal.page=<working.jpg> -Preal.boxes='x1,y1,x2,y2,type;...' -Preal.out=<wiped.png>
 * }}}
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealWipePageTest {

    @Test
    fun wipesRealPageWithRealBoxes() {
        val pagePath = System.getProperty("real.page")
        assumeTrue(
            "no -Preal.page supplied, skipping the real-page wipe run",
            !pagePath.isNullOrBlank() && File(pagePath).exists()
        )
        val spec = System.getProperty("real.boxes").orEmpty()
        val outPath = System.getProperty("real.out")
        assumeTrue("no -Preal.out supplied", !outPath.isNullOrBlank())

        val original = BitmapFactory.decodeFile(pagePath)
        assertTrue("could not decode $pagePath", original != null)

        val bubbles = spec.split(';')
            .filter { it.isNotBlank() }
            .mapIndexed { index, entry ->
                val f = entry.split(',')
                require(f.size >= 4) { "malformed box entry: $entry" }
                Bubble(
                    id = index + 1,
                    text = "\u30c6\u30b9\u30c8",
                    box = listOf(f[0].trim().toFloat(), f[1].trim().toFloat(), f[2].trim().toFloat(), f[3].trim().toFloat()),
                    type = f.getOrNull(4)?.trim()?.ifBlank { null } ?: "bubble"
                )
            }
        println("[real] page=${File(pagePath).name} boxes=${bubbles.size} " +
            "nonBalloon=${bubbles.count { it.isNonBalloon }}")

        val wiped = runBlocking { FlatWiper.wipeBubbles(original, bubbles) }

        // FlatWiper copies its input, but never assume it: write a copy we own either way.
        val out = if (wiped === original) original.copy(Bitmap.Config.ARGB_8888, false) else wiped
        FileOutputStream(outPath).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("nothing written to $outPath", File(outPath).length() > 0L)
        println("[real] wrote $outPath (${File(outPath).length()} bytes)")
    }
}
