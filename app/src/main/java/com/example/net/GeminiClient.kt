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
        mimeType: String = "image/jpeg"
    ): Result<List<Bubble>> = withContext(Dispatchers.IO) {
        try {
            val cleanBase = baseUrl.trim().removeSuffix("/")
            val cleanModel = model.trim().removePrefix("models/")
            val url = "$cleanBase/models/$cleanModel:generateContent?key=${apiKey.trim()}"

            val prompt = """
                Detect all speech bubbles and text in this manga page. Output ONLY JSON in format:
                {"bubbles":[{"id":1,"text":"待って！","box":[0.12,0.20,0.41,0.38],"vertical":true}]}
                box is normalized [x1,y1,x2,y2] between 0 and 1 (left, top, right, bottom).
                Include non-bubble narration if it is printed text. Skip pure SFX if unreadable.
                Order bubbles in standard Japanese reading order (top-to-bottom, right-to-left).
            """.trimIndent()

            val requestJson = JSONObject().apply {
                val contents = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            // Text prompt
                            put(JSONObject().apply { put("text", prompt) })
                            // Image part
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", mimeType)
                                    put("data", imageBase64)
                                })
                            })
                        }
                        put("parts", parts)
                    }
                    put(contentObj)
                }
                put("contents", contents)

                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.1)
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
                return@withContext Result.failure(ApiException(code, errorMsg, extractRetryAfter(response.header("Retry-After"))))
            }

            val textResponse = extractGeminiResponseText(body)
            val bubbles = parseBubblesJson(textResponse)
            Result.success(bubbles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun translateBubbles(
        baseUrl: String,
        apiKey: String,
        model: String,
        bubbles: List<Bubble>
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

            val prompt = """
                You are an expert manga/comic translator.
                Translate the following Japanese speech bubbles into natural English dialog.
                Return ONLY valid JSON in format:
                {"items":[{"id":1,"translated":"..."}]}
                Input bubbles:
                $inputList
                Rules:
                - Keep character names consistent.
                - Do not merge bubbles.
                - Provide ONLY natural English translation per id.
                - No translator notes, no romanization, no explanations.
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
                return@withContext Result.failure(ApiException(code, errorMsg, extractRetryAfter(response.header("Retry-After"))))
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
            val root = JSONObject(clean)
            val arr = root.optJSONArray("bubbles") ?: root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                list.add(Bubble.fromJson(arr.getJSONObject(i)))
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
            val root = JSONObject(clean)
            val arr = root.optJSONArray("items") ?: root.optJSONArray("translations") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val id = item.optInt("id", -1)
                val trans = item.optString("translated", item.optString("text", ""))
                if (id != -1 && trans.isNotBlank()) {
                    map[id] = trans
                }
            }
        } catch (e: Exception) {
            Log.w("GeminiClient", "Failed to parse translations JSON: $rawText", e)
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
        val firstBrace = str.indexOf('{')
        val lastBrace = str.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            str = str.substring(firstBrace, lastBrace + 1)
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
