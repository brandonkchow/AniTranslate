package com.example.net

import android.util.Log
import com.example.data.models.Bubble
import com.example.data.slots.ApiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAiCompatibleClient {

    @Volatile
    var lastServedModel: String? = null

    /** Local slots (CUSTOM / WORKSTATION) run on our hardware and answer in minutes, not seconds. */
    private fun clientFor(provider: ApiProvider) =
        if (provider == ApiProvider.CUSTOM || provider == ApiProvider.WORKSTATION) {
            HttpClientProvider.localClient
        } else {
            HttpClientProvider.client
        }

    suspend fun testKey(baseUrl: String, apiKey: String, provider: ApiProvider): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trim().removeSuffix("/")
            val url = "$cleanBase/models"

            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .get()

            if (provider == ApiProvider.OPENROUTER) {
                requestBuilder.addHeader("HTTP-Referer", "https://github.com/bubbleforge/bubbleforge")
                requestBuilder.addHeader("X-Title", "Bubbleforge")
            }

            val response = clientFor(provider).newCall(requestBuilder.build()).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                Result.success("Key valid (HTTP $code)")
            } else {
                val errorMsg = extractErrorMessage(body, code)
                Result.failure(ApiException(code, errorMsg, extractRetryAfter(response.header("Retry-After"), body)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun translateBubbles(
        baseUrl: String,
        apiKey: String,
        model: String,
        bubbles: List<Bubble>,
        provider: ApiProvider,
        storyContext: String = "",
        fallbackModels: List<String> = emptyList()
    ): Result<Map<Int, String>> = withContext(Dispatchers.IO) {
        if (bubbles.isEmpty()) return@withContext Result.success(emptyMap())
        try {
            val cleanBase = baseUrl.trim().removeSuffix("/")
            val url = "$cleanBase/chat/completions"

            val inputList = JSONArray().apply {
                bubbles.forEach { b ->
                    put(JSONObject().apply {
                        put("id", b.id)
                        put("text", b.text)
                    })
                }
            }

            val contextSection = if (storyContext.isNotBlank()) {
                "\nStory / Previous Page Context for continuity:\n$storyContext\n"
            } else ""

            val systemPrompt = """
                You are a master manga localizer and comic translator.
                Translate the following Japanese manga dialogue into natural, punchy, conversational English.
                $contextSection
                Rules:
                - Holistic Translation: Translate all bubbles in the sequence as a single scene.
                - Pronoun & Subject Resolution: Resolve omitted Japanese subjects (e.g. 私, 俺, 貴方, 彼, 彼女) and implicit pronouns based on context and conversational flow.
                - Character Voice & Tone: Preserve unique character personalities, comedic timing, emotional cadence, and shouting intensity.
                - Keep dialogue punchy and lettering-friendly.
                - Return ONLY valid JSON in format:
                {"items":[{"id":1,"translated":"..."}]}
            """.trimIndent()

            val requestJson = JSONObject().apply {
                put("model", model.trim())
                if (provider == ApiProvider.OPENROUTER && fallbackModels.isNotEmpty()) {
                    val modelsArr = JSONArray().apply {
                        put(model.trim())
                        fallbackModels.filter { it.isNotBlank() && it.trim() != model.trim() }
                            .take(3)
                            .forEach { put(it.trim()) }
                    }
                    put("models", modelsArr)
                }
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Translate:\n$inputList")
                    })
                }
                put("messages", messages)
                put("temperature", 0.3)
                put("response_format", JSONObject().apply {
                    put("type", "json_object")
                })
            }

            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .post(requestBody)

            if (provider == ApiProvider.OPENROUTER) {
                requestBuilder.addHeader("HTTP-Referer", "https://github.com/bubbleforge/bubbleforge")
                requestBuilder.addHeader("X-Title", "Bubbleforge")
            }

            val response = clientFor(provider).newCall(requestBuilder.build()).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorMsg = extractErrorMessage(body, code)
                return@withContext Result.failure(ApiException(code, errorMsg, extractRetryAfter(response.header("Retry-After"), body)))
            }

            val servedModel = try {
                JSONObject(body).optString("model", model)
            } catch (_: Exception) {
                model
            }
            lastServedModel = servedModel
            Log.i("OpenAiCompatibleClient", "translateBubbles served by model: $servedModel (provider: $provider)")

            val content = extractChatResponseContent(body)
            val translations = parseTranslationsJson(content)
            Result.success(translations)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractChatResponseContent(body: String): String {
        return try {
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices")
            val firstChoice = choices?.optJSONObject(0)
            val message = firstChoice?.optJSONObject("message")
            message?.optString("content", "").orEmpty()
        } catch (e: Exception) {
            body
        }
    }

    private fun parseBubblesJson(rawText: String): List<Bubble> {
        val clean = cleanJson(rawText)
        val list = mutableListOf<Bubble>()
        try {
            if (clean.startsWith("[")) {
                val arr = JSONArray(clean)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null) {
                        list.add(Bubble.fromJson(obj, fallbackId = i + 1))
                    }
                }
            } else {
                val root = JSONObject(clean)
                val arr = root.optJSONArray("bubbles")
                    ?: root.optJSONArray("speech_bubbles")
                    ?: root.optJSONArray("items")
                    ?: root.optJSONArray("text_boxes")
                    ?: root.optJSONArray("boxes")
                    ?: root.optJSONArray("dialogue")
                    ?: root.optJSONArray("results")
                    ?: root.optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i)
                        if (obj != null) {
                            list.add(Bubble.fromJson(obj, fallbackId = i + 1))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("OpenAiCompatibleClient", "Failed to parse bubbles JSON: $rawText", e)
        }
        return list
    }

    private fun parseTranslationsJson(rawText: String): Map<Int, String> {
        val clean = cleanJson(rawText)
        val map = mutableMapOf<Int, String>()
        try {
            if (clean.startsWith("[")) {
                val arr = JSONArray(clean)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optInt("id", item.optInt("index", i + 1))
                    val trans = item.optString("translated", item.optString("english", item.optString("text", "")))
                    if (id != -1 && trans.isNotBlank()) {
                        map[id] = trans
                    }
                }
            } else {
                val root = JSONObject(clean)
                val arr = root.optJSONArray("items")
                    ?: root.optJSONArray("translations")
                    ?: root.optJSONArray("bubbles")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("results")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val id = item.optInt("id", item.optInt("index", i + 1))
                        val trans = item.optString("translated", item.optString("english", item.optString("text", "")))
                        if (id != -1 && trans.isNotBlank()) {
                            map[id] = trans
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("OpenAiCompatibleClient", "Failed to parse translations JSON: $rawText", e)
        }
        return map
    }

    private fun cleanJson(raw: String): String {
        var str = raw.trim()
        if (str.startsWith("```json")) {
            str = str.removePrefix("```json").trim()
        } else if (str.startsWith("```")) {
            str = str.removePrefix("```").trim()
        }
        if (str.endsWith("```")) {
            str = str.removeSuffix("```").trim()
        }
        val firstObj = str.indexOf('{')
        val lastObj = str.lastIndexOf('}')
        val firstArr = str.indexOf('[')
        val lastArr = str.lastIndexOf(']')

        // If it's an outer JSON array [ ... ]
        if (firstArr != -1 && lastArr != -1 && (firstObj == -1 || firstArr < firstObj) && lastArr > lastObj) {
            return str.substring(firstArr, lastArr + 1)
        }
        // If it's an outer JSON object { ... }
        if (firstObj != -1 && lastObj != -1 && lastObj > firstObj) {
            return str.substring(firstObj, lastObj + 1)
        }
        return str
    }

    private fun extractErrorMessage(body: String, code: Int): String {
        return try {
            val json = JSONObject(body)
            val err = json.optJSONObject("error")
            err?.optString("message", "HTTP $code error") ?: "HTTP $code error"
        } catch (e: Exception) {
            "HTTP $code error"
        }
    }

    private fun extractRetryAfter(header: String?, body: String? = null): Long? {
        if (!header.isNullOrBlank()) {
            val headerSecs = header.trim().toDoubleOrNull()
            if (headerSecs != null) {
                return (headerSecs * 1000).toLong().coerceAtLeast(1000L)
            }
        }
        if (!body.isNullOrBlank()) {
            val regexSec = Regex("""retry in ([0-9]+(?:\.[0-9]+)?)\s*s""", RegexOption.IGNORE_CASE)
            val matchSec = regexSec.find(body)
            if (matchSec != null) {
                val secs = matchSec.groupValues[1].toDoubleOrNull()
                if (secs != null) return ((secs + 1.5) * 1000).toLong()
            }

            val regexMs = Regex("""retry in ([0-9]+(?:\.[0-9]+)?)\s*ms""", RegexOption.IGNORE_CASE)
            val matchMs = regexMs.find(body)
            if (matchMs != null) {
                val ms = matchMs.groupValues[1].toDoubleOrNull()
                if (ms != null) return (ms + 1000).toLong()
            }
        }
        return null
    }
}
