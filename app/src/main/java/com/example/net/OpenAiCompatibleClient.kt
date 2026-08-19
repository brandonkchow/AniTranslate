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

            val response = HttpClientProvider.client.newCall(requestBuilder.build()).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (response.isSuccessful) {
                Result.success("Key valid (HTTP $code)")
            } else {
                val errorMsg = extractErrorMessage(body, code)
                Result.failure(ApiException(code, errorMsg, extractRetryAfter(response.header("Retry-After"))))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun detectBubbles(
        baseUrl: String,
        apiKey: String,
        model: String,
        imageBase64: String,
        mimeType: String = "image/jpeg",
        provider: ApiProvider
    ): Result<List<Bubble>> = withContext(Dispatchers.IO) {
        if (provider == ApiProvider.GROQ) {
            return@withContext Result.failure(IllegalArgumentException("Groq is text-only. Cannot process image detection."))
        }

        try {
            val cleanBase = baseUrl.trim().removeSuffix("/")
            val url = "$cleanBase/chat/completions"

            val systemPrompt = """
                You are a high-precision Japanese Manga Speech Bubble and OCR Detector.
                Detect all speech bubbles, dialogue text, and narration text boxes in this manga image.
                
                For each speech bubble, provide:
                - "id": integer starting from 1 in standard Japanese reading order (top-to-bottom, right-to-left)
                - "text": exact transcribed Japanese text / kanji / furigana from inside the bubble
                - "box": [x1, y1, x2, y2] normalized bounding box coordinates (0.0 to 1.0) where x1 is left, y1 is top, x2 is right, y2 is bottom
                - "vertical": boolean (true if vertical Japanese text, false if horizontal)
                
                Output ONLY a JSON object with this format:
                {
                  "bubbles": [
                    {
                      "id": 1,
                      "text": "いや これあれだよ！ きっと名のある牛だよ！",
                      "box": [0.70, 0.02, 0.96, 0.18],
                      "vertical": true
                    }
                  ]
                }
            """.trimIndent()

            val requestJson = JSONObject().apply {
                put("model", model.trim())
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        val contentArr = JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "Detect bubbles in this manga page:")
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:$mimeType;base64,$imageBase64")
                                })
                            })
                        }
                        put("content", contentArr)
                    })
                }
                put("messages", messages)
                put("temperature", 0.1)
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

            val response = HttpClientProvider.client.newCall(requestBuilder.build()).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorMsg = extractErrorMessage(body, code)
                return@withContext Result.failure(ApiException(code, errorMsg, extractRetryAfter(response.header("Retry-After"))))
            }

            val content = extractChatResponseContent(body)
            val bubbles = parseBubblesJson(content)
            Result.success(bubbles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun translateBubbles(
        baseUrl: String,
        apiKey: String,
        model: String,
        bubbles: List<Bubble>,
        provider: ApiProvider
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

            val systemPrompt = """
                You are an expert manga/comic translator.
                Translate the Japanese speech bubbles into natural English.
                Return ONLY valid JSON in format:
                {"items":[{"id":1,"translated":"..."}]}
                Rules:
                - Keep names consistent.
                - Do not merge bubbles.
                - Natural English dialog only.
                - No notes, explanations, or romanization.
            """.trimIndent()

            val requestJson = JSONObject().apply {
                put("model", model.trim())
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

            val response = HttpClientProvider.client.newCall(requestBuilder.build()).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorMsg = extractErrorMessage(body, code)
                return@withContext Result.failure(ApiException(code, errorMsg, extractRetryAfter(response.header("Retry-After"))))
            }

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

    private fun extractRetryAfter(header: String?): Long? {
        if (header == null) return null
        return try {
            header.toLongOrNull()?.times(1000)
        } catch (e: Exception) {
            null
        }
    }
}
