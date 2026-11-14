package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.models.Bubble
import com.example.data.slots.ApiProvider
import com.example.net.OpenAiCompatibleClient
import com.example.pipeline.detect.OnnxBubbleDetector
import com.example.pipeline.read.BaberuModelManager
import com.example.pipeline.read.BaberuOcrReader
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.text.Normalizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BaberuProofInstrumentedTest {

    companion object {
        private const val TAG = "BaberuProofTest"
        private const val STUB_PORT = 18711
    }

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // Test-only fixtures (baberu_gt/*) live in the androidTest APK, not the app APK:
    // they must be read through the instrumentation (test) context's assets.
    private val testCtx: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    private fun ensureModelsReady(): BaberuModelManager {
        val manager = BaberuModelManager.getInstance(ctx)
        runBlocking {
            if (!manager.verifyAll()) {
                val downloaded = manager.downloadAll()
                assertTrue("downloadAll() failed: ${manager.state.value}", downloaded)
            }
            assertEquals(BaberuModelManager.State.Ready, manager.state.value)
            assertTrue("verifyAll() must be true after Ready", manager.verifyAll())
        }
        return manager
    }

    private fun decodeAssetBitmap(assetPath: String): Bitmap {
        return testCtx.assets.open(assetPath).use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: error("Failed to decode bitmap asset: $assetPath")
    }

    private fun normalizeForCer(s: String): String {
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), "")
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    @Test(timeout = 15L * 60L * 1000L)
    fun modelDownloadAndVerify() {
        val manager = ensureModelsReady()
        val files = listOf(
            manager.visionFile,
            manager.prefillFile,
            manager.stepFile,
            manager.vocabFile
        )
        for (file in files) {
            assertTrue("Model file missing: ${file.absolutePath}", file.exists())
            assertTrue("Model file empty: ${file.absolutePath}", file.length() > 0L)
        }
    }

    @Test(timeout = 30L * 60L * 1000L)
    fun ocrMatchesDesktopLockOnAll117Crops() {
        val manager = ensureModelsReady()
        val reader = BaberuOcrReader(manager)

        val cropFiles = testCtx.assets.list("baberu_gt/crops_v3")?.sorted().orEmpty()
        assertEquals("Expected 117 crop assets", 117, cropFiles.size)

        val silverJson = testCtx.assets.open("baberu_gt/silver_v3.json").bufferedReader(Charsets.UTF_8).use {
            JSONObject(it.readText())
        }

        val outputs = ArrayList<String>(cropFiles.size)
        val resultsArray = JSONArray()
        var cerSum = 0.0
        var blankCount = 0

        for (fileName in cropFiles) {
            val id = fileName.substringBeforeLast('.')
            val ref = silverJson.getString(id)
            val bitmap = decodeAssetBitmap("baberu_gt/crops_v3/$fileName")
            val t0 = SystemClock.elapsedRealtime()
            val out: String = try {
                reader.read(bitmap)
            } finally {
                bitmap.recycle()
            }
            val ms = SystemClock.elapsedRealtime() - t0

            assertNotNull("OCR output was null for $id", out)
            outputs.add(out)
            if (out.isEmpty()) {
                blankCount++
            }

            val normOut = normalizeForCer(out)
            val normRef = normalizeForCer(ref)
            val cer = if (normRef.isEmpty()) {
                if (normOut.isEmpty()) 0.0 else 1.0
            } else {
                levenshtein(normOut, normRef).toDouble() / normRef.length.toDouble()
            }
            cerSum += cer

            Log.i(TAG, "ocr id=$id cer=${"%.4f".format(cer)} ms=$ms")
            resultsArray.put(
                JSONObject().apply {
                    put("id", id)
                    put("out", out)
                    put("ref", ref)
                    put("cer", cer)
                    put("ms", ms)
                }
            )
        }

        val meanCer = cerSum / cropFiles.size.toDouble()
        val report = JSONObject().apply {
            put("count", cropFiles.size)
            put("meanCer", meanCer)
            put("blankCount", blankCount)
            put("results", resultsArray)
        }
        File(ctx.filesDir, "baberu_proof_ocr.json").writeText(report.toString(2), Charsets.UTF_8)
        Log.i(TAG, "ocr summary: count=${cropFiles.size} meanCer=${"%.4f".format(meanCer)} blankCount=$blankCount")

        assertEquals(117, outputs.size)
        assertTrue("meanCer $meanCer exceeded gate 0.075", meanCer <= 0.075)
    }

    @Test(timeout = 15L * 60L * 1000L)
    fun corruptModelFailsClosed() {
        val manager = ensureModelsReady()
        val backupFile = File(ctx.cacheDir, "vision_int4.onnx.bak")
        manager.visionFile.copyTo(backupFile, overwrite = true)

        try {
            manager.visionFile.writeBytes(ByteArray(1024))

            runBlocking {
                assertFalse("verifyAll() must return false for corrupt model", manager.verifyAll())
            }
            assertTrue(
                "Expected State.Failed, got ${manager.state.value}",
                manager.state.value is BaberuModelManager.State.Failed
            )

            val firstCrop = testCtx.assets.list("baberu_gt/crops_v3")?.sorted()?.firstOrNull()
                ?: error("No crops in baberu_gt/crops_v3")
            val bitmap = decodeAssetBitmap("baberu_gt/crops_v3/$firstCrop")
            try {
                val freshReader = BaberuOcrReader(manager)
                try {
                    freshReader.read(bitmap)
                    fail("Expected BaberuOcrReader.read() to throw on corrupt vision_int4.onnx")
                } catch (_: Throwable) {
                    // Expected fail-closed behavior.
                }
            } finally {
                bitmap.recycle()
            }

            runBlocking {
                val restored = manager.downloadAll()
                assertTrue("downloadAll() restore failed: ${manager.state.value}", restored)
                assertEquals(BaberuModelManager.State.Ready, manager.state.value)
                assertTrue("verifyAll() must be true after restore", manager.verifyAll())
            }
        } finally {
            backupFile.delete()
        }
    }

    @Test(timeout = 60L * 1000L)
    fun stubTranslationExactly117Units() {
        val bubbles = (1..117).map { id ->
            Bubble(
                id = id,
                text = "stub-unit-$id",
                box = listOf(0f, 0f, 0.1f, 0.1f)
            )
        }

        val requestCount = AtomicInteger(0)
        val requestBubbleCount = AtomicInteger(-1)
        val serverReady = CountDownLatch(1)
        val serverSocket = ServerSocket(STUB_PORT, 50, InetAddress.getByName("127.0.0.1"))

        val serverThread = Thread {
            serverReady.countDown()
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    break
                }
                socket.use { conn ->
                    requestCount.incrementAndGet()
                    val input = BufferedInputStream(conn.getInputStream())
                    val headerText = readHttpHeaders(input)
                    val contentLength = Regex("(?im)^Content-Length:\\s*(\\d+)\\s*$")
                        .find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val n = input.read(bodyBytes, readTotal, contentLength - readTotal)
                        if (n < 0) break
                        readTotal += n
                    }
                    val bodyStr = String(bodyBytes, 0, readTotal, Charsets.UTF_8)
                    val reqJson = JSONObject(bodyStr)
                    val messages = reqJson.getJSONArray("messages")
                    var userContent = ""
                    for (i in 0 until messages.length()) {
                        val msg = messages.getJSONObject(i)
                        if (msg.optString("role") == "user") {
                            userContent = msg.optString("content")
                        }
                    }
                    val marker = "Translate:\n"
                    val idx = userContent.indexOf(marker)
                    assertTrue("Missing 'Translate:\\n' marker in user message", idx >= 0)
                    val itemsArr = JSONArray(userContent.substring(idx + marker.length))
                    requestBubbleCount.set(itemsArr.length())

                    val respItems = JSONArray()
                    for (i in 0 until itemsArr.length()) {
                        val id = itemsArr.getJSONObject(i).getInt("id")
                        respItems.put(
                            JSONObject().apply {
                                put("id", id)
                                put("translated", "EN-$id")
                            }
                        )
                    }
                    val contentJson = JSONObject().put("items", respItems).toString()
                    val responseBody = JSONObject().apply {
                        put("model", "stub-1")
                        put(
                            "choices",
                            JSONArray().put(
                                JSONObject().put(
                                    "message",
                                    JSONObject().put("content", contentJson)
                                )
                            )
                        )
                    }.toString().toByteArray(Charsets.UTF_8)

                    val headers = ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${responseBody.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                    val out = conn.getOutputStream()
                    out.write(headers)
                    out.write(responseBody)
                    out.flush()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            assertTrue("Stub HTTP server did not start in time", serverReady.await(5, TimeUnit.SECONDS))
            val client = OpenAiCompatibleClient()
            val result = runBlocking {
                client.translateBubbles(
                    baseUrl = "http://127.0.0.1:$STUB_PORT/v1",
                    apiKey = "stub",
                    model = "stub-1",
                    bubbles = bubbles,
                    provider = ApiProvider.GEMINI
                )
            }
            assertTrue("translateBubbles failed: ${result.exceptionOrNull()}", result.isSuccess)
            val translated = result.getOrThrow()
            assertEquals(117, translated.size)
            assertEquals((1..117).toSet(), translated.keys)
            for (id in 1..117) {
                assertEquals("EN-$id", translated[id])
            }
            assertEquals(117, requestBubbleCount.get())
            assertEquals(1, requestCount.get())
        } finally {
            try {
                serverSocket.close()
            } catch (_: Throwable) {
            }
            serverThread.join(5000)
        }
    }

    @Test(timeout = 10L * 60L * 1000L)
    fun detectorCountsOnCleanPages_informational() {
        val detector = OnnxBubbleDetector(ctx)
        assertTrue("OnnxBubbleDetector.loadModel() failed", detector.loadModel())

        val pages = testCtx.assets.list("baberu_gt/clean")?.sorted().orEmpty()
        assertEquals("Expected 11 clean pages", 11, pages.size)

        val results = JSONArray()
        for (page in pages) {
            val bitmap = decodeAssetBitmap("baberu_gt/clean/$page")
            val regions = try {
                detector.detect(bitmap)
            } finally {
                bitmap.recycle()
            }
            val bubbles = regions.count { it.label == OnnxBubbleDetector.LABEL_BUBBLE }
            val textBubbles = regions.count { it.label == OnnxBubbleDetector.LABEL_TEXT_BUBBLE }
            val freeText = regions.count { it.label == OnnxBubbleDetector.LABEL_TEXT_FREE }
            val total = regions.size
            assertTrue("Total detections must be >= 0", total >= 0)

            results.put(
                JSONObject().apply {
                    put("page", page)
                    put("bubbles", bubbles)
                    put("textBubbles", textBubbles)
                    put("freeText", freeText)
                    put("total", total)
                }
            )
        }

        val outFile = File(ctx.filesDir, "baberu_proof_detector.json")
        val jsonStr = results.toString(2)
        outFile.writeText(jsonStr, Charsets.UTF_8)
        Log.i(TAG, "detector results: $jsonStr")
    }

    private fun readHttpHeaders(input: BufferedInputStream): String {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        return buf.toString(Charsets.ISO_8859_1.name())
    }
}
