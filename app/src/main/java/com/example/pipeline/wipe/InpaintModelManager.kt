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
 * Optional Best-tier fill model: LaMa (big-lama) fp32 ONNX, 208 MB.
 *
 * Never bundled in the app (prompt 20261118): downloaded on demand into
 * filesDir/models/inpaint/, SHA-256 verified, `.part` staging with atomic
 * rename — the same discipline as BaberuModelManager. Best tier without
 * this file resolves to Standard; the wipe never blocks on it.
 */
class InpaintModelManager(filesDir: File, baseHttpClient: OkHttpClient = HttpClientProvider.client) {

    sealed class State {
        data object NotDownloaded : State()
        data class Downloading(val progress: Float, val bytesDownloaded: Long = 0L, val totalBytes: Long = 0L) : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    companion object {
        const val DOWNLOAD_URL = "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"
        const val FILE_NAME = "lama_fp32.onnx"
        const val SHA256 = "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6"
        const val EXPECTED_BYTES = 208_044_816L

        @Volatile private var instance: InpaintModelManager? = null
        fun getInstance(context: Context): InpaintModelManager =
            instance ?: synchronized(this) {
                instance ?: InpaintModelManager(context.applicationContext.filesDir).also { instance = it }
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

    val modelDir: File = File(filesDir, "models/inpaint")
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
            _state.value = State.Ready
            true
        } catch (t: Throwable) {
            _state.value = State.Failed("LaMa download failed: ${t.message}")
            false
        }
    }
}
