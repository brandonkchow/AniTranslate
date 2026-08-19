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
                You are an expert Japanese Manga OCR & Complete Dialogue Transcriber.
                Scan this manga page thoroughly and detect ALL readable Japanese dialogue and text regions:
                1. Speech balloons & thought bubbles (standard ovals, clouds, spiky/shout balloons)
                2. Narration and exposition text boxes (square/rectangular boxes)
                3. Free-floating dialogue and character speech directly on artwork (text without a bubble)
                4. Handwritten side-text, off-bubble murmurings, and character banter
                5. Sound effects (SFX) that contain readable kana/kanji dialogue

                CRITICAL SCANLATION RULES:
                - Do NOT skip text just because it lacks a speech bubble border. Detect ALL readable Japanese dialogue across every panel.
                - Transcribe the exact Japanese kanji, hiragana, katakana, and furigana faithfully.
                - Order items sequentially (id: 1, 2, 3...) in Japanese manga reading order (Right-to-Left, Top-to-Bottom).
                
                For each detected text item, return:
                - "id": integer starting from 1
                - "text": exact transcribed Japanese text
                - "box": [x1, y1, x2, y2] normalized bounding box coordinates (0.0 to 1.0) where x1 is left, y1 is top, x2 is right, y2 is bottom
                - "type": "bubble" | "narration" | "floating" | "side_text"
                - "vertical": boolean (true if vertical Japanese text, false if horizontal)
                
                Output ONLY a JSON object with this format:
                {
                  "bubbles": [
                    {
                      "id": 1,
                      "text": "いや これあれだよ！ きっと名のある牛だよ！",
                      "box": [0.70, 0.02, 0.96, 0.18],
                      "type": "bubble",
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
            val rawBubbles = parseBubblesJson(content)
            val sortedBubbles = Bubble.sortByMangaReadingOrder(rawBubbles)
            Result.success(sortedBubbles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun scanAllText(
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
                You are a specialized Japanese Manga Text OCR Scanner operating in "Scan-All-Text" Fallback Mode.
                Your mission is to find ALL Japanese text strings anywhere across this manga page:
                - Vertical columns of Japanese characters (tategaki: 縦書き)
                - Horizontal lines of Japanese text (yokogaki: 横書き)
                - Free-floating character dialogue, thoughts, and mutterings without any speech bubble
                - Narration boxes, side margin commentary, character names, and title subtitles
                - Sound effect (SFX) text that contains readable kana or kanji words

                CRITICAL DIRECTIVE:
                Ignore whether text is in a speech bubble or not! Treat ANY cluster of Japanese text on the page as a distinct translation target region.

                For each Japanese text string/cluster found:
                - "id": integer starting from 1 (in Right-to-Left, Top-to-Bottom reading order)
                - "text": exact transcribed Japanese text string / kanji / furigana
                - "box": [x1, y1, x2, y2] normalized bounding box coordinates (0.0 to 1.0) where x1 is left, y1 is top, x2 is right, y2 is bottom
                - "type": "floating" | "narration" | "bubble" | "side_text"
                - "vertical": boolean (true if vertical column, false if horizontal line)

                Output ONLY a JSON object:
                {
                  "bubbles": [
                    {
                      "id": 1,
                      "text": "テキスト",
                      "box": [0.10, 0.20, 0.30, 0.40],
                      "type": "floating",
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
                                put("text", "Scan and extract all Japanese text regions:")
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:$mimeType;base64,$imageBase64")
                                    put("detail", "high")
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
            val rawBubbles = parseBubblesJson(content)
            val sortedBubbles = Bubble.sortByMangaReadingOrder(rawBubbles)
            Result.success(sortedBubbles)
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
        storyContext: String = ""
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
