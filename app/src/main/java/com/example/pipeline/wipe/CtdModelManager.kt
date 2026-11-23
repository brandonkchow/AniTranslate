package com.example.pipeline.wipe

import android.content.Context
import com.example.net.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Required mask model: comic-text-detector fp32 ONNX, 94.67 MB.
 *
 * First-run in-app download into filesDir/models/ctd/, SHA-256 pinned,
 * `.part` staging with atomic rename — the same door as
 * [InpaintModelManager]. The wipe cannot run without this file: a missing
 * model fails the page closed with [UNAVAILABLE_MESSAGE], never a silent
 * fallback. Never bundled, never committed.
 */
class CtdModelManager(filesDir: File, baseHttpClient: OkHttpClient = HttpClientProvider.client) {

    sealed class State {
        data object NotDownloaded : State()
        data class Downloading(val progress: Float, val bytesDownloaded: Long = 0L, val totalBytes: Long = 0L) : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    companion object {
        const val DOWNLOAD_URL = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/comictextdetector.pt.onnx"
        const val FILE_NAME = "comic-text-detector.onnx"
        const val SHA256 = "1a86ace74961413cbd650002e7bb4dcec4980ffa21b2f19b86933372071d718f"
        const val EXPECTED_BYTES = 94669756L

        /** Fail-closed message naming the cause and the remedy. */
        const val UNAVAILABLE_MESSAGE =
            "Comic text detector model unavailable: download it in Settings, then retry."

        @Volatile private var instance: CtdModelManager? = null
        fun getInstance(context: Context): CtdModelManager =
            instance ?: synchronized(this) {
                instance ?: CtdModelManager(context.applicationContext.filesDir).also { instance = it }
            }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) { val r = s.read(buf); if (r <= 0) break; md.update(buf, 0, r) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    val modelDir: File = File(filesDir, "models/ctd")
    val modelFile: File get() = File(modelDir, FILE_NAME)
    val isDownloaded: Boolean get() = modelFile.exists() && modelFile.length() == EXPECTED_BYTES

    private val client: OkHttpClient = baseHttpClient.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES).callTimeout(20, TimeUnit.MINUTES).build()

    private val _state = MutableStateFlow<State>(if (isDownloaded) State.Ready else State.NotDownloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun download(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!modelDir.exists() && !modelDir.mkdirs()) {
                _state.value = State.Failed("Cannot create ${modelDir.absolutePath}"); return@withContext false
            }
            if (isDownloaded && !force) { _state.value = State.Ready; return@withContext true }
            val part = File(modelDir, "$FILE_NAME.part")
            if (force) { modelFile.delete(); part.delete() }
            _state.value = State.Downloading(0f)
            Request.Builder().url(DOWNLOAD_URL).get().build().let { req ->
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) { _state.value = State.Failed("HTTP ${resp.code}"); return@withContext false }
                    val body = resp.body ?: run { _state.value = State.Failed("Empty body"); return@withContext false }
                    val total = body.contentLength().takeIf { it > 0 } ?: EXPECTED_BYTES
                    var done = 0L; var lastEmit = 0L
                    FileOutputStream(part).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val r = input.read(buf); if (r <= 0) break
                                out.write(buf, 0, r); done += r
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 100L) {
                                    lastEmit = now
                                    _state.value = State.Downloading((done.toFloat() / total).coerceIn(0f, 1f), done, total)
                                }
                            }
                        }
                    }
                }
            }
            val actual = sha256(part)
            if (!actual.equals(SHA256, ignoreCase = true)) {
                part.delete()
                _state.value = State.Failed("Checksum mismatch for $FILE_NAME")
                return@withContext false
            }
            if (modelFile.exists()) modelFile.delete()
            if (!part.renameTo(modelFile)) throw IOException("rename failed")
            CtdTextMask.invalidate()
            _state.value = State.Ready
            true
        } catch (t: Throwable) {
            _state.value = State.Failed("Comic text detector download failed: ${t.message}")
            false
        }
    }
}
