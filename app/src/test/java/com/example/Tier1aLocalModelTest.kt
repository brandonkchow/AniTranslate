package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.slots.ApiProvider
import com.example.net.OpenAiCompatibleClient
import com.example.pipeline.detect.DetectorPostProcess
import com.example.pipeline.detect.OnnxBubbleDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Tier 1a mechanical proof: the app's own OpenAI-compatible client against a
 * local model server on the host (llama.cpp). Skipped unless TIER1A_BASE_URL
 * is set, so normal test runs never wait on a local model.
 * Run: TIER1A_BASE_URL=http://127.0.0.1:18080/v1 ./gradlew :app:testDebugUnitTest --tests "*Tier1aLocalModelTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Tier1aLocalModelTest {

    @Test
    fun detectBubbles_onPage001_viaLocalModel() = runBlocking {
        val baseUrl = System.getenv("TIER1A_BASE_URL")
        assumeTrue("TIER1A_BASE_URL not set; Tier 1a proof skipped", !baseUrl.isNullOrBlank())

        val page = File("../tests/fixtures/manga/page-001-coffee-shop.jpg")
        assert(page.exists()) { "fixture missing: ${page.absolutePath}" }
        val base64 = Base64.getEncoder().encodeToString(page.readBytes())

        val client = OpenAiCompatibleClient()
        val result = client.detectBubbles(
            baseUrl = baseUrl!!,
            apiKey = "local-no-key",
            model = "qwen2.5-vl-3b",
            imageBase64 = base64,
            provider = ApiProvider.CUSTOM
        )
        println("TIER1A detect result: $result")
        val bubbles = result.getOrThrow()
        bubbles.forEach { println("TIER1A bubble #${it.id} [${it.type}] v=${it.vertical} box=${it.box} text=${it.text}") }
        assert(bubbles.isNotEmpty()) { "no bubbles detected on page-001" }
        assert(bubbles.all { it.box.size == 4 }) { "every bubble must carry a 4-coordinate box" }
    }

    /**
     * Step 2 crop-first scan: the ONNX detector owns geometry, the local model
     * only reads crops. Model-returned boxes from a full-page call are template
     * echoes (Tier 1a record), so no box in this test comes from the model.
     */
    @Test
    fun cropFirstScan_onPage001_viaOnnxCropsAndLocalModel() = runBlocking {
        val baseUrl = System.getenv("TIER1A_BASE_URL")
        assumeTrue("TIER1A_BASE_URL not set; Tier 1a proof skipped", !baseUrl.isNullOrBlank())

        val page = File("../tests/fixtures/manga/page-001-coffee-shop.jpg")
        assert(page.exists()) { "fixture missing: ${page.absolutePath}" }
        val pageBitmap = BitmapFactory.decodeFile(page.absolutePath)
            ?: throw AssertionError("could not decode page-001")
        println("TIER1A-CROP page=${pageBitmap.width}x${pageBitmap.height}")

        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = OnnxBubbleDetector(context)
        val detectStartMs = System.currentTimeMillis()
        val regions = detector.detect(pageBitmap)
        val detectMs = System.currentTimeMillis() - detectStartMs
        println("TIER1A-CROP detector ready=${detector.isReady} regions=${regions.size} in ${detectMs}ms")
        regions.forEachIndexed { i, r -> println("TIER1A-CROP region#$i [${r.label}] score=${r.score} box=${r.box}") }
        assert(regions.size >= 5) { "detector must return >=5 boxes on page-001, got ${regions.size}" }

        val bubbles = DetectorPostProcess.suppressDuplicates(DetectorPostProcess.bubbleRegions(regions))
        val crops = if (bubbles.isNotEmpty()) bubbles else regions
        println("TIER1A-CROP cropping ${crops.size} region(s) (bubbles=${bubbles.size})")

        val client = OpenAiCompatibleClient()
        var emptyCrops = 0
        val roundStartMs = System.currentTimeMillis()
        crops.forEachIndexed { idx, region ->
            val x = (region.box[0] * pageBitmap.width).toInt().coerceIn(0, pageBitmap.width - 1)
            val y = (region.box[1] * pageBitmap.height).toInt().coerceIn(0, pageBitmap.height - 1)
            val w = ((region.box[2] - region.box[0]) * pageBitmap.width).toInt().coerceAtLeast(1)
            val h = ((region.box[3] - region.box[1]) * pageBitmap.height).toInt().coerceAtLeast(1)
            val crop = Bitmap.createBitmap(pageBitmap, x, y, minOf(w, pageBitmap.width - x), minOf(h, pageBitmap.height - y))
            val bytes = ByteArrayOutputStream().also { out -> crop.compress(Bitmap.CompressFormat.JPEG, 90, out) }.toByteArray()
            val cropBase64 = Base64.getEncoder().encodeToString(bytes)
            val cropStartMs = System.currentTimeMillis()
            val result = client.scanAllText(
                baseUrl = baseUrl!!,
                apiKey = "local-no-key",
                model = "qwen2.5-vl-3b",
                imageBase64 = cropBase64,
                provider = ApiProvider.CUSTOM
            )
            val cropMs = System.currentTimeMillis() - cropStartMs
            val texts = result.getOrThrow()
            if (texts.isEmpty()) emptyCrops++
            println("TIER1A-CROP crop#$idx [${region.label}] ${crop.width}x${crop.height} ${cropMs}ms texts=${texts.map { it.text }}")
        }
        val roundMs = System.currentTimeMillis() - roundStartMs
        println("TIER1A-CROP total crop round ${roundMs}ms (${roundMs / 1000}s) for ${crops.size} crops, empty=$emptyCrops; full-page detect baseline was 683s")
        detector.close()
    }
}
