package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.data.models.Bubble
import com.example.pipeline.typeset.Typesetter
import com.example.pipeline.typeset.WordWrap
import com.example.pipeline.wipe.FlatWiper
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Stage re-render proof for the whole-word wrap fix (Commander 2026-11-15).
 *
 * Drives the shipped [FlatWiper] + [Typesetter] over real clean pages with
 * ground-truth units and bench translations (input JSON built from
 * detector_outputs.json + marked_boxes_v3.json + C0_gemini_trackA.json).
 * Writes rendered JPGs and a per-unit layout dump, and fails on any
 * mid-word split without a hyphen in the lines actually drawn.
 *
 * Skipped unless supplied:
 *   -Prender.input=<render_input.json> -Prender.cleanDir=<dir>
 *   -Prender.outDir=<dir> -Prender.pages=page-04,page-05,page-06
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TypesetRenderProofTest {

    @Test
    fun rendersPagesWithWholeWordWrap() {
        val inputPath = System.getProperty("render.input")
        val cleanDir = System.getProperty("render.cleanDir")
        val outDirPath = System.getProperty("render.outDir")
        assumeTrue("render props not supplied", !inputPath.isNullOrBlank() && !cleanDir.isNullOrBlank() && !outDirPath.isNullOrBlank())
        val pages = System.getProperty("render.pages")!!.split(",").map { it.trim() }
        val all = JSONObject(File(inputPath!!).readText())
        val outDir = File(outDirPath!!).apply { mkdirs() }
        val context = ApplicationProvider.getApplicationContext<Context>()

        val dumpLines = mutableListOf<String>()
        val floorUnits = mutableListOf<Pair<String, Int>>()
        var totalUnits = 0
        for (page in pages) {
            val clean = File(cleanDir!!).listFiles()!!.first { it.name.startsWith(page) }
            val original = BitmapFactory.decodeFile(clean.absolutePath)
            assertTrue("could not decode $clean", original != null)
            val arr = all.getJSONArray(page)
            val bubbles = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val boxArr = o.getJSONArray("box")
                Bubble(
                    id = o.getInt("id"),
                    text = "テスト",
                    box = (0 until 4).map { boxArr.getDouble(it).toFloat() },
                    translated = o.getString("translated"),
                    type = o.getString("type")
                )
            }
            val decisions = mutableListOf<Map<String, Any>>()
            Typesetter.onLayoutDecided = { id, type, decidedText, lines, hyphenated, fontSizePx, phase, lineCount, occupancy ->
                decisions += mapOf(
                    "id" to id, "type" to type, "text" to decidedText,
                    "lines" to lines, "hyphenated" to hyphenated,
                    "fontSizePx" to fontSizePx, "phase" to phase
                )
                if (phase == FLOOR_PHASE) floorUnits += page to id
                dumpLines += JSONObject().apply {
                    put("page", page)
                    put("id", id)
                    put("type", type)
                    put("text", decidedText)
                    put("lines", JSONArray(lines))
                    put("hyphenated", hyphenated)
                    put("fontSizePx", fontSizePx.toDouble())
                    put("phase", phase)
                    put("lineCount", lineCount)
                    put("occupancy", occupancy)
                }.toString()
            }
            val rendered = try {
                val wiped = runBlocking { FlatWiper.wipeBubbles(original, bubbles) }
                // Wiped-but-not-typeset output: the residual-ink measurement reads
                // this, so leftover source glyphs are counted without the English
                // glyphs drawn over them polluting the count.
                FileOutputStream(File(outDir, "${page}_wiped.jpg")).use {
                    wiped.compress(Bitmap.CompressFormat.JPEG, 92, it)
                }
                runBlocking { Typesetter.typesetBubbles(context, wiped, bubbles) }.bitmap
            } finally {
                Typesetter.onLayoutDecided = null
            }
            val outFile = File(outDir, "${page}_translated.jpg")
            FileOutputStream(outFile).use { rendered.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            assertTrue("empty render $outFile", outFile.length() > 0)
            assertEquals("every unit must produce a layout decision on $page", bubbles.size, decisions.size)
            for (d in decisions) {
                @Suppress("UNCHECKED_CAST")
                val lines = d["lines"] as List<String>
                assertFalse(
                    "mid-word split without hyphen on $page unit ${d["id"]}: $lines",
                    WordWrap.hasUnhyphenatedMidWordSplit(d["text"] as String, lines)
                )
            }
            totalUnits += decisions.size
            println("[render] $page units=${decisions.size} hyphenated=${decisions.count { it["hyphenated"] == true }} -> $outFile")
        }
        val dumpFile = File(outDir, "layout_dump.txt")
        dumpFile.writeText(dumpLines.joinToString("\n") + "\n")
        // A unit on the 4px floor ("floor-resort") is a microscopic-text
        // risk: it must be named in the dump on disk, not pass silently.
        val namedFloor = assertFloorUnitsNamedInDump(dumpFile.readText(), floorUnits)
        namedFloor.forEach { println("[render] FLOOR-RESORT (4px floor) unit named in layout_dump.txt: $it") }
        println("[render] total units=$totalUnits, zero unhyphenated mid-word splits, floor-resort units named=${namedFloor.size}")
    }

    @Test
    fun floorUnitsAreNamedInDump() {
        val dump = """
            {"page":"page-04","id":1,"phase":"shrink","fontSizePx":18.0}
            {"page":"page-06","id":9,"phase":"floor-resort","fontSizePx":4.0}
        """.trimIndent()
        val named = assertFloorUnitsNamedInDump(dump, listOf("page-06" to 9))
        assertEquals(listOf("page-06 unit 9 (fontSizePx=4.0, phase=floor-resort)"), named)
    }

    @Test
    fun floorUnitMissingFromDumpFails() {
        val dump = """{"page":"page-04","id":1,"phase":"shrink","fontSizePx":18.0}"""
        try {
            assertFloorUnitsNamedInDump(dump, listOf("page-06" to 9))
            throw AssertionError("expected the unnamed floor unit to fail the proof")
        } catch (expected: AssertionError) {
            assertTrue(expected.message!!.contains("page-06 unit 9"))
        }
    }

    companion object {
        /** The 4px floor phase in [Typesetter.calculateBestLayout]. */
        const val FLOOR_PHASE = "floor-resort"

        /**
         * Every (page, id) in [floorUnits] must appear in [dumpText] as a
         * floor-resort line carrying its font size. Returns the named
         * units, one string per floor unit, for the proof's output.
         */
        fun assertFloorUnitsNamedInDump(dumpText: String, floorUnits: List<Pair<String, Int>>): List<String> {
            val named = mutableListOf<String>()
            val entries = dumpText.lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
            for ((page, id) in floorUnits) {
                val entry = entries.firstOrNull {
                    it.optString("page") == page && it.optInt("id") == id && it.optString("phase") == FLOOR_PHASE
                }
                assertTrue(
                    "unit landing on the 4px floor phase is not named in the layout dump: $page unit $id",
                    entry != null && entry.has("fontSizePx")
                )
                named += "$page unit $id (fontSizePx=${entry!!.getDouble("fontSizePx")}, phase=$FLOOR_PHASE)"
            }
            return named
        }
    }
}
