package com.example.pipeline.read

import android.content.Context
import com.example.net.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Manages the pinned on-device Baberu OCR ONNX model bundle in `filesDir/models/baberu/`.
 *
 * No model bytes are bundled in the APK or git. Downloads use `.part` staging with HTTP Range
 * resume support, SHA-256 verification, and atomic rename.
 */
class BaberuModelManager(
    filesDir: File,
    baseHttpClient: OkHttpClient = HttpClientProvider.client
) {
    sealed class State {
        data object NotDownloaded : State()
        data class Downloading(
            val progress: Float,
            val currentFile: String = "",
            val bytesDownloaded: Long = 0L,
            val totalBytes: Long = 0L
        ) : State()

        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    data class ModelSpec(
        val remotePath: String,
        val localName: String,
        val sha256: String,
        val expectedBytes: Long? = null
    )

    companion object {
        const val REPO_ID = "genshiai-daichi/baberu-ocr"
        const val REVISION = "b9868433dbda3ed9ba1357d008599ff4ada873d5"
        const val DOWNLOAD_BASE =
            "https://huggingface.co/$REPO_ID/resolve/$REVISION/"

        const val VISION_FILE = "vision_int4.onnx"
        const val PREFILL_FILE = "decoder_prefill_int8.onnx"
        const val STEP_FILE = "decoder_step_int8.onnx"
        const val VOCAB_FILE = "vocab.json"

        val SPECS: List<ModelSpec> = listOf(
            ModelSpec(
                remotePath = "onnx/vision_int4.onnx",
                localName = VISION_FILE,
                sha256 = "1967ecef502e4c1e1e7c512ea0a2e06396b39ded87a8a9cc77bd7db2e981a361",
                expectedBytes = 52_293_486L
            ),
            ModelSpec(
                remotePath = "onnx/decoder_prefill_int8.onnx",
                localName = PREFILL_FILE,
                sha256 = "187a59cb61d3870f1f97c9eb027eb34ff19d023fba801cda113a13870be0b724"
            ),
            ModelSpec(
                remotePath = "onnx/decoder_step_int8.onnx",
                localName = STEP_FILE,
                sha256 = "46640b0b3db77f0f0eef660bbe5dc9126a5e0fb49eb2786b2edba7b0371d9769"
            ),
            ModelSpec(
                remotePath = "tokenizer/vocab.json",
                localName = VOCAB_FILE,
                sha256 = "681de4eb83154a397af49e2ddae4705041b12a22e16fe00860b829e889e5f47c"
            )
        )

        @Volatile
        private var instance: BaberuModelManager? = null

        fun getInstance(context: Context): BaberuModelManager {
            return instance ?: synchronized(this) {
                instance ?: BaberuModelManager(context.applicationContext.filesDir).also {
                    instance = it
                }
            }
        }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val read = stream.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    val modelDir: File = File(filesDir, "models/baberu")
    val visionFile: File get() = File(modelDir, VISION_FILE)
    val prefillFile: File get() = File(modelDir, PREFILL_FILE)
    val stepFile: File get() = File(modelDir, STEP_FILE)
    val vocabFile: File get() = File(modelDir, VOCAB_FILE)

    private val downloadClient: OkHttpClient = baseHttpClient.newBuilder()
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    private val downloadMutex = Mutex()

    private val _state = MutableStateFlow<State>(initialPresenceState())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun initialPresenceState(): State {
        val allPresent = SPECS.all { spec ->
            val f = File(modelDir, spec.localName)
            f.exists() && f.length() > 0L
        }
        return if (allPresent) State.Ready else State.NotDownloaded
    }

    /**
     * Re-hashes all 4 model files on disk. Updates [state] to [State.Ready] if all match,
     * or [State.Failed] with a user-readable message if any file is missing or corrupted.
     */
    suspend fun verifyAll(): Boolean = withContext(Dispatchers.IO) {
        if (!modelDir.exists()) {
            _state.value = State.NotDownloaded
            return@withContext false
        }
        for (spec in SPECS) {
            val file = File(modelDir, spec.localName)
            if (!file.exists() || file.length() == 0L) {
                _state.value = State.Failed("Missing Baberu OCR file: ${spec.localName}. Download in Settings.")
                return@withContext false
            }
            val actual = try {
                sha256(file)
            } catch (t: Throwable) {
                _state.value = State.Failed("Failed to read ${spec.localName}: ${t.message}")
                return@withContext false
            }
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                _state.value = State.Failed(
                    "Checksum mismatch for ${spec.localName}. Please re-download the Baberu OCR model."
                )
                return@withContext false
            }
        }
        _state.value = State.Ready
        true
    }

    /**
     * Downloads all 4 pinned Baberu OCR files to `.part` files, verifies SHA-256, and atomically
     * renames them into place. Supports HTTP Range resume when `.part` exists and the server
     * returns HTTP 206.
     */
    suspend fun downloadAll(forceRedownload: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            try {
                if (!modelDir.exists() && !modelDir.mkdirs()) {
                    _state.value = State.Failed("Cannot create model directory: ${modelDir.absolutePath}")
                    return@withLock false
                }

                if (forceRedownload) {
                    for (spec in SPECS) {
                        File(modelDir, spec.localName).delete()
                        File(modelDir, "${spec.localName}.part").delete()
                    }
                }

                _state.value = State.Downloading(progress = 0f, currentFile = SPECS.first().localName)

                val totalFiles = SPECS.size
                for ((index, spec) in SPECS.withIndex()) {
                    val target = File(modelDir, spec.localName)
                    if (!forceRedownload && target.exists() && target.length() > 0L) {
                        if (sha256(target).equals(spec.sha256, ignoreCase = true)) {
                            val progress = (index + 1).toFloat() / totalFiles.toFloat()
                            _state.value = State.Downloading(progress = progress, currentFile = spec.localName)
                            continue
                        } else {
                            target.delete()
                        }
                    }

                    downloadSingleFile(spec, index, totalFiles)
                }

                verifyAll()
            } catch (t: Throwable) {
                _state.value = State.Failed("Baberu OCR download failed: ${t.message ?: "Unknown error"}")
                false
            }
        }
    }

    private fun downloadSingleFile(spec: ModelSpec, fileIndex: Int, totalFiles: Int) {
        val target = File(modelDir, spec.localName)
        val part = File(modelDir, "${spec.localName}.part")
        val url = DOWNLOAD_BASE + spec.remotePath

        var existingBytes = if (part.exists()) part.length() else 0L
        val reqBuilder = Request.Builder().url(url).get()
        if (existingBytes > 0L) {
            reqBuilder.addHeader("Range", "bytes=$existingBytes-")
        }

        downloadClient.newCall(reqBuilder.build()).execute().use { response ->
            when {
                response.code == 416 -> {
                    // Range not satisfiable (e.g. .part is already complete or stale) — retry from 0.
                    part.delete()
                    existingBytes = 0L
                    downloadFromScratch(url, part, spec, fileIndex, totalFiles)
                }
                response.code == 206 -> {
                    val body = response.body ?: throw IOException("Empty response body for ${spec.localName}")
                    val contentLength = body.contentLength()
                    val totalForFile = if (contentLength > 0L) existingBytes + contentLength else (spec.expectedBytes ?: -1L)
                    streamToFile(body.byteStream(), part, append = true, initialBytes = existingBytes, totalForFile = totalForFile, spec = spec, fileIndex = fileIndex, totalFiles = totalFiles)
                }
                response.isSuccessful -> {
                    // Server returned 200 OK (ignored Range or fresh request) -> overwrite .part
                    val body = response.body ?: throw IOException("Empty response body for ${spec.localName}")
                    val totalForFile = body.contentLength().takeIf { it > 0L } ?: (spec.expectedBytes ?: -1L)
                    streamToFile(body.byteStream(), part, append = false, initialBytes = 0L, totalForFile = totalForFile, spec = spec, fileIndex = fileIndex, totalFiles = totalFiles)
                }
                else -> {
                    throw IOException("HTTP ${response.code} downloading ${spec.localName}")
                }
            }
        }

        val actualSha = sha256(part)
        if (!actualSha.equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("SHA-256 mismatch for ${spec.localName} (expected ${spec.sha256.take(12)}, got ${actualSha.take(12)})")
        }

        if (target.exists() && !target.delete()) {
            throw IOException("Failed to replace existing ${spec.localName}")
        }
        if (!part.renameTo(target)) {
            throw IOException("Failed to rename ${part.name} to ${target.name}")
        }
    }

    private fun downloadFromScratch(
        url: String,
        part: File,
        spec: ModelSpec,
        fileIndex: Int,
        totalFiles: Int
    ) {
        val req = Request.Builder().url(url).get().build()
        downloadClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} downloading ${spec.localName}")
            }
            val body = response.body ?: throw IOException("Empty response body for ${spec.localName}")
            val totalForFile = body.contentLength().takeIf { it > 0L } ?: (spec.expectedBytes ?: -1L)
            streamToFile(body.byteStream(), part, append = false, initialBytes = 0L, totalForFile = totalForFile, spec = spec, fileIndex = fileIndex, totalFiles = totalFiles)
        }
    }

    private fun streamToFile(
        input: java.io.InputStream,
        part: File,
        append: Boolean,
        initialBytes: Long,
        totalForFile: Long,
        spec: ModelSpec,
        fileIndex: Int,
        totalFiles: Int
    ) {
        val buf = ByteArray(64 * 1024)
        var downloaded = initialBytes
        var lastEmitMs = 0L

        FileOutputStream(part, append).use { out ->
            input.use { stream ->
                while (true) {
                    val read = stream.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    downloaded += read

                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= 100L) {
                        lastEmitMs = now
                        val fileFraction = if (totalForFile > 0L) {
                            (downloaded.toFloat() / totalForFile.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val overall = ((fileIndex.toFloat() + fileFraction) / totalFiles.toFloat()).coerceIn(0f, 1f)
                        _state.value = State.Downloading(
                            progress = overall,
                            currentFile = spec.localName,
                            bytesDownloaded = downloaded,
                            totalBytes = totalForFile.coerceAtLeast(0L)
                        )
                    }
                }
            }
        }
    }
}
