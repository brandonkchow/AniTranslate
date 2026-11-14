package com.example.pipeline.read

import org.json.JSONArray

/**
 * Pure-Kotlin Baberu OCR vocabulary and greedy decode logic, free of Android and ORT types
 * so it can be unit-tested directly on the JVM.
 *
 * Reference: `~/.cache/baberu-ocr/onnx_infer.py`
 * - ids 0..3 = <pad>/<bos>/<eos>/<unk>; id >= 4 -> charset[id - 4]
 * - repetition_penalty = 1.2 (negative logits multiplied, non-negative divided)
 * - run caps: 12 for single-char L/N content ids (excluding "ーｰ〜~"), 16 for symbols (id > 3)
 * - max_new_tokens = 256
 */
object BaberuDecode {

    const val PAD_ID = 0
    const val BOS_ID = 1
    const val EOS_ID = 2
    const val UNK_ID = 3
    const val CHARSET_OFFSET = 4

    const val DEFAULT_MAX_NEW_TOKENS = 256
    const val DEFAULT_REPETITION_PENALTY = 1.2
    const val DEFAULT_MAX_CONTENT_RUN = 12
    const val DEFAULT_MAX_SYMBOL_RUN = 16

    private const val EXCLUDED_ELONGATION_CHARS = "ーｰ〜~"

    class Vocab(charset: List<String>) {
        val id2ch: Map<Int, String>
        val bos: Int = BOS_ID
        val eos: Int = EOS_ID
        val vocabSize: Int = charset.size + CHARSET_OFFSET
        val contentIds: Set<Int>

        init {
            val map = HashMap<Int, String>(charset.size + 4)
            map[0] = ""
            map[1] = ""
            map[2] = ""
            map[3] = ""
            val content = HashSet<Int>()
            for ((i, ch) in charset.withIndex()) {
                val id = i + CHARSET_OFFSET
                map[id] = ch
                if (isContentToken(ch)) {
                    content.add(id)
                }
            }
            id2ch = map
            contentIds = content
        }

        fun decode(ids: List<Int>): String {
            val sb = StringBuilder(ids.size)
            for (id in ids) {
                if (id >= CHARSET_OFFSET) {
                    sb.append(id2ch[id] ?: "")
                }
            }
            return sb.toString()
        }

        companion object {
            fun fromJsonString(json: String): Vocab {
                val arr = JSONArray(json)
                val list = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                return Vocab(list)
            }

            /**
             * Matches Python:
             * `len(ch) == 1 and ch not in "ーｰ〜~" and unicodedata.category(ch)[0] in "LN"`
             */
            fun isContentToken(ch: String): Boolean {
                if (ch.length != 1) return false
                val c = ch[0]
                if (c in EXCLUDED_ELONGATION_CHARS) return false
                return isUnicodeLetterOrNumber(c)
            }

            private fun isUnicodeLetterOrNumber(c: Char): Boolean {
                return when (Character.getType(c).toByte()) {
                    Character.UPPERCASE_LETTER,
                    Character.LOWERCASE_LETTER,
                    Character.TITLECASE_LETTER,
                    Character.MODIFIER_LETTER,
                    Character.OTHER_LETTER,
                    Character.DECIMAL_DIGIT_NUMBER,
                    Character.LETTER_NUMBER,
                    Character.OTHER_NUMBER -> true
                    else -> false
                }
            }
        }
    }

    /**
     * Applies repetition penalty and trailing run-cap suppression in-place on [logits] (float64),
     * then returns the argmax token id.
     */
    fun selectNextToken(
        logits: DoubleArray,
        seq: List<Int>,
        toks: List<Int>,
        contentIds: Set<Int>,
        repetitionPenalty: Double = DEFAULT_REPETITION_PENALTY,
        maxContentRun: Int = DEFAULT_MAX_CONTENT_RUN,
        maxSymbolRun: Int = DEFAULT_MAX_SYMBOL_RUN
    ): Int {
        if (repetitionPenalty != 1.0) {
            val seen = HashSet<Int>(seq.size)
            for (tid in seq) {
                if (tid in 0 until logits.size && seen.add(tid)) {
                    val s = logits[tid]
                    logits[tid] = if (s < 0.0) s * repetitionPenalty else s / repetitionPenalty
                }
            }
        }

        val last = if (toks.isNotEmpty()) toks[toks.size - 1] else 0
        val cap = when {
            last in contentIds -> maxContentRun
            last > 3 -> maxSymbolRun
            else -> 0
        }
        if (cap > 0 && last in 0 until logits.size) {
            var run = 0
            for (i in toks.size - 1 downTo 0) {
                if (toks[i] == last) {
                    run++
                } else {
                    break
                }
            }
            if (run >= cap) {
                logits[last] = Double.NEGATIVE_INFINITY
            }
        }

        var bestIdx = 0
        var bestVal = Double.NEGATIVE_INFINITY
        for (i in logits.indices) {
            val v = logits[i]
            if (v > bestVal) {
                bestVal = v
                bestIdx = i
            }
        }
        return bestIdx
    }

    /**
     * Pure decode loop abstracted over the step function so the full decode loop (EOS stop,
     * maxNewTokens cap, pos increment, repetition penalty, run caps) can be unit-tested on JVM.
     */
    fun decodeLoop(
        initialLogits: DoubleArray,
        visionSeqLen: Int,
        vocab: Vocab,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
        repetitionPenalty: Double = DEFAULT_REPETITION_PENALTY,
        maxContentRun: Int = DEFAULT_MAX_CONTENT_RUN,
        maxSymbolRun: Int = DEFAULT_MAX_SYMBOL_RUN,
        stepFn: (nextTokenId: Int, pos: Int) -> DoubleArray
    ): List<Int> {
        var logits = initialLogits
        val seq = ArrayList<Int>(maxNewTokens + 1).apply { add(vocab.bos) }
        val toks = ArrayList<Int>(maxNewTokens)
        var pos = visionSeqLen + 1

        for (step in 0 until maxNewTokens) {
            val nxt = selectNextToken(
                logits = logits,
                seq = seq,
                toks = toks,
                contentIds = vocab.contentIds,
                repetitionPenalty = repetitionPenalty,
                maxContentRun = maxContentRun,
                maxSymbolRun = maxSymbolRun
            )
            if (nxt == vocab.eos) {
                break
            }
            toks.add(nxt)
            seq.add(nxt)
            if (toks.size >= maxNewTokens) {
                break
            }
            logits = stepFn(nxt, pos)
            pos += 1
        }
        return toks
    }
}
