package com.example

import com.example.pipeline.wipe.EnclosureMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EnclosureMap]: classical segmentation of speech bubble enclosures.
 */
class EnclosureMapTest {

    @Test
    fun `detects enclosed speech bubble and rejects open border paper`() {
        val w = 80
        val h = 80
        // Entire page has paper background (1.0f) on border, artwork tone (0.4f) inside panel
        val lum = FloatArray(w * h) { 1.0f }
        for (y in 6 until h - 6) {
            for (x in 6 until w - 6) {
                lum[y * w + x] = 0.40f // panel artwork tone
            }
        }

        // Draw a dark rectangular panel border (inset 5px from page edge)
        for (x in 5 until w - 5) {
            lum[5 * w + x] = 0f
            lum[(h - 6) * w + x] = 0f
        }
        for (y in 5 until h - 5) {
            lum[y * w + 5] = 0f
            lum[y * w + (w - 6)] = 0f
        }

        // Inside the panel, draw an enclosed circular speech bubble on paper (1.0f)
        for (y in 10 until 70) {
            for (x in 10 until 70) {
                val d = (x - 40) * (x - 40) + (y - 40) * (y - 40)
                if (d < 18 * 18) {
                    lum[y * w + x] = 1.0f // bubble paper interior
                } else if (d <= 20 * 20) {
                    lum[y * w + x] = 0f // bubble wall stroke
                }
            }
        }

        // Add text ink inside the bubble (15 ink pixels)
        for (y in 38..42) {
            for (x in 39..41) {
                lum[y * w + x] = 0f
            }
        }

        val map = EnclosureMap.build(lum, w, h)

        // The open border paper touched the page border, so it is not an enclosure.
        // The speech bubble inside the panel is completely enclosed and contains text ink.
        assertEquals(1, map.enclosures.size)

        val enc = map.enclosures[0]
        assertTrue(enc.area > 500)

        // Check box inside bubble
        val insideBox = listOf(35f / w, 35f / h, 45f / w, 45f / h)
        assertTrue(enc.containsCenter(insideBox))
        assertNotNull(map.findEnclosureForBoxCenter(insideBox))

        // Check box outside bubble (in panel art or border)
        val outsideBox = listOf(10f / w, 10f / h, 20f / w, 20f / h)
        assertFalse(enc.containsCenter(outsideBox))
        assertNull(map.findEnclosureForBoxCenter(outsideBox))

        // Wall stroke pixels must not be part of the enclosure interior
        for (y in 10 until 70) {
            for (x in 10 until 70) {
                val d = (x - 40) * (x - 40) + (y - 40) * (y - 40)
                if (d in (18 * 18)..(20 * 20)) {
                    assertEquals(0, map.enclosureIds[y * w + x])
                }
            }
        }
    }

    @Test
    fun `two-lobe bubble joined by neck is detected as single enclosure covering both lobes`() {
        val w = 100
        val h = 140
        // Panel artwork tone (0.4f), page border 5px
        val lum = FloatArray(w * h) { 0.40f }

        // Two lobes: upper centered at (50, 45) r=22, lower centered at (50, 95) r=22
        // joined by open neck x in 40..60, y in 45..95
        for (y in 10 until 130) {
            for (x in 10 until 90) {
                val dUpper = (x - 50) * (x - 50) + (y - 45) * (y - 45)
                val dLower = (x - 50) * (x - 50) + (y - 95) * (y - 95)
                val inNeck = x in 40..60 && y in 45..95

                if (dUpper < 20 * 20 || dLower < 20 * 20 || inNeck) {
                    lum[y * w + x] = 1.0f // paper interior
                } else if (dUpper <= 23 * 23 || dLower <= 23 * 23 || (x in 37..63 && y in 45..95)) {
                    lum[y * w + x] = 0f // wall stroke
                }
            }
        }

        // Ink in upper lobe
        for (y in 40..45) {
            for (x in 48..52) {
                lum[y * w + x] = 0f
            }
        }
        // Ink in lower lobe
        for (y in 90..95) {
            for (x in 48..52) {
                lum[y * w + x] = 0f
            }
        }

        val map = EnclosureMap.build(lum, w, h)
        assertEquals(1, map.enclosures.size)

        val enc = map.enclosures[0]
        // Bounding box must cover both the upper lobe (y ~ 25) and lower lobe (y ~ 115)
        assertTrue("Enclosure must span down to lower lobe", enc.pixelBounds[3] >= 110)
        assertTrue("Enclosure must span up to upper lobe", enc.pixelBounds[1] <= 30)

        // Box covering only upper lobe should match this enclosure
        val upperBox = listOf(40f / w, 35f / h, 60f / w, 55f / h)
        val matchedEnc = map.findEnclosureForBoxCenter(upperBox)
        assertNotNull(matchedEnc)
        assertEquals(enc.id, matchedEnc!!.id)
    }
}
