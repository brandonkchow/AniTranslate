package com.example.data.models

import org.json.JSONArray
import org.json.JSONObject

data class Bubble(
    val id: Int,
    val text: String, // Source Japanese text
    val box: List<Float>, // Normalized [x1, y1, x2, y2] in 0.0 .. 1.0 (left, top, right, bottom)
    val vertical: Boolean = true,
    var translated: String = "",
    var fontSizeSp: Float = 14f,
    var visible: Boolean = true
) {
    val x1: Float get() = box.getOrNull(0)?.coerceIn(0f, 1f) ?: 0f
    val y1: Float get() = box.getOrNull(1)?.coerceIn(0f, 1f) ?: 0f
    val x2: Float get() = box.getOrNull(2)?.coerceIn(0f, 1f) ?: 1f
    val y2: Float get() = box.getOrNull(3)?.coerceIn(0f, 1f) ?: 1f

    val width: Float get() = (x2 - x1).coerceAtLeast(0.01f)
    val height: Float get() = (y2 - y1).coerceAtLeast(0.01f)

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("text", text)
            val boxArr = JSONArray()
            box.forEach { boxArr.put(it.toDouble()) }
            put("box", boxArr)
            put("vertical", vertical)
            put("translated", translated)
            put("fontSizeSp", fontSizeSp.toDouble())
            put("visible", visible)
        }
    }

    companion object {
        fun fromJson(json: JSONObject, fallbackId: Int = 1): Bubble {
            val id = when {
                json.has("id") -> json.optInt("id", fallbackId)
                json.has("bubble_id") -> json.optInt("bubble_id", fallbackId)
                json.has("index") -> json.optInt("index", fallbackId)
                else -> fallbackId
            }

            val text = when {
                json.has("text") && json.optString("text").isNotBlank() -> json.optString("text")
                json.has("japanese") && json.optString("japanese").isNotBlank() -> json.optString("japanese")
                json.has("content") && json.optString("content").isNotBlank() -> json.optString("content")
                json.has("ocr") && json.optString("ocr").isNotBlank() -> json.optString("ocr")
                json.has("dialogue") && json.optString("dialogue").isNotBlank() -> json.optString("dialogue")
                json.has("transcript") && json.optString("transcript").isNotBlank() -> json.optString("transcript")
                else -> json.optString("text", "")
            }

            val box = parseBoxCoordinates(json)

            val vertical = when {
                json.has("vertical") -> json.optBoolean("vertical", true)
                json.has("is_vertical") -> json.optBoolean("is_vertical", true)
                json.has("orientation") -> json.optString("orientation", "vertical").contains("vertical", ignoreCase = true)
                else -> true
            }

            val translated = when {
                json.has("translated") && json.optString("translated").isNotBlank() -> json.optString("translated")
                json.has("english") && json.optString("english").isNotBlank() -> json.optString("english")
                json.has("translation") && json.optString("translation").isNotBlank() -> json.optString("translation")
                else -> json.optString("translated", "")
            }

            val fontSizeSp = json.optDouble("fontSizeSp", 14.0).toFloat()
            val visible = json.optBoolean("visible", true)

            return Bubble(
                id = id,
                text = text,
                box = box,
                vertical = vertical,
                translated = translated,
                fontSizeSp = fontSizeSp,
                visible = visible
            )
        }

        private fun parseBoxCoordinates(json: JSONObject): List<Float> {
            // 1. Check Gemini box_2d: [ymin, xmin, ymax, xmax] (could be 0..1000 or 0.0..1.0)
            val box2d = json.optJSONArray("box_2d")
            if (box2d != null && box2d.length() >= 4) {
                val y1Raw = box2d.optDouble(0, 0.0).toFloat()
                val x1Raw = box2d.optDouble(1, 0.0).toFloat()
                val y2Raw = box2d.optDouble(2, 0.0).toFloat()
                val x2Raw = box2d.optDouble(3, 0.0).toFloat()

                val scale = if (maxOf(x1Raw, y1Raw, x2Raw, y2Raw) > 1.5f) 1000f else 1f
                val x1 = (x1Raw / scale).coerceIn(0f, 1f)
                val y1 = (y1Raw / scale).coerceIn(0f, 1f)
                val x2 = (x2Raw / scale).coerceIn(0f, 1f)
                val y2 = (y2Raw / scale).coerceIn(0f, 1f)
                return listOf(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
            }

            // 2. Check standard "box" or "bbox" or "rect" array: [x1, y1, x2, y2]
            val boxArr = json.optJSONArray("box") ?: json.optJSONArray("bbox") ?: json.optJSONArray("rect")
            if (boxArr != null && boxArr.length() >= 4) {
                val v0 = boxArr.optDouble(0, 0.0).toFloat()
                val v1 = boxArr.optDouble(1, 0.0).toFloat()
                val v2 = boxArr.optDouble(2, 0.0).toFloat()
                val v3 = boxArr.optDouble(3, 0.0).toFloat()

                val maxVal = maxOf(v0, v1, v2, v3)
                val scale = if (maxVal > 1.5f) 1000f else 1f

                // Check if [x, y, width, height] format where v2 and v3 are width and height
                val isWidthHeight = (v2 + v0 <= 1.05f * scale && v3 + v1 <= 1.05f * scale && v2 < v0 && v3 < v1)
                val (x1, y1, x2, y2) = if (isWidthHeight) {
                    listOf(v0 / scale, v1 / scale, (v0 + v2) / scale, (v1 + v3) / scale)
                } else {
                    listOf(v0 / scale, v1 / scale, v2 / scale, v3 / scale)
                }
                return listOf(
                    minOf(x1, x2).coerceIn(0f, 1f),
                    minOf(y1, y2).coerceIn(0f, 1f),
                    maxOf(x1, x2).coerceIn(0f, 1f),
                    maxOf(y1, y2).coerceIn(0f, 1f)
                )
            }

            // 3. Check object coordinates: { "x1": ..., "y1": ..., "x2": ..., "y2": ... }
            val coordObj = json.optJSONObject("box") ?: json.optJSONObject("coordinates") ?: json.optJSONObject("bbox") ?: json
            if (coordObj.has("x1") || coordObj.has("left") || coordObj.has("x")) {
                val x1Raw = (coordObj.optDouble("x1", coordObj.optDouble("left", coordObj.optDouble("x", 0.0)))).toFloat()
                val y1Raw = (coordObj.optDouble("y1", coordObj.optDouble("top", coordObj.optDouble("y", 0.0)))).toFloat()
                val wRaw = coordObj.optDouble("width", coordObj.optDouble("w", 0.0)).toFloat()
                val hRaw = coordObj.optDouble("height", coordObj.optDouble("h", 0.0)).toFloat()
                val x2Raw = if (coordObj.has("x2")) coordObj.optDouble("x2", 0.0).toFloat() else if (coordObj.has("right")) coordObj.optDouble("right", 0.0).toFloat() else (x1Raw + wRaw)
                val y2Raw = if (coordObj.has("y2")) coordObj.optDouble("y2", 0.0).toFloat() else if (coordObj.has("bottom")) coordObj.optDouble("bottom", 0.0).toFloat() else (y1Raw + hRaw)

                val scale = if (maxOf(x1Raw, y1Raw, x2Raw, y2Raw) > 1.5f) 1000f else 1f
                val x1 = (x1Raw / scale).coerceIn(0f, 1f)
                val y1 = (y1Raw / scale).coerceIn(0f, 1f)
                val x2 = (x2Raw / scale).coerceIn(0f, 1f)
                val y2 = (y2Raw / scale).coerceIn(0f, 1f)
                return listOf(
                    minOf(x1, x2),
                    minOf(y1, y2),
                    maxOf(x1, x2),
                    maxOf(y1, y2)
                )
            }

            return listOf(0f, 0f, 0f, 0f)
        }

        fun parseListFromJson(jsonString: String): List<Bubble> {
            if (jsonString.isBlank()) return emptyList()
            return try {
                val array = JSONArray(jsonString)
                val list = mutableListOf<Bubble>()
                for (i in 0 until array.length()) {
                    list.add(fromJson(array.getJSONObject(i), fallbackId = i + 1))
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun listToJsonString(bubbles: List<Bubble>): String {
            val array = JSONArray()
            bubbles.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        /**
         * Sorts bubbles in authentic Japanese Manga reading order:
         * 1. Top-to-bottom tiers (panels).
         * 2. Right-to-left within each vertical tier.
         * 3. Renumbers IDs sequentially (1..N).
         */
        fun sortByMangaReadingOrder(bubbles: List<Bubble>): List<Bubble> {
            if (bubbles.size <= 1) return bubbles

            return bubbles.sortedWith(
                Comparator { b1, b2 ->
                    val yDiff = kotlin.math.abs(b1.y1 - b2.y1)
                    if (yDiff < 0.12f) {
                        // Same vertical panel band: Right-to-Left takes precedence
                        b2.x2.compareTo(b1.x2)
                    } else {
                        // Different vertical tier: Top-to-Bottom takes precedence
                        b1.y1.compareTo(b2.y1)
                    }
                }
            ).mapIndexed { index, bubble ->
                bubble.copy(id = index + 1)
            }
        }
    }
}
