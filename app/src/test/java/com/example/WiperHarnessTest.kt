package com.example

import com.example.pipeline.wipe.BubbleInkMask
import com.example.pipeline.wipe.BubbleInterior
import com.example.pipeline.wipe.EnclosedInterior
import com.example.pipeline.wipe.EnclosureMap
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

        val fullLum = FloatArray(width * height) { BubbleInterior.luminance(canvasPixels[it]) }
        val enclosureMap = EnclosureMap.build(fullLum, width, height)
        val allInteriors = BooleanArray(width * height)

        // One box failing must not hide the other nine: collect them and report the lot after the
        // image is written, so a failing run still leaves an artifact to look at.
        val leftoverCensus = mutableListOf<String>()

        for ((index, box) in parseBoxes(boxesSpec!!).withIndex()) {
            val left = (box.x1 * width).toInt().coerceIn(0, width - 1)
            val top = (box.y1 * height).toInt().coerceIn(0, height - 1)
            val right = (box.x2 * width).toInt().coerceIn(left + 1, width)
            val bottom = (box.y2 * height).toInt().coerceIn(top + 1, height)

            val boxW = right - left
            val boxH = bottom - top

            val enc = enclosureMap.findEnclosureForBoxCenter(listOf(box.x1, box.y1, box.x2, box.y2))
            val effLeft = if (enc != null) minOf(left, enc.pixelBounds[0]) else left
            val effTop = if (enc != null) minOf(top, enc.pixelBounds[1]) else top
            val effRight = if (enc != null) maxOf(right, enc.pixelBounds[2]) else right
            val effBottom = if (enc != null) maxOf(bottom, enc.pixelBounds[3]) else bottom

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
                EnclosedInterior.measurementMargin(effRight - effLeft, effBottom - effTop)
            }
            val regionLeft = (effLeft - margin).coerceAtLeast(0)
            val regionTop = (effTop - margin).coerceAtLeast(0)
            val regionRight = (effRight + margin).coerceAtMost(width)
            val regionBottom = (effBottom + margin).coerceAtMost(height)
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

            val bubbleMask = if (enc != null) {
                BooleanArray(regionW * regionH) { i ->
                    val rx = i % regionW
                    val ry = i / regionW
                    val gx = regionLeft + rx
                    val gy = regionTop + ry
                    enclosureMap.enclosureIds[gy * width + gx] == enc.id
                }
            } else {
                null
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
                ),
                bubbleMask
            )

            for (ry in 0 until regionH) {
                for (rx in 0 until regionW) {
                    if (plan.interior[ry * regionW + rx]) {
                        allInteriors[(regionTop + ry) * width + (regionLeft + rx)] = true
                    }
                }
            }

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
                // When the wipe classified the ink, the interior is the detector's whole box rather
                // than a shape inset from a wall, so it legitimately contains structure — wall,
                // tail, spine — that is dark on purpose. Structure is neither residual text nor dirt.
                val structure = plan.structure.size == regionW * regionH && plan.structure[i]
                if (plan.interior[i]) {
                    val lum = BubbleInterior.luminance(region[i])
                    if (lum < visibleResidualLuminance && !structure) visibleResidual++
                    if (!plan.wipe[i] && !structure) {
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

            // What the image says had to be gone, independent of anything the classifier decided.
            // The masks above cannot see this defect: a component verdict that fused the lettering
            // to the stroke calls the blob structure, and structure is exempt from the residual
            // count by design. So measure it from the wall's own enclosure instead — if the stroke
            // really closes around paper, every dark pixel that paper enclosed was text, and after
            // the wipe it is either gone or it is Japanese left in a bubble (0.5f is well below the
            // the 0.55f paper threshold, so anti-aliased edges beside a stroke never count).
            var enclosedLeftover = 0
            if (enclosedBefore.found) {
                for (i in 0 until regionW * regionH) {
                    if (!enclosedBefore.mask[i]) continue
                    if (BubbleInterior.luminance(before[i]) >= 0.5f) continue
                    if (BubbleInterior.luminance(region[i]) >= 0.5f) continue
                    enclosedLeftover++
                }
            }

            println(
                "[harness] box $index ${regionW}x$regionH margin=$margin type=${box.type} " +
                    "shape=$shape wallInset=${plan.wallInset} inkMask=${plan.usedInkMask} " +
                    "wallCloses=${enclosedBefore.found} wiped=${plan.wipe.count { it }} " +
                    "glyph=${plan.glyphPixels} structure=${plan.structurePixels} wallBefore=$wallBefore " +
                    "wallSurvived=$wallSurvived visibleResidual=$visibleResidual " +
                    "enclosedLeftover=$enclosedLeftover " +
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

                if (plan.glyph.isNotEmpty()) {
                    // The classifying path states its contract from the masks it produced: every
                    // pixel it called text is gone, and no pixel it called structure was painted.
                    // Neither claim depends on knowing where a wall is.
                    assertEquals(
                        "box $index: text the classifier identified survived the wipe",
                        0,
                        plan.glyph.indices.count { plan.glyph[it] && !plan.wipe[it] }
                    )
                    assertEquals(
                        "box $index: the wipe painted over structure it had identified",
                        0,
                        plan.structure.indices.count { plan.structure[it] && plan.wipe[it] }
                    )
                } else {
                    // The fitted path still needs the image to have offered a wall to measure.
                    assertTrue(
                        "box $index: found no wall to preserve — is this really a bubble?",
                        wallBefore > 0
                    )
                }
                assertEquals(
                    "box $index: the wipe must not touch a single pixel outside the interior",
                    wallBefore,
                    wallSurvived
                )

                // Every count above is measured against the interior the plan chose, so a shape
                // that overshot reads as interior and the counts agree with the shape that made the
                // mistake — the wall census goes green while the stroke is repainted. Anchor one
                // claim in the image instead, where no shape can answer for it: a glyph is an
                // island in paper and a stroke leaves the region, so ink the flood reaches from the
                // region's own edge is not lettering and nothing may repaint it.
                //
                // Asserted only where no wall closed. When one did, the enclosure is the stronger
                // anchor and the repair deliberately promotes lettering leaning on the wall — which
                // is contiguous with the stroke, so there it is edge-connected by construction and
                // the claim would be a false positive rather than a defect.
                if (!enclosedBefore.found) {
                    val anchored = edgeConnectedDarkInk(before, regionW, regionH, bgLum)
                    assertEquals(
                        "box $index: the wipe repainted ink connected to the region's own edge — " +
                            "that is a stroke, not lettering, and it is the one claim no fitted " +
                            "shape can answer",
                        0,
                        (0 until regionW * regionH).count { anchored[it] && plan.wipe[it] }
                    )
                }
                assertEquals(
                    "box $index: ghost text left behind inside the bubble",
                    0,
                    visibleResidual
                )
                if (enclosedBefore.found && enclosedLeftover > 0) {
                    leftoverCensus += "box $index=$enclosedLeftover"
                }
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

        assertTrue(
            "Japanese left inside a wall that closes (dark px): " +
                leftoverCensus.joinToString(", ") +
                " — every box the detector calls text has to come out clean, not only the ones " +
                "whose lettering happened to stand clear of the stroke",
            leftoverCensus.isEmpty()
        )

        val modifiedOutsideInteriors = (0 until width * height).count { !allInteriors[it] && canvasPixels[it] != original[it] }
        assertEquals(
            "Outside interior regions, pixels must be completely unmodified (SSIM 1.0)",
            0,
            modifiedOutsideInteriors
        )

        // For Job 13, assert zero visible residual inside the formerly-surviving region (lower lobe 0.613..0.743)
        if (pagePath!!.contains("13")) {
            val box4Enclosure = enclosureMap.findEnclosureForBoxCenter(listOf(0.347f, 0.511f, 0.500f, 0.613f))
            org.junit.Assert.assertNotNull("box 4 must find an enclosure", box4Enclosure)
            val ry1 = (0.613f * height).toInt()
            val ry2 = (0.743f * height).toInt()
            var formerlySurvivingResidual = 0
            for (y in ry1 until ry2) {
                for (x in 0 until width) {
                    val p = y * width + x
                    if (enclosureMap.enclosureIds[p] == box4Enclosure!!.id) {
                        val lum = BubbleInterior.luminance(canvasPixels[p])
                        if (lum < visibleResidualLuminance) {
                            formerlySurvivingResidual++
                        }
                    }
                }
            }
            println("[harness] Job 13 formerly-surviving residual count: $formerlySurvivingResidual")
            assertEquals("formerly surviving lower lobe of box 4 must have zero visible residual", 0, formerlySurvivingResidual)
        }
    }

    /**
     * Dark ink 8-connected to the region's own edge, measured from the image alone.
     *
     * Deliberately not [BubbleInkMask]'s own helper. The harness exists to hold the decision
     * against the pixels rather than against the decision's own bookkeeping, so this is a second,
     * independent reading of the same fact — and it reads the pixels as they were *before* the
     * wipe, so a wipe cannot hide what it painted by having painted it.
     *
     * The dark-ink reading matches the census above, which is also written for light bubbles. An
     * inverted region would need the polarity flipped and this returns nothing either way, so it
     * fails open rather than inventing a violation.
     */
    private fun edgeConnectedDarkInk(
        pixels: IntArray,
        width: Int,
        height: Int,
        bgLum: Float
    ): BooleanArray {
        val ink = BooleanArray(width * height) {
            BubbleInterior.luminance(pixels[it]) < bgLum - BubbleInkMask.INK_TOLERANCE
        }
        val reached = BooleanArray(width * height)
        val queue = ArrayDeque<Int>()

        fun seed(i: Int) {
            if (ink[i] && !reached[i]) {
                reached[i] = true
                queue.addLast(i)
            }
        }

        for (x in 0 until width) {
            seed(x)
            seed((height - 1) * width + x)
        }
        for (y in 0 until height) {
            seed(y * width)
            seed(y * width + width - 1)
        }

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % width
            val y = i / width
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    seed(ny * width + nx)
                }
            }
        }
        return reached
    }
}
