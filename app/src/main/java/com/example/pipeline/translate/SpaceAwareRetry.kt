package com.example.pipeline.translate

import com.example.data.models.Bubble
import com.example.pipeline.typeset.BubbleBudget
import com.example.pipeline.typeset.Typesetter

/**
 * Space-aware retry decisions (Commander 2026-11-16; design and
 * calibration in rapi-work/briefs/20261116_anitranslate-space-aware-design.md).
 *
 * Calibration falsified the character-count trigger: blind translations
 * exceed the hard budget on only 2% of ground-truth units, while the
 * visible fit pain (floor shrink, hyphenation) is shape- and word-bound.
 * So distress is read from the typesetter's measured outcome, and the
 * budget serves as the retry instruction. Meaning is shed in a fixed
 * order — literal wording first, then nuance, keeping tone, names and
 * core meaning last — and a candidate that is not strictly shorter than
 * the standing translation is rejected, so a retry can never make fit
 * worse. At most [MAX_ROUNDS] rounds; after that the typesetter's
 * existing shrink/hyphenate/floor-resort order is the final safety net.
 */
internal object SpaceAwareRetry {

    const val MAX_ROUNDS = 2

    /** Distress = typeset below the preferred (professional standard) size, or a last-resort outcome. */
    fun isDistressed(outcome: Typesetter.LayoutOutcome?): Boolean {
        if (outcome == null) return false
        return outcome.fontSizePx < outcome.preferredSizePx ||
            outcome.hyphenated ||
            outcome.clipped ||
            outcome.phase == "floor-resort" ||
            outcome.fontSizePx <= outcome.minReadablePx * 1.05f
    }

    /** Round 1 aims at the preferred budget; round 2 tightens to 85% of it. */
    fun targetChars(budget: BubbleBudget.Budget, round: Int): Int = when (round) {
        1 -> budget.preferredChars
        else -> (budget.preferredChars * 0.85f).toInt().coerceAtLeast(1)
    }

    fun accepts(current: String, candidate: String?): Boolean =
        !candidate.isNullOrBlank() && candidate.length < current.length

    private fun lastResort(outcome: Typesetter.LayoutOutcome?): Boolean =
        outcome != null && (outcome.hyphenated || outcome.clipped || outcome.phase == "floor-resort")

    /**
     * A shorter string can still typeset worse (wider glyphs, fewer word
     * breaks, shout uppercasing), so acceptance is provisional until the
     * re-typeset outcome is compared. Outcomes are ranked by distress class
     * before font size: a last-resort layout (hyphenated, clipped, or
     * floor-resort) is a lettering failure that a marginally smaller
     * whole-word layout is not, so leaving the last-resort class is never
     * worse and entering it is always worse. Within the same class, worse
     * means still distressed without a larger font, or newly distressed.
     * The pipeline reverts any unit this flags, which is what makes "a
     * retry can never make fit worse" true on measured axes, not just on
     * character count.
     */
    fun madeWorse(before: Typesetter.LayoutOutcome?, after: Typesetter.LayoutOutcome?): Boolean {
        if (after == null) return false
        if (lastResort(after) != lastResort(before)) return lastResort(after)
        if (!isDistressed(after)) return false
        if (!isDistressed(before)) return true
        return after.fontSizePx <= (before?.fontSizePx ?: Float.MAX_VALUE)
    }

    fun pageContext(bubbles: List<Bubble>): String = buildString {
        appendLine("Full page dialogue (Japanese -> current English), for story context:")
        bubbles.forEach { b ->
            if (b.text.isNotBlank()) {
                appendLine("- #${b.id} \"${b.text.replace("\n", " ")}\" -> \"${b.translated.replace("\n", " ")}\"")
            }
        }
    }

    fun retryInstruction(bubble: Bubble, targetChars: Int, round: Int): String = buildString {
        appendLine("REWRITE ONE BUBBLE (space-aware retry, round $round of $MAX_ROUNDS).")
        appendLine("Bubble #${bubble.id} Japanese: \"${bubble.text.replace("\n", " ")}\"")
        appendLine("Current English (too large for its bubble): \"${bubble.translated.replace("\n", " ")}\"")
        appendLine("Rewrite ONLY bubble #${bubble.id} in at most $targetChars characters.")
        appendLine("Use the full-page context above for pronouns, tone and continuity.")
        appendLine("Shed meaning in this order: literal wording first, then nuance and filler;")
        appendLine("keep character voice and tone, keep names, keep the core meaning last.")
        appendLine("Prefer a shorter common word over a long one. Do not add new content.")
    }
}
