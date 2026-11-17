package com.example

import com.example.data.models.Bubble
import com.example.data.models.TextClass
import com.example.pipeline.detect.TextClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextClassifierTest {

    // ---- Rule-by-rule unit tests (positive & negative) ----

    @Test
    fun sideTextIsSkippedWhileNormalBubbleIsDialogue() {
        val sideText = Bubble(
            id = 1,
            text = "ドカッ",
            box = listOf(0.1f, 0.1f, 0.2f, 0.3f),
            vertical = true,
            type = "side_text"
        )
        val normal = sideText.copy(type = "bubble")

        assertEquals(TextClass.SKIP, TextClassifier.classify(sideText))
        assertEquals(TextClass.DIALOGUE, TextClassifier.classify(normal))
    }

    @Test
    fun punctuationOnlyIsSkippedWhileTextWithLettersIsKept() {
        val dotsBubble = Bubble(
            id = 1,
            text = "……",
            box = listOf(0.523f, 0.063f, 0.583f, 0.147f),
            vertical = true,
            type = "bubble"
        )
        val fullwidthDots = dotsBubble.copy(text = "．．．", type = "floating")
        val exclOnly = dotsBubble.copy(text = "！？")
        val blank = dotsBubble.copy(text = "   \n  ")
        val realSpeech = dotsBubble.copy(text = "う、うん．．．！")

        assertEquals(TextClass.SKIP, TextClassifier.classify(dotsBubble))
        assertEquals(TextClass.SKIP, TextClassifier.classify(fullwidthDots))
        assertEquals(TextClass.SKIP, TextClassifier.classify(exclOnly))
        assertEquals(TextClass.SKIP, TextClassifier.classify(blank))
        assertEquals(TextClass.DIALOGUE, TextClassifier.classify(realSpeech))
    }

    @Test
    fun largeHorizontalStylizedTitleIsSkippedWhileHorizontalSpeechBubbleIsDialogue() {
        val title = Bubble(
            id = 1,
            text = "好奇心ガールＨＩＮＡＴＡＨＩＮＡＴＡＰＬＵＳ",
            box = listOf(0.349f, 0.314f, 0.986f, 0.785f),
            vertical = false,
            type = "bubble"
        )
        val horizontalSpeech = Bubble(
            id = 2,
            text = "騙しやがってよオ！ドロじゃねえか！",
            box = listOf(0.464f, 0.799f, 0.755f, 0.958f),
            vertical = false,
            type = "bubble"
        )
        val bubbleWithSingleLatin = Bubble(
            id = 3,
            text = "Ｐ科の同期からお土産でちんすこうを頂きましたならけないです",
            box = listOf(0.865f, 0.0f, 1.0f, 0.217f),
            vertical = true,
            type = "bubble"
        )

        assertEquals(TextClass.SKIP, TextClassifier.classify(title))
        assertEquals(TextClass.DIALOGUE, TextClassifier.classify(horizontalSpeech))
        assertEquals(TextClass.DIALOGUE, TextClassifier.classify(bubbleWithSingleLatin))
    }

    @Test
    fun compactHorizontalBackgroundPrintIsSkippedWhileFloatingSpeechIsDialogue() {
        val bgPrint = Bubble(
            id = 1,
            text = "志望大字判定",
            box = listOf(0.364f, 0.0f, 0.630f, 0.042f),
            vertical = false,
            type = "floating"
        )
        // Negative: horizontal floating speech with exclamation/question stays DIALOGUE (fail-closed)
        val horizontalFloatingSpeech = Bubble(
            id = 2,
            text = "本当なの！？",
            box = listOf(0.30f, 0.20f, 0.50f, 0.25f),
            vertical = false,
            type = "floating"
        )
        // Negative: pure-kana horizontal floating text without kanji stays DIALOGUE (fail-closed)
        val kanaFloating = Bubble(
            id = 3,
            text = "おいしいね",
            box = listOf(0.30f, 0.20f, 0.45f, 0.24f),
            vertical = false,
            type = "floating"
        )

        assertEquals(TextClass.SKIP, TextClassifier.classify(bgPrint))
        assertEquals(TextClass.DIALOGUE, TextClassifier.classify(horizontalFloatingSpeech))
        assertEquals(TextClass.DIALOGUE, TextClassifier.classify(kanaFloating))
    }

    @Test
    fun structuredFloatingCardAndRankingAreInfo() {
        val rankingCard = Bubble(
            id = 1,
            text = "FANDAHHラフオンブ\n85位\n持ち球姫\nSHISHIKURATAMAKY",
            box = listOf(0.827f, 0.281f, 1.0f, 0.377f),
            vertical = false,
            type = "floating"
        )
        val singleLineRank = Bubble(
            id = 2,
            text = "第3位 人気作品",
            box = listOf(0.10f, 0.10f, 0.30f, 0.14f),
            vertical = false,
            type = "floating"
        )
        // Negative: vertical floating SFX stays DIALOGUE
        val verticalSfx = Bubble(
            id = 3,
            text = "ドドド12",
            box = listOf(0.10f, 0.10f, 0.15f, 0.30f),
            vertical = true,
            type = "floating"
        )

        assertEquals(TextClass.INFO, TextClassifier.classify(rankingCard))
        assertEquals(TextClass.INFO, TextClassifier.classify(singleLineRank))
        assertEquals(TextClass.DIALOGUE, TextClassifier.classify(verticalSfx))
    }

    // ---- Paired falsifiers & accepted controls on real sidecar units ----

    @Test
    fun pairedFalsifiersPage09SkipAndPage11InfoAndDialogueControls() {
        // Page-09 units that must be SKIP
        val p09Id2 = Bubble(2, "判定評価本人の偏差値", listOf(0.59f, 0.067f, 0.698f, 0.109f), vertical = false, type = "floating")
        val p09Id3 = Bubble(3, "志望大字判定", listOf(0.364f, 0.0f, 0.63f, 0.042f), vertical = false, type = "floating")
        val p09Id5 = Bubble(5, "．．．", listOf(0.01f, 0.005f, 0.078f, 0.127f), vertical = true, type = "floating")
        val p09Id6 = Bubble(6, "．．．．．．", listOf(0.011f, 0.117f, 0.057f, 0.21f), vertical = true, type = "bubble")
        val p09Id7 = Bubble(7, "好奇心ガールＨＩＮＡＴＡＨＩＮＡＴＡＰＬＵＳ", listOf(0.349f, 0.314f, 0.986f, 0.785f), vertical = false, type = "bubble")

        // Page-11 id 4 card that must be INFO (translated & typeset in place)
        val p11Id4 = Bubble(
            4,
            "FANDAHHラフオンブ\n85位\n持ち球姫\nSHISHIKURATAMAKY",
            listOf(0.827f, 0.281f, 1.0f, 0.377f),
            vertical = false,
            type = "floating"
        )

        // Accepted dialogue/floating controls that must stay DIALOGUE
        val p06Id15 = Bubble(15, "大人うるせえ", listOf(0.353f, 0.803f, 0.419f, 0.884f), vertical = true, type = "floating")
        val p06Id16 = Bubble(16, "おいしいね", listOf(0.262f, 0.802f, 0.331f, 0.875f), vertical = true, type = "floating")
        val p02Id10 = Bubble(10, "ガラララ．．．", listOf(0.172f, 0.82f, 0.21f, 0.925f), vertical = true, type = "floating")

        val classified = TextClassifier.classifyAll(
            listOf(p09Id2, p09Id3, p09Id5, p09Id6, p09Id7, p11Id4, p06Id15, p06Id16, p02Id10)
        )

        // Page-09 SKIP falsifiers
        for (i in 0..4) {
            assertEquals("page-09 unit ${classified[i].id} must be SKIP", TextClass.SKIP, classified[i].textClass)
            assertTrue("page-09 unit ${classified[i].id} must set isNonBalloon=true", classified[i].isNonBalloon)
        }

        // Page-11 INFO falsifier
        val card = classified[5]
        assertEquals(TextClass.INFO, card.textClass)
        assertFalse("INFO unit must NOT be skipped by isNonBalloon", card.isNonBalloon)

        // Page-06 & Page-02 DIALOGUE controls
        for (i in 6..8) {
            assertEquals("control unit ${classified[i].id} must remain DIALOGUE", TextClass.DIALOGUE, classified[i].textClass)
            assertFalse("DIALOGUE unit must have isNonBalloon=false", classified[i].isNonBalloon)
        }
    }

    @Test
    fun textClassRoundTripsThroughBubbleJson() {
        val original = listOf(
            Bubble(1, "……", listOf(0.1f, 0.1f, 0.2f, 0.2f), textClass = TextClass.SKIP),
            Bubble(2, "85位", listOf(0.8f, 0.2f, 1.0f, 0.3f), vertical = false, type = "floating", textClass = TextClass.INFO),
            Bubble(3, "おはよう", listOf(0.5f, 0.5f, 0.7f, 0.7f), textClass = TextClass.DIALOGUE)
        )
        val json = Bubble.listToJsonString(original)
        val parsed = Bubble.parseListFromJson(json)

        assertEquals(3, parsed.size)
        assertEquals(TextClass.SKIP, parsed[0].textClass)
        assertTrue(parsed[0].isNonBalloon)
        assertEquals(TextClass.INFO, parsed[1].textClass)
        assertFalse(parsed[1].isNonBalloon)
        assertEquals(TextClass.DIALOGUE, parsed[2].textClass)
        assertFalse(parsed[2].isNonBalloon)
    }
}
