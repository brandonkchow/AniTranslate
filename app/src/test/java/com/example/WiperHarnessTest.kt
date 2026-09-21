package com.example

import com.example.pipeline.wipe.BubbleInkMask
import com.example.pipeline.wipe.BubbleInterior
import com.example.pipeline.wipe.EnclosedInterior
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reads a harness setting.
 *
 * Gradle project properties (`-Pharness.page=...`) are the reliable route into a forked test JVM,
 * because environment variables depend on how the Gradle daemon happened to be started — invisible
 * to the caller. The env var fallback keeps the harness runnable outside Gradle.
 */
private fun harnessSetting(name: String): String? =
    System.getProperty("harness.$name")
        ?: System.getenv("ANITRANSLATE_HARNESS_${name.uppercase()}")

/**
 * Desktop harness: runs the shipped wipe logic against a real page on a development machine.
 *
 * The wiper's decisions live in [BubbleInkMask] and [BubbleInterior], which are free of Android
 * types, so the *same* code the phone runs can be exercised here against a real JPEG. The only
 * thing this harness substitutes is Android's `Bitmap` for `ImageIO` — both hand the logic the
 * same `IntArray` of ARGB pixels.
 *
 * Skipped unless asked for, so it never runs in CI:
 *
 *     ./gradlew testDebugUnitTest --tests '*WiperHarnessTest*' \
 *       -Pharness.page=/path/page.jpg \
 *       -Pharness.boxes="0.608,0.046,0.930,0.229,speech;0.071,0.053,0.430,0.221,speech" \
 *       -Pharness.out=/path/wiped.png
 */
class WiperHarnessTest {

    private val pagePath = harnessSetting("page")
    private val boxesSpec = harnessSetting("boxes")
    private val outPath = harnessSetting("out")

    /** Luminance below which a residual pixel would be visible as ghost text. */
    private val visibleResidualLuminance = 0.90f

