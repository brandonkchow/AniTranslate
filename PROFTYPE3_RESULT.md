# PROFTYPE3 Result

- **Branch**: `fm/PROFTYPE3` (base `e8653b3`, not landed on `main`)
- **Rule source**: `rapi-work/bulcan-anitranslate/TYPESETTING-COMPARISON.md` row 4 (line breaks only; unit, size, centring, occupancy untouched)

## Diff Stat

```text
 app/src/main/java/com/example/pipeline/typeset/WordWrap.kt | 58 ++++++++++----------
 app/src/test/java/com/example/TypesetterWordWrapTest.kt    | 85 +++++++++++++++++++++++++----
 2 files changed, 104 insertions(+), 39 deletions(-)
```

## Verification

- `./gradlew :app:testDebugUnitTest`: **PASS** — 220 tests across 36 test files, 0 failed, 3 skipped (pre-existing; baseline was 214 tests, +6 new tests).
- `./gradlew :app:assembleDebug`: **PASS** (executed with temporary symlink to `/home/bchow/projects/AniTranslate/debug.keystore`, removed immediately after build).

## Pre-existing Assertion Conflicts

- `TypesetterWordWrapTest` > `over-wide word hyphenates at a syllable boundary with a visible hyphen`:
  - Previously passed a single-word string (`"unbelievably"`) to `WordWrap.wrap`, which now returns `null` under the last-word guard (`wordIndex == words.lastIndex`).
  - Also encoded the old 2-character fragment floor (`fragment.length >= 2`, `last.length >= 2`) and the non-syllable fallback escape (`|| fragment.length >= 2`).
  - Updated to wrap `"say unbelievably now"` (non-last-word position) and validate `WordWrap.hyphenateWord` with strict syllable boundaries and the 3-character fragment floor (`>= 3`).

## Adversarial Review Verdict

- **Verdict**: `NO-EVIDENCE` blocker
- `codex exec -m gpt-5.3-codex-spark --sandbox read-only` failed with `401 Unauthorized: Missing bearer or basic authentication in header` on both the initial run and one retry; the review output file was not produced.

CORTEX-DISPOSITION: none — disposable worker per brief
