package com.example

import com.example.pipeline.wipe.CtdModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CtdModelManagerTest {

    private fun tempFilesDir(): File =
        Files.createTempDirectory("ctd-test").toFile().also { it.deleteOnExit() }

    /** Fake transport: serves [bytes] for any request, no network. */
    private fun fakeClient(bytes: ByteArray): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }.build()

    @Test
    fun constantsEqualVerifiedUpstreamValues() {
        assertEquals(
            "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/comictextdetector.pt.onnx",
            CtdModelManager.DOWNLOAD_URL
        )
        assertEquals("comic-text-detector.onnx", CtdModelManager.FILE_NAME)
        assertEquals(
            "1a86ace74961413cbd650002e7bb4dcec4980ffa21b2f19b86933372071d718f",
            CtdModelManager.SHA256
        )
        assertEquals(94669756L, CtdModelManager.EXPECTED_BYTES)
    }

    @Test
    fun hashMismatchFailsAndLeavesNothingBehind(): Unit = runBlocking {
        val manager = CtdModelManager(tempFilesDir(), fakeClient("not the model".toByteArray()))
        assertFalse(manager.download())
        assertTrue(manager.state.value is CtdModelManager.State.Failed)
        assertFalse(manager.modelFile.exists())
        assertFalse(File(manager.modelDir, CtdModelManager.FILE_NAME + ".part").exists())
    }

    @Test
    fun isDownloadedReflectsPresenceAndLength() {
        val manager = CtdModelManager(tempFilesDir(), fakeClient(ByteArray(0)))
        assertFalse(manager.isDownloaded)
        manager.modelDir.mkdirs()
        manager.modelFile.writeBytes(ByteArray(16))
        assertFalse(manager.isDownloaded)
        // Sparse: right length, no 95 MB of real bytes.
        RandomAccessFile(manager.modelFile, "rw").setLength(CtdModelManager.EXPECTED_BYTES)
        assertTrue(manager.isDownloaded)
    }

    @Test
    fun ensureReadyAttemptsOnceThenManualRetries(): Unit = runBlocking {
        val requests = AtomicInteger(0)
        val counting = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("not the model".toByteArray().toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }.build()
        val manager = CtdModelManager(tempFilesDir(), counting)
        assertFalse(manager.ensureReady())
        assertEquals(1, requests.get())
        assertFalse(manager.ensureReady())
        assertEquals(1, requests.get())
        assertFalse(manager.download())
        assertEquals(2, requests.get())
    }

    @Test
    fun concurrentDownloadsSingleFlight(): Unit = runBlocking {
        val requests = AtomicInteger(0)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            arrived.countDown()
            release.await(10, TimeUnit.SECONDS)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("not the model".toByteArray().toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }.build()
        val manager = CtdModelManager(tempFilesDir(), blocking)
        val d1 = async(Dispatchers.IO) { manager.download() }
        assertTrue(arrived.await(10, TimeUnit.SECONDS))
        val d2 = async(Dispatchers.IO) { manager.download() }
        delay(300)
        release.countDown()
        assertFalse(d1.await())
        assertFalse(d2.await())
        assertEquals(1, requests.get())
        assertFalse(manager.modelFile.exists())
        assertFalse(File(manager.modelDir, CtdModelManager.FILE_NAME + ".part").exists())
    }

    @Test
    fun midBodyFailureDeletesPart(): Unit = runBlocking {
        val failing = OkHttpClient.Builder().addInterceptor { chain ->
            val failingBody = object : ResponseBody() {
                override fun contentType(): MediaType? =
                    "application/octet-stream".toMediaType()
                override fun contentLength(): Long = 1024L
                override fun source(): BufferedSource = throw IOException("boom mid-body")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(failingBody)
                .build()
        }.build()
        val manager = CtdModelManager(tempFilesDir(), failing)
        assertFalse(manager.download())
        assertTrue(manager.state.value is CtdModelManager.State.Failed)
        assertFalse(manager.modelFile.exists())
        assertFalse(File(manager.modelDir, CtdModelManager.FILE_NAME + ".part").exists())
    }
}
