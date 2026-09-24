# STP: Enclosure-Authoritative Wipe + Floating Reclassification (Jobs 13 & 15)

## Module
- `app/src/main/java/com/example/pipeline/wipe/` — `BubbleInkMask.kt`, `FlatWiper.kt`, `EnclosedInterior.kt`
- `app/src/main/java/com/example/pipeline/detect/BubbleTextMerger.kt` (Fix A consumer)
- `app/src/test/java/com/example/` — `BubbleInkMaskTest.kt`, `WiperHarnessTest.kt`, new tests

## Dependency Context
Read `AGENTS.md` (repo root) first — the Iteration Flywheel and the "never break non-bubble
artwork" law are binding. Read the full header comments of `BubbleInkMask.kt` and
`EnclosedInterior.kt`; the design rationale lives there and the change must not regress any
invariant they document (wall survival, box-relative inset, border-connected ink).

Two verified defects from device jobs (artifacts in `~/.local/share/AniTranslate/fixtures/`,
md5s in `FIXTURES.md` there; on-device `bubblesJson` boxes reproduced below):

**Defect 1 (job 13, page 1080x1507):** text ink of a merged two-lobe bubble survives OUTSIDE the
detector's rectangle. `BubbleInkMask.plan()` intersects every erase candidate with `boxMask`, so
ink inside the measured `bubbleMask` interior but outside the detector box is never wiped even
though the interior measurement is the documented authority. Detector boxes (normalized x1,y1,x2,y2):
```
0.638,0.036,0.906,0.226 ; 0.502,0.266,0.726,0.427 ; 0.060,0.349,0.284,0.497 ;
0.502,0.511,0.740,0.694 ; 0.347,0.511,0.500,0.613 ; 0.731,0.767,0.944,0.967 ;
0.464,0.800,0.754,0.958 ; 0.059,0.810,0.292,0.968   (all type "speech")
```
The surviving text belongs to the bubble of box `0.347,0.511,0.500,0.613`.

**Defect 2 (job 15, page 1448x2048):** a real bubble (text なるほどこれは重症ですね) was emitted as
`source="floating"` with a box that covers the caption area, not the bubble. Its translation was
typeset as free text and the JP text was never wiped. A `floating` entry is only legitimate when
its text lies on open artwork; when the text sits inside a closed white enclosure much larger than
the text block, it is a missed bubble.

## Signatures (guidance, not straitjackets — keep JVM-pure, no Android types)
- `BubbleInkMask.plan(...)`: when `bubbleMask != null`, the erase candidate bounds become
  `boxMask OR bubbleMask` (still intersected with nothing else). The wall/glyph classifier and the
  dilate bounds must never reach outside `bubbleMask` — the interior remains the outer wall of
  protection. When `bubbleMask == null`, behaviour is byte-identical to today.
- New JVM-pure object (suggest `wipe/EnclosureMap.kt`): given a full-page luminance array, produce
  per-pixel enclosure IDs by flood-filling bright interiors seeded from glyph components; expose
  per-enclosure area, bounding box, and whether a given box's centre lies inside it. Downscaling
  for speed is acceptable if the harness verifies at full resolution.
- `BubbleTextMerger` (or its pipeline caller): a `floating` entry whose text centre sits inside an
  enclosure whose area >= 4x the text box area is reclassified to a detector-sourced bubble with
  the enclosure-derived box, so the existing wipe machinery handles it.

## Test Anchor (count from XML, never the build banner; `--rerun-tasks` for real runs)
1. Existing suite green: `./gradlew testDebugUnitTest` (114 tests must stay green).
2. New unit test: synthetic two-lobe page (draw it in code: two white lobes joined by an open neck,
   glyph strokes in each) — plan() must wipe ink in BOTH lobes when the interior covers both, and
   must leave the wall stroke untouched.
3. New unit test: when `bubbleMask == null`, plan() output is identical to the pre-change
   algorithm on the same inputs (guards the no-mask path).
4. Harness, real page: `./gradlew testDebugUnitTest --tests '*WiperHarnessTest*' -Pharness.page=/home/bchow/.local/share/AniTranslate/fixtures/orig_13.png -Pharness.boxes="<boxes above at image scale>" -Pharness.out=/tmp/wiped13_fixed.png` — assert zero visible residual (luminance >= 0.90) inside the formerly-surviving region, and wall pixels on the bubble outlines survive (SSIM 1.0 outside interiors).
5. Harness, job 15 page: same pattern with boxes `0.865,0.001,1.0,0.217;0.753,0.059,0.854,0.224;0.495,0.000,0.645,0.199;0.313,0.001,0.501,0.242;0.222,0.065,0.305,0.180;0.004,0.191,0.076,0.284;0.766,0.392,1.0,0.692;0.006,0.402,0.250,0.709;0.811,0.718,0.995,0.990` — the reclassification test runs at unit level with a synthetic enclosure.

## Constraints
- Never wipe outside a measured interior or a validated enclosure. Wall, tail, border survive.
- No new ML models, no cloud calls, no new dependencies. Classical CV only.
- Do not edit `EnclosedInterior`'s measured-interior semantics; consume it.
- Fixtures are third-party scans: they stay OUT of git. Reference them by absolute path.
- Match existing code style: long rationale-bearing KDoc comments explaining WHY.

## Exit Condition
`./gradlew testDebugUnitTest` exits 0 with the new tests executed and green (verify per-class
counts from `app/build/test-results/testDebugUnitTest/*.xml`), and both harness runs produce
`/tmp/wiped13_fixed.png` / `/tmp/wiped15_fixed.png` with the assertions of Test Anchor 4 holding.
Report the per-class test totals and the residual counts you measured.
