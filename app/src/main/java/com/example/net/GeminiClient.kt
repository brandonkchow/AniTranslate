package com.example.net

import android.util.Log
import com.example.data.models.Bubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class GeminiClient {

    suspend fun testKey(baseUrl: String, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trim().removeSuffix("/")
            val url = "$cleanBase/models?key=${apiKey.trim()}"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = HttpClientProvider.client.newCall(request).execute()
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
        storyContext: String = ""
    ): Result<Map<Int, String>> = withContext(Dispatchers.IO) {
        if (bubbles.isEmpty()) return@withContext Result.success(emptyMap())
        try {
            val cleanBase = baseUrl.trim().removeSuffix("/")
            val cleanModel = model.trim().removePrefix("models/")
            val url = "$cleanBase/models/$cleanModel:generateContent?key=${apiKey.trim()}"

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

            val prompt = """
                You are a master manga localizer and comic translator.
                Translate the following Japanese manga dialogue into natural, punchy, conversational English.
                $contextSection
                Input bubbles (ordered in Japanese reading order: Top-to-Bottom, Right-to-Left):
                $inputList
                
                Localization & Translation Guidelines:
                - Holistic Translation: Read the entire conversation on this page as a single cohesive scene.
                - Pronoun & Subject Resolution: Japanese frequently omits subjects (e.g. 私, 俺, 貴方, 彼, 彼女). Infer and supply the correct English pronouns and subjects based on speaker context, relationship, and conversational flow.
                - Character Voice & Tone: Preserve unique character personalities, slang, emotional weight, comedic timing, and shouting intensity.
                - Exclamations & SFX: Translate sound effects or shouts naturally (e.g. "うますぎ警報 発令―――！！" -> "DELICIOUSNESS WARNING ISSUED---!!").
                - Lettering-Friendly: Provide concise, idiomatic comic dialogue that fits standard speech bubbles.
                - Output format: Return ONLY valid JSON in format:
                {"items":[{"id":1,"translated":"..."}]}
            """.trimIndent()

            val requestJson = JSONObject().apply {
                val contents = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        }
                        put("parts", parts)
                    }
                    put(contentObj)
                }
                put("contents", contents)

                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.3)
                })
            }

            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = HttpClientProvider.client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorMsg = extractErrorMessage(body, code)
                return@withContext Result.failure(ApiException(code, errorMsg, extractRetryAfter(response.header("Retry-After"), body)))
            }

            val textResponse = extractGeminiResponseText(body)
            val translations = parseTranslationsJson(textResponse)
            Result.success(translations)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractGeminiResponseText(body: String): String {
        return try {
            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val firstPart = parts?.optJSONObject(0)
            firstPart?.optString("text", "").orEmpty()
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
            Log.w("GeminiClient", "Failed to parse bubbles JSON: $rawText", e)
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
            Log.w("GeminiClient", "Failed to parse translations JSON: $rawText", e)
        }
        return map
    }

    private fun cleanJson(raw: String): String {
        var str = raw.trim()
        val markdownRegex = Regex("""```(?:json)?\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
        val match = markdownRegex.find(str)
        if (match != null) {
            str = match.groupValues[1].trim()
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
        // 1. Check Retry-After HTTP response header (seconds)
        if (!header.isNullOrBlank()) {
            val headerSecs = header.trim().toDoubleOrNull()
            if (headerSecs != null) {
                return (headerSecs * 1000).toLong().coerceAtLeast(1000L)
            }
        }
        // 2. Check JSON error.details retryDelay: "18.302319871s" or "300ms"
        if (!body.isNullOrBlank()) {
            try {
                val json = JSONObject(body)
                val err = json.optJSONObject("error")
                val details = err?.optJSONArray("details")
                if (details != null) {
                    for (i in 0 until details.length()) {
                        val d = details.optJSONObject(i) ?: continue
                        val retryDelayStr = d.optString("retryDelay", "")
                        if (retryDelayStr.isNotBlank()) {
                            val parsed = parseDurationStringToMs(retryDelayStr)
                            if (parsed != null) return parsed
                        }
                    }
                }
            } catch (_: Exception) {}

            // 3. Check regex on error message: "Please retry in 18.302319871s" or "retry in 361.532506ms"
            val regexSec = Regex("""retry in ([0-9]+(?:\.[0-9]+)?)\s*s""", RegexOption.IGNORE_CASE)
            val matchSec = regexSec.find(body)
            if (matchSec != null) {
                val secs = matchSec.groupValues[1].toDoubleOrNull()
                if (secs != null) return ((secs + 1.5) * 1000).toLong() // add 1.5s safety buffer
            }

            val regexMs = Regex("""retry in ([0-9]+(?:\.[0-9]+)?)\s*ms""", RegexOption.IGNORE_CASE)
            val matchMs = regexMs.find(body)
            if (matchMs != null) {
                val ms = matchMs.groupValues[1].toDoubleOrNull()
                if (ms != null) return (ms + 1000).toLong() // add 1000ms safety buffer
            }
        }
        return null
    }

    private fun parseDurationStringToMs(str: String): Long? {
        val clean = str.trim()
        if (clean.endsWith("ms", ignoreCase = true)) {
            return clean.removeSuffix("ms").toDoubleOrNull()?.let { (it + 500).toLong() }
        }
        if (clean.endsWith("s", ignoreCase = true)) {
            return clean.removeSuffix("s").toDoubleOrNull()?.let { ((it + 1.5) * 1000).toLong() }
        }
        return clean.toDoubleOrNull()?.let { ((it + 1.5) * 1000).toLong() }
    }
}
