package com.example

import com.example.pipeline.PagePipeline
import com.example.pipeline.read.BaberuDecode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaberuDecodeTest {

    // Small inline charset:
    // id 4 = "あ" (Lo -> content)
    // id 5 = "A"  (Lu -> content)
    // id 6 = "3"  (Nd -> content)
    // id 7 = "ー" (Lm, but explicitly excluded -> symbol)
    // id 8 = "〜" (So, excluded -> symbol)
    // id 9 = "!"  (Po -> symbol)
    // id 10 = "ab" (len > 1 -> symbol)
    private val charset = listOf("あ", "A", "3", "ー", "〜", "!", "ab")

    @Test
    fun vocabOffsetAndSpecialIds_matchPythonReference() {
        val vocab = BaberuDecode.Vocab(charset)
        assertEquals(1, vocab.bos)
        assertEquals(2, vocab.eos)
        assertEquals(4 + charset.size, vocab.vocabSize)

        assertEquals("", vocab.id2ch[0])
        assertEquals("", vocab.id2ch[1])
        assertEquals("", vocab.id2ch[2])
        assertEquals("", vocab.id2ch[3])
        assertEquals("あ", vocab.id2ch[4])
        assertEquals("!", vocab.id2ch[9])

        // Special ids 0..3 are skipped on decode
        assertEquals("あA!", vocab.decode(listOf(0, 1, 4, 5, 9, 2, 3)))
    }

    @Test
    fun contentIds_classificationMatchesUnicodeAndExclusions() {
        val vocab = BaberuDecode.Vocab(charset)
        assertTrue("Hiragana 'あ' is content", 4 in vocab.contentIds)
        assertTrue("ASCII 'A' is content", 5 in vocab.contentIds)
        assertTrue("Digit '3' is content", 6 in vocab.contentIds)

        assertFalse("'ー' is excluded from contentIds", 7 in vocab.contentIds)
        assertFalse("'〜' is excluded from contentIds", 8 in vocab.contentIds)
        assertFalse("'!' punctuation is not content", 9 in vocab.contentIds)
        assertFalse("Multi-char token 'ab' is not content", 10 in vocab.contentIds)
    }

    @Test
    fun repetitionPenalty_signRule_appliesCorrectly() {
        // id 1 (bos) in seq: positive logit divided by 1.2; id 4 in seq: negative logit multiplied by 1.2
        val logits = DoubleArray(11) { -10.0 }
        logits[4] = -5.0  // negative -> -5.0 * 1.2 = -6.0
        logits[5] = 6.0   // positive -> 6.0 / 1.2 = 5.0
        logits[6] = 4.5   // not in seq -> stays 4.5

        val nxt = BaberuDecode.selectNextToken(
            logits = logits,
            seq = listOf(1, 4, 5),
            toks = emptyList(),
            contentIds = setOf(4, 5, 6),
            repetitionPenalty = 1.2
        )

        assertEquals(-6.0, logits[4], 1e-9)
        assertEquals(5.0, logits[5], 1e-9)
        assertEquals(5, nxt)
    }

    @Test
    fun runCap_suppressesContentIdAt12_andSymbolIdAt16() {
        val vocab = BaberuDecode.Vocab(charset)

        // Content id 4 repeated 11 times -> NOT suppressed yet
        val logits11 = DoubleArray(vocab.vocabSize) { -100.0 }
        logits11[4] = 10.0
        logits11[5] = 1.0
        val pick11 = BaberuDecode.selectNextToken(
            logits = logits11,
            seq = listOf(1),
            toks = List(11) { 4 },
            contentIds = vocab.contentIds,
            repetitionPenalty = 1.0
        )
        assertEquals(4, pick11)

        // Content id 4 repeated 12 times -> suppressed to -Infinity, picks id 5
        val logits12 = DoubleArray(vocab.vocabSize) { -100.0 }
        logits12[4] = 10.0
        logits12[5] = 1.0
        val pick12 = BaberuDecode.selectNextToken(
            logits = logits12,
            seq = listOf(1),
            toks = List(12) { 4 },
            contentIds = vocab.contentIds,
            repetitionPenalty = 1.0
        )
        assertEquals(Double.NEGATIVE_INFINITY, logits12[4], 0.0)
        assertEquals(5, pick12)

        // Symbol id 7 ('ー') repeated 15 times -> NOT suppressed yet
        val logits15 = DoubleArray(vocab.vocabSize) { -100.0 }
        logits15[7] = 10.0
        logits15[5] = 1.0
        val pick15 = BaberuDecode.selectNextToken(
            logits = logits15,
            seq = listOf(1),
            toks = List(15) { 7 },
            contentIds = vocab.contentIds,
            repetitionPenalty = 1.0
        )
        assertEquals(7, pick15)

        // Symbol id 7 ('ー') repeated 16 times -> suppressed to -Infinity, picks id 5
        val logits16 = DoubleArray(vocab.vocabSize) { -100.0 }
        logits16[7] = 10.0
        logits16[5] = 1.0
        val pick16 = BaberuDecode.selectNextToken(
            logits = logits16,
            seq = listOf(1),
            toks = List(16) { 7 },
            contentIds = vocab.contentIds,
            repetitionPenalty = 1.0
        )
        assertEquals(Double.NEGATIVE_INFINITY, logits16[7], 0.0)
        assertEquals(5, pick16)
    }

    @Test
    fun decodeLoop_tracksPositionIdsAndStopsAtEos() {
        val vocab = BaberuDecode.Vocab(charset)
        val recordedCalls = mutableListOf<Pair<Int, Int>>()

        fun logitsFor(winner: Int): DoubleArray = DoubleArray(vocab.vocabSize) { -100.0 }.apply {
            this[winner] = 10.0
        }

        val tokens = BaberuDecode.decodeLoop(
            initialLogits = logitsFor(4), // emits 'あ'
            visionSeqLen = 256,
            vocab = vocab,
            maxNewTokens = 10
        ) { nextTokenId, pos ->
            recordedCalls.add(nextTokenId to pos)
            when (recordedCalls.size) {
                1 -> logitsFor(5) // emits 'A'
                else -> logitsFor(vocab.eos) // stops
            }
        }

        assertEquals(listOf(4, 5), tokens)
        assertEquals("あA", vocab.decode(tokens))
        assertEquals(listOf(4 to 257, 5 to 258), recordedCalls)
    }

    @Test
    fun resolveGeminiOpenAiBaseUrl_handlesDefaultBlankAndCustomStub() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            PagePipeline.resolveGeminiOpenAiBaseUrl("https://generativelanguage.googleapis.com/v1beta")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            PagePipeline.resolveGeminiOpenAiBaseUrl("https://generativelanguage.googleapis.com/v1beta/")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            PagePipeline.resolveGeminiOpenAiBaseUrl("  ")
        )
        assertEquals(
            "http://127.0.0.1:18080/v1",
            PagePipeline.resolveGeminiOpenAiBaseUrl("http://127.0.0.1:18080/v1")
        )
    }
}
