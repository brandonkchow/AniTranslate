# Manga Translator — AI Assistant & Agent Guidelines

Refer to `AGENTS.md` for full architectural standards, test suites, and the **Iteration Flywheel** specifications.

### Core Commands & Flywheel Execution
- **Run Pipeline & Flywheel Tests**: `gradle :app:testDebugUnitTest --tests "com.example.MangaDetectionIntegrationTest"`
- **Inspect Artifacts**: Check `/app/build/outputs/test_pipeline/` for rendered images (`1_original.png`, `2_masked_wiped.png`, `3_typeset_result.png`, `4_side_by_side_comparison.png`, `5_annotated_bboxes.png`) and scorecards (`flywheel_metrics_scorecard.md`, `feedback_flywheel_report.json`).
- **Compile Verification**: Always verify builds with `compile_applet`.
