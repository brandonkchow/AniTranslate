# Manga Translator — Engineering & Iteration Flywheel Guidelines

Welcome to the Manga Translator codebase. This document codifies the engineering standards, architecture, testing frameworks, and the **Continuous Iteration Flywheel** used to continuously benchmark, evaluate, and refine manga OCR, bubble detection, speech bubble wiping, and multi-line comic typesetting.

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

## 🛠️ Pipeline Architecture & Key Modules

1. **Detection & OCR (`com.example.net.GeminiClient`)**:
   - Sends image base64 to Gemini 2.5 Flash with structured JSON schema.
   - Extracts normalized `[ymin, xmin, ymax, xmax]` bounding boxes, Japanese text, and vertical/horizontal text flow orientation.
   - Slot-based key management (`SlotStorage`) allows automatic retrieval of configured AI Studio secrets.

2. **Bubble Inpainting & Wiping (`com.example.pipeline.wipe.FlatWiper`)**:
   - Applies contour-safe insetting (`2–5px` density-scaled) to preserve hand-drawn speech balloon contours and frame borders.
   - Adapts to interior bubble backgrounds by sampling non-text pixels (`luminance >= 0.65`).
   - Supports circular/oval masking and rounded-rectangle panel wiping.

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
3. **Preserve Secrets & Multi-Provider Architecture**: Keep `SlotStorage` flexible for Gemini, OpenAI, Claude, DeepSeek, and custom endpoints.
4. **Proactive Flywheel Improvements**: When you identify opportunities to improve text wrapping, font rendering, bubble masking, or test telemetry, implement the improvement, run the tests, and record the metrics in the flywheel scorecard.
