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
        fun fromJson(json: JSONObject): Bubble {
            val id = json.optInt("id", 1)
            val text = json.optString("text", "")
            val boxArr = json.optJSONArray("box")
            val box = mutableListOf<Float>()
            if (boxArr != null) {
                for (i in 0 until boxArr.length()) {
                    box.add(boxArr.optDouble(i, 0.0).toFloat())
                }
            }
            if (box.size < 4) {
                // fallback box
                while (box.size < 4) box.add(0f)
            }
            val vertical = json.optBoolean("vertical", true)
            val translated = json.optString("translated", "")
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

        fun parseListFromJson(jsonString: String): List<Bubble> {
            if (jsonString.isBlank()) return emptyList()
            return try {
                val array = JSONArray(jsonString)
                val list = mutableListOf<Bubble>()
                for (i in 0 until array.length()) {
                    list.add(fromJson(array.getJSONObject(i)))
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
    }
}