    private data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val type: String)

    private fun parseBoxes(spec: String): List<Box> = spec.split(';').filter { it.isNotBlank() }.map { entry ->
        val parts = entry.split(',')
        require(parts.size >= 4) { "box needs x1,y1,x2,y2[,type]: $entry" }
        Box(
            parts[0].trim().toFloat(),
            parts[1].trim().toFloat(),
            parts[2].trim().toFloat(),
            parts[3].trim().toFloat(),
            parts.getOrNull(4)?.trim() ?: "speech"
        )
    }

    @Test
    fun `wipes a real page without destroying the bubble walls`() {
        assumeTrue("set ANITRANSLATE_HARNESS_PAGE to run the desktop harness", pagePath != null)
        assumeTrue("set ANITRANSLATE_HARNESS_BOXES to run the desktop harness", boxesSpec != null)

        val image = ImageIO.read(File(pagePath!!))
        requireNotNull(image) { "could not read image: $pagePath" }

        val width = image.width
        val height = image.height
        val canvasPixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, canvasPixels, 0, width)
        val original = canvasPixels.copyOf()

        val densityScale = maxOf(1.0f, maxOf(width, height) / 1000f)
        println("[harness] page ${width}x$height, densityScale=$densityScale")

        for ((index, box) in parseBoxes(boxesSpec!!).withIndex()) {
            val left = (box.x1 * width).toInt().coerceIn(0, width - 1)
            val top = (box.y1 * height).toInt().coerceIn(0, height - 1)
            val right = (box.x2 * width).toInt().coerceIn(left + 1, width)
            val bottom = (box.y2 * height).toInt().coerceIn(top + 1, height)

            val boxW = right - left
            val boxH = bottom - top

            // Sample the fill colour from the detector's own box, exactly as FlatWiper does: the
            // widened region below reaches out onto the artwork.
            val boxPixels = IntArray(boxW * boxH)
            for (y in 0 until boxH) {
                System.arraycopy(canvasPixels, (top + y) * width + left, boxPixels, y * boxW, boxW)
            }
            val sampled = BubbleInkMask.sampleInteriorColor(boxPixels, boxW, boxH)

            // Widen the measurement region by the same margin FlatWiper uses, so the harness
            // exercises the shipping path instead of a narrower one the phone never runs.
            val margin = if (box.type.lowercase() in setOf("floating", "side_text")) {
                0
            } else {
                EnclosedInterior.measurementMargin(boxW, boxH)
            }
            val regionLeft = (left - margin).coerceAtLeast(0)
            val regionTop = (top - margin).coerceAtLeast(0)
            val regionRight = (right + margin).coerceAtMost(width)
            val regionBottom = (bottom + margin).coerceAtMost(height)
            val regionW = regionRight - regionLeft
            val regionH = regionBottom - regionTop

            val region = IntArray(regionW * regionH)
            for (y in 0 until regionH) {
                System.arraycopy(
                    canvasPixels,
                    (regionTop + y) * width + regionLeft,
                    region,
                    y * regionW,
                    regionW
                )
            }

            val shape = BubbleInterior.shapeFor(boxW.toFloat() / boxH.toFloat(), box.type)
            // Measured on the detector's own box, exactly as FlatWiper does — the widened region
            // would give a different inset, which is what silently enlarged the fallback shape.
            val boxInset = BubbleInterior.measureWallInset(
                FloatArray(boxW * boxH) { BubbleInterior.luminance(boxPixels[it]) },
                boxW,
                boxH
            )
            val plan = BubbleInkMask.plan(
                region,
                regionW,
                regionH,
                sampled,
                shape,
                densityScale,
                BubbleInkMask.BoxFrame(
                    left = left - regionLeft,
                    top = top - regionTop,
                    width = boxW,
                    height = boxH,
                    inset = boxInset
                )
            )

            val before = region.copyOf()
            if (plan.usedInkMask) {
                BubbleInkMask.apply(region, plan.wipe, sampled)
            }

            // ---- measurement, on the same pixels the phone would produce ----
            val bgLum = BubbleInterior.luminance(sampled)
            var wallBefore = 0
            var wallSurvived = 0
            var visibleResidual = 0
            var maxDarkDeviation = 0f

            for (i in 0 until regionW * regionH) {
                val lumBefore = BubbleInterior.luminance(before[i])
                if (lumBefore < bgLum - BubbleInkMask.INK_TOLERANCE && !plan.interior[i]) {
                    // A dark pixel outside the interior is wall, tail or artwork: all must survive.
                    wallBefore++
                    if (before[i] == region[i]) wallSurvived++
                }
                if (plan.interior[i]) {
                    val lum = BubbleInterior.luminance(region[i])
                    if (lum < visibleResidualLuminance) visibleResidual++
                    if (!plan.wipe[i]) {
                        // The whole point of leaning on the geometry: what survives the wipe has to
                        // be indistinguishable from the colour it was repainted with.
                        val dark = bgLum - lum
                        if (dark > maxDarkDeviation) maxDarkDeviation = dark
                    }
                }
            }

            // Did the image actually enclose an interior here? This decides whether the measured
            // geometry was used, or the fitted shape fell back into play.
            val enclosedBefore = EnclosedInterior.measure(
                FloatArray(regionW * regionH) { BubbleInterior.luminance(before[it]) },
                regionW,
                regionH
            )

            println(
                "[harness] box $index ${regionW}x$regionH margin=$margin type=${box.type} " +
                    "shape=$shape wallInset=${plan.wallInset} inkMask=${plan.usedInkMask} " +
                    "wallCloses=${enclosedBefore.found} wiped=${plan.wipe.count { it }} wallBefore=$wallBefore " +
                    "wallSurvived=$wallSurvived visibleResidual=$visibleResidual " +
                    "maxDarkDeviation=${"%.4f".format(maxDarkDeviation)}" +
                    " (${"%.1f".format(maxDarkDeviation * 255)}/255)"
            )

            if (plan.usedInkMask) {
                // The regression this change exists to prevent. A mask that is too large counts the
                // wall as interior in the counts above, so the harness stays green while the
                // artwork is destroyed — which is exactly how the last on-device run passed on the
                // laptop and failed on the page. Measure it from the image instead: if the stroke
                // was eaten, the paper inside now reaches the page and the interior stops closing.
                if (enclosedBefore.found) {
                    val enclosedAfter = EnclosedInterior.measure(
                        FloatArray(regionW * regionH) { BubbleInterior.luminance(region[it]) },
                        regionW,
                        regionH
                    )
                    assertTrue(
                        "box $index: the wipe broke the bubble wall — the interior no longer closes",
                        enclosedAfter.found
                    )
                }

                // The regression: every wall pixel used to be repainted as interior.
                assertTrue(
                    "box $index: found no wall to preserve — is this really a bubble?",
                    wallBefore > 0
                )
                assertEquals(
                    "box $index: the wipe must not touch a single pixel outside the interior",
                    wallBefore,
                    wallSurvived
                )
                assertEquals(
                    "box $index: ghost text left behind inside the bubble",
                    0,
                    visibleResidual
                )
                assertTrue(
                    "box $index: leftover interior pixel at ${"%.1f".format(maxDarkDeviation * 255)}/255 " +
                        "off the repaint colour — that is visible dirt",
                    maxDarkDeviation <= BubbleInkMask.INK_TOLERANCE + 0.001f
                )
            }

            // Write the region back so the harness output is a real image.
            for (y in 0 until regionH) {
                System.arraycopy(
                    region,
                    y * regionW,
                    canvasPixels,
                    (regionTop + y) * width + regionLeft,
                    regionW
                )
            }
        }

        val changed = canvasPixels.indices.count { canvasPixels[it] != original[it] }
        println("[harness] changed $changed of ${canvasPixels.size} pixels")

        if (outPath != null) {
            val output = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            output.setRGB(0, 0, width, height, canvasPixels, 0, width)
            val file = File(outPath)
            file.parentFile?.mkdirs()
            ImageIO.write(output, "png", file)
            println("[harness] wrote $outPath")
        }
    }
}
