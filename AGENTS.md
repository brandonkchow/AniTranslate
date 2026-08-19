# Manga Translator — Engineering & Iteration Flywheel Guidelines

Welcome to the Manga Translator codebase. This document codifies the engineering standards, architecture, testing frameworks, translation localization guidelines, and the **Continuous Iteration Flywheel** used to continuously benchmark, evaluate, and refine manga OCR, bubble detection, speech bubble wiping, and multi-line comic typesetting.

---

## 🔄 The Iteration Flywheel Workflow

Every developer and AI agent working on this codebase must follow the 5-step Iteration Flywheel whenever making changes to the detection, wiping, translation, or typesetting pipelines:

```
                  ┌──────────────────────────────┐
                  │ 1. Run Automated Test Suite  │
                  │ (Robolectric & Pipeline Spec)│
                  └──────────────┬───────────────┘
                                 │
                                 ▼
                  ┌──────────────────────────────┐
                  │ 2. Inspect Visual Artifacts  │
                  │ (1_orig, 2_wiped, 3_typeset, │
                  │  4_comparison, 5_bboxes)     │
                  └──────────────┬───────────────┘
                                 │
                                 ▼
                  ┌──────────────────────────────┐
                  │ 3. Review Diagnostics Report │
                  │ (flywheel_metrics_scorecard, │
                  │  overflow risk, SSIM score)  │
                  └──────────────┬───────────────┘
                                 │
                                 ▼
                  ┌──────────────────────────────┐
                  │ 4. Formulate & Apply Targeted│
                  │    Algorithmic Enhancements  │
                  └──────────────┬───────────────┘
                                 │
                                 ▼
                  ┌──────────────────────────────┐
                  │ 5. Compile & Re-Benchmark    │
                  │ (Verify Improvements & Lock) │
                  └──────────────┬───────────────┘
                                 │
                                 └── (Repeat Flywheel)
```

---

## 📖 Manga Reading-Order & Holistic Translation Engine

### 1. Authentic Manga Reading Order Sorting (`Bubble.sortByMangaReadingOrder`)
- Japanese manga is read **Right-to-Left (RTL)** and **Top-to-Bottom (TTB)** across comic panels.
- Dialogue bubbles must be sorted into tiered vertical panel bands. Within each band (`|y1 - y2| < 0.12`), rightmost bubbles (`x2` descending) take precedence before leftward bubbles.
- Bounding boxes are renumbered sequentially (`id = 1..N`) so that translation models receive lines in the exact sequence the author intended them to be read.

### 2. Intra-Page Holistic Scene Translation
- Translating bubbles in isolation causes disjointed, robotic dialogue and pronoun confusion.
- All bubbles on a single page are sent as a complete conversation thread to the model.
- **Pronoun & Subject Resolution**: Japanese omitted subjects (私, 俺, 貴方, 彼, 彼女) and passive referents are resolved naturally using the whole-scene conversational context.
- **Voice & Tone Consistency**: Character personality, comedic timing, emotional intensity, honorifics, and shout volume are preserved.

### 3. Cross-Page Story Continuity & Memory Chaining
- When translating multi-page chapters in `PagePipeline.kt`, dialogue from the preceding page is extracted and passed in as `storyContext`.
- This ensures seamless continuity for multi-page monologues, scene transitions, character reveals, and consistent terminology across an entire chapter.

---

## 🛠️ Pipeline Architecture & Key Modules

1. **Detection & OCR (`com.example.net.GeminiClient`)**:
   - Sends image base64 to Gemini 2.5 Flash with structured JSON schema.
   - Extracts normalized `[ymin, xmin, ymax, xmax]` bounding boxes, Japanese text, and vertical/horizontal text flow orientation.
   - Sorts detections in manga reading order via `Bubble.sortByMangaReadingOrder`.

2. **Bubble Inpainting & Wiping (`com.example.pipeline.wipe.FlatWiper`)**:
   - Applies contour-safe insetting (`2–5px` density-scaled) to preserve hand-drawn speech balloon contours and frame borders.
   - Adapts to interior bubble backgrounds by sampling non-text pixels (`luminance >= 0.65`).
   - Maintains 100% pixel fidelity (`SSIM = 1.0`) on all non-bubble artwork.

3. **Comic Typesetting Engine (`com.example.pipeline.typeset.Typesetter`)**:
   - Uses an 8-iteration **binary search** to find the optimal readable font size (0.5px precision) that fits both bubble width and height bounds.
   - Applies **elliptical safety padding** (larger horizontal insets for oval geometry).
   - Dynamically determines line limits and line-spacing based on aspect ratio.
   - Formats emphasis (SFX / shouting exclamation marks) with bold comic typeface weights.

4. **Multi-Job Execution & Tracing (`com.example.utils.RunLogger`)**:
   - High-resolution millisecond event tracing for each page lifecycle stage (`PREPARE`, `DETECT_START`, `DETECT_DONE`, `WIPE_DONE`, `TRANSLATE_DONE`, `TYPESET_DONE`).

---

## 📊 Visual Verification & Test Artifacts

Whenever the test suite executes (`gradle :app:testDebugUnitTest --tests "com.example.MangaDetectionIntegrationTest"`), the test runner exports high-resolution visual assets and structured scorecards to `/app/build/outputs/test_pipeline/`:

| Artifact | Purpose | Evaluation Criteria |
| :--- | :--- | :--- |
| `1_original.png` | Baseline raw test page | Source reference for Japanese text and panel layout. |
| `2_masked_wiped.png` | Inset-wiped page | Outer speech balloon borders and panel lines MUST remain crisp and intact. |
| `3_typeset_result.png` | Translated & rendered page | English text must be centered, legible, and unclipped with no overflow. |
| `4_side_by_side_comparison.png` | Dual-column side-by-side composite | Quick visual diffing of Japanese vs. English placement. |
| `5_annotated_bboxes.png` | Bounding box overlay | Red bounding box outlines with blue dimension labels. |
| `feedback_flywheel_report.json` | Machine-readable metrics | Structured JSON for CI/CD and programmatic verification. |
| `flywheel_metrics_scorecard.md` | Human-readable scorecard | Detailed markdown table of aspect ratios, text density, and overflow risk. |

---

## 📋 Rules for Future Agents

1. **Always Verify with Tests First**: Before declaring any change complete, execute `gradle :app:testDebugUnitTest` and `compile_applet`.
2. **Never Break Non-Bubble Artwork**: The wiper and typesetter must only modify pixels inside valid speech bubble bounding boxes. Non-bubble artwork must maintain 100% pixel fidelity (SSIM = 1.0 outside bubbles).
3. **Respect Manga Reading Order & Context**: Always sort speech bubbles with `Bubble.sortByMangaReadingOrder` and pass `storyContext` for multi-page translations.
4. **Preserve Secrets & Multi-Provider Architecture**: Keep `SlotStorage` flexible for Gemini, OpenAI, Claude, DeepSeek, and custom endpoints.
5. **Proactive Flywheel Improvements**: When you identify opportunities to improve text wrapping, font rendering, bubble masking, or test telemetry, implement the improvement, run the tests, and record the metrics in the flywheel scorecard.
