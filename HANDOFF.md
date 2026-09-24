# Handoff — AniTranslate bubble-interior geometry

You are picking up active work on **AniTranslate** (`/home/bchow/projects/AniTranslate`,
Android, package `com.aistudio.bubbleforge.mngtr`), an on-device manga scanlation app.
The task is **pixel geometry for the wipe stage**: after bubble text is translated, the original
glyphs must be erased from the artwork without touching the bubble's outline or the screentone
behind it.

Load the `project-bulcan` skill before you start — it is binding for this project.

---

## 1. The one rule that governs this work

**Iterate on the PC. Ship to the phone only when the PC harness is green.**
(`project-bulcan` §4.2.) A JVM harness runs the *shipped* wipe code against a real manga page from
the command line, so geometry bugs are found in seconds on the laptop instead of through
install → tap → screenshot → inspect cycles on the handset.

This loop has already paid for itself twice: it found a JPEG-ringing threshold bug the device run
had hidden, and it is the only reason the current fix could be developed at all.

**Corollary you must honour:** do not hand Brandon a list of taps unless you truly cannot test it
yourself. He has explicitly said driving the emulator UI by hand wastes tokens
(*"for simple things let me know what i should do instead"*, `project-bulcan` §4.1).

---

## 2. Where things stand right now

- Repo: `/home/bchow/projects/AniTranslate`, branch `main`. The measured-interior fix (§3), the
  glyph layer (§3b), the text anchoring plus unread invariant (§3c) and the half-wipe repair
  (§3d, `3797c94`) are all committed and pushed.
- **PC suite: 115/0/0/1** — 8 of those are the anchoring regression, locked to the real page's own
  detection output (§3c), and 3 are the fused wall+lettering repair (§3d). The single skip is the
  harness exercising itself; the step-3 regression fixture is still lost, which is an open coverage
  gap, not a pass.
- **The half-wipe is fixed and measured (§3d).** On the focus page **4 of 9** boxes were leaving
  Japanese ink inside a *closed* wall — 7,091 dark px, of which the reported bubble's 2,441 was the
  largest instance rather than the only one — which is what put the English over unerased Japanese.
  All 9 now report `enclosedLeftover=0` with every wall intact, and the two fallback boxes are
  untouched to the byte.
- **Installed on the phone:** the Step A debug build (`adb install -r`, app data intact).
- **Verified on the device — three real pages, 2026-09-23.** The Step A build ran the focus page plus
  two others. Anchoring fired on all three and the coverage report is honest:

  | run | detector found | re-anchored | reported unread (`DETECT_UNREAD`) |
  | --- | --- | --- | --- |
  | focus page (job_10) | 9 bubbles | **1** | 1 — x38..47% y63..72% (score 0.75) |
  | FANZA page (job_11) | 4 bubbles | **3** | 0 |
  | ちんすこう page (job_12) | 10 bubbles | **2** | 1 — x2..12% y72..86% (score 0.91) |

  Same-page A/B against the pre-fix build (job_9; `orig_9.png` md5 == `orig_10.png` md5), counting
  changed pixels **inside the boxes the defect names**:

  | region on the focus page | pre-fix | post-fix |
  | --- | --- | --- |
  | the dropped bubble's interior | **0** changed | **15,011** changed |
  | its text region (the erased ink) | 0 | **13,984** |
  | the gutter where its translation was mis-typeset | **17,364** | **38** |
  | the never-read bubble's text region | 0 | 0 — reported, left in Japanese |

  So the bubble the reader *mis-placed* is now translated in its own bubble, the phantom region is
  gone, and the bubble the reader *skipped* is untouched **and named in the log**. Across the three
  pages 21 of 23 bubbles carry text; the 2 that do not are exactly the ones `DETECT_UNREAD` lists.
  Every re-anchored bubble's text was inspected for mis-assignment — each reads its own dialogue, and
  the 13 regions left `floating` on the FANZA page are SFX and credits, not dialogue.
- **Owed:** **Step B — manga-ocr ONNX on-device** — is what covers those two bubbles. Until it ships,
  an unread region is reported loudly and left in Japanese rather than wiped blank.

---

## 3. What the fix does, and why

**Root cause of the old failure.** `BubbleInterior.measureWallInset` marched **only the four centre
lines and took the max**, then built an **inscribed ellipse** from that single number, with a
`coerceIn(0, min(w,h)/3)` clamp hiding both failure modes. On a spiky bubble a centre ray slips down a
valley between spurs; on a text-dense bubble it stops on a **glyph**, not the wall. On a page of
scalloped/starburst bubbles this produced wrong geometry on **7 of 8** inspectable boxes.

**The cure — measure, never fit.** `EnclosedInterior` floods the bubble's white space outward from
the region centre. The interior is *that flooded region plus the glyphs it encloses*. The wall is
contiguous with the page, so **it can never be filled** — which makes **over-wipe structurally
impossible** and follows any wall shape for free, with **no inset to guess**.

**Three details that are load-bearing — do not undo them:**

1. **The measurement margin.** Detection boxes are tight by construction, so the stroke is clipped by
   the box edge and can never close. `EnclosedInterior.measurementMargin` (~8% of the longer side)
   widens the region **for measurement only**. Without it: 4/9. With it: 6/9.
2. **`BoxFrame`.** The detector's own box, and where it sits inside the widened region. The fallback
   ellipse is fitted in the **box** frame and lifted into the region.
3. **The fallback inset must be measured on the detector's box, never the widened region.** The
   region was padded to find the enclosure; re-measuring the inset there walks further and meets a
   glyph instead of the stroke. This cost a bubble the bottom arc of its outline — twice. See §5.

---

## 3b. The glyph layer — classification replaces the shape (2026-09-21, `5c98b31`)

**What changed.** `GlyphErase` labels the ink inside the detector's box into 8-connected components
and splits it by **shape alone**: *structure* is ink whose longest bbox side reaches 45% of the box's
short side (a wall, tail or spine crossing the box), ink touching the box edge, or a low-fill
hairline at ≥25% extent (a scallop bump, an arc); *glyph* is everything else. Glyphs are erased and
filled from the local background, and the halo dilation is **clipped to `boxMask ∧ ¬structure`** so it
can never grow onto a wall. `BubbleInkMask.plan` runs classification **first** — the enclosed
interior and the fitted shape are now reached only when the classifier finds nothing to act on. No
bound-derived value is painted in preference any more, which is what the clamp was.

**Why the shapes had to be demoted.** An interior — measured *or* fitted — is a shape *around* the
text, so it clips the block's corners: a plain ellipse leaves the ends of a vertical column outside
itself. Measured on a synthetic page with ground-truth wall/text masks, text surviving the wipe was
**104 px on the old code and 0 px on the new** for a tight box where no interior closes, with zero
wall ink repainted either way.

**On the real focus page, the detector's own nine boxes:** box 7 went 10,482 px wiped → 7,333 **with
7,584 px of structure now preserved** (the scalloped wall is no longer sliced); box 8 went 1,447 →
3,819 px (**2.6×**, 8,975 px of spikes kept). `wallBefore == wallSurvived` and `visibleResidual == 0`
on all nine.

**The lesson that matters more than the fix.** The harness used to measure residuals *inside
`plan.interior`* — the mask whose size was the bug — so it reported `visibleResidual=0
maxDarkDeviation=0.0000`, a perfect wipe, while a wall arc and 16% of the text were destroyed. **A
metric computed inside the artefact that produced the mistake cannot detect the mistake.** The
residual/deviation checks are now structure-aware, the classified path asserts its contract from its
own masks (every classified glyph erased, no classified structure painted), and anything that needs
to *prove* a wipe must be measured against ground truth — `/tmp/g/make_page.py` + `measure.py` build
a page whose wall and text pixels are known. Nothing about a real page can be proved by comparing
"is it still dark", because the wipe repaints text with text.

**Deliberate limitation:** ink *clipped* by the detector's box (a text column overflowing its bubble)
is indistinguishable from a wall by shape alone and is therefore preserved. Real lettering fits
inside its bubble; do not read a residual from an overflowing fixture as a wipe defect.

---

## 3c. Anchoring — the drop that was silent (2026-09-23, Step A)

Detection was never the failing stage; **content reading was**. On the real page the detector found
**9** bubbles and **9** text regions, the reading slot returned **8** boxes, the merge matched **7**,
and two bubbles' text was lost — logged as "left untouched (no text inside)", which was a **false
negative**: both bubbles contain a detected text region. Measured on that page (1080x1507):

| bubble | score | box | its text region | what happened |
| --- | --- | --- | --- | --- |
| A | 0.925 | x5.9–29.3% y80.9–96.8% | 0.917, inside it | read correctly, but the box came back ~42 px right: its centre landed in the gutter, so the text never matched, and the orphan box was typeset into empty artwork as a `floating` region |
| B | 0.849 | x31.5–49.8% y61.9–74.6% | 0.735, inside it | never read at all — no box came back for it |

Both bubbles' Japanese was **byte-identical in the wiped and the final image** (14,375 px and 5,929 px
of ink) — untouched, not damaged, and silently so.

**The fix is `BubbleTextAnchor`** (`detect/BubbleTextAnchor.kt`), a pure pre-pass run in
`PagePipeline` before matching:

- a box that matches no bubble is **snapped onto the text region of a bubble that has no text yet**,
  when one lies within 5% of the page diagonal (92.7 px here). It then matches normally: the text
  lands on its own bubble and the phantom floating region disappears, because the box is no longer
  orphaned;
- **only unfilled bubbles are anchor targets**, so a snap can never duplicate text onto a bubble that
  was read correctly. That is what makes the choice structural rather than a distance coin-flip: the
  correct target was 42.4 px away, and a correctly-read neighbour's text was 75.1 px away — so
  nearest-wins alone would have hung on a 1.8x margin;
- text the reading slot never returned is reported as **`DETECT_UNREAD`** in the run log (regions and
  scores) and its artwork is left untouched rather than wiped blank. It is no longer silent.

`BubbleTextAnchorTest` locks this to the real page's complete detection set (9 + 9 regions, 8 entries,
boxes exactly as the run recorded them), and includes the pre-fix state as its own test so the
invariant is proven to fire.

## 3d. The half-wipe — a wall and its lettering are one blob (2026-09-23, `3797c94`)

**The observation that opened it:** a bubble came back *cleaned on one side and still Japanese on the
other*, with the translated English sitting over the Japanese that was left — "floating English where
it shouldn't be". No count in this repo called it wrong: `visibleResidual=0` and `wallSurvived ==
wallBefore` were both true, because the wipe had done exactly what it was told.

**Why one blob.** `BubbleInkMask.INK_TOLERANCE` is `0.02f`, so *any* pixel darker than 0.98 counts as
ink — including the faint anti-aliased grey along a stroke, which is what lettering leaning on a wall
is made of. The classifier therefore sees wall + lettering as **one 8-connected component**, and its
verdict is per component and all-or-nothing: the component spans the box (`extent >= 0.45 x
shortSide`) so it is **structure**, and the lettering is kept along with the wall it touches. Columns
that stand clear of the wall are their own components and erase normally — which is why the failure
looked arbitrary, and why only some bubbles do it. Measured on the focus page: at threshold **t<80**
the fused blob splits into **50** components, so the bridge is mid-grey pixels, not solid contact.

**Why the enclosure separates them.** `EnclosedInterior` measures the paper the wall surrounds and its
paper threshold is **0.55** — the very grey pixels the ink test calls ink, it calls paper. The wall is
contiguous with the page, so it can never be *inside* its own enclosure, and ink inside that enclosure
is text by construction. That disagreement is the repair.

**What changed.** In the classification branch of `plan()`: measure the enclosure, take `inkWithin` of
it, promote what falls inside the detector's box from structure to glyph, then re-dilate within the
same box. Promotion can only *add* lettering — the mask it promotes from cannot contain a wall — and
the halo still stops at the wall, because a wall pixel is still not eligible under the bounds. Every
other path is untouched: no closing wall, nothing promoted, or the fallback, and `plan()` returns
exactly what it returned before.

**Evidence, and the loop it closed.** The harness was given an invariant **measured from the image
rather than from the classifier** — the classifier's own verdict is the thing that was wrong, so a
check derived from it could never fail: *dark ink inside a wall that closes must be gone.* With the
documented step-2 command:

| box | wall closes | leftover ink, pre-fix | post-fix |
| --- | --- | --- | --- |
| 0 | yes | 0 | 0 |
| 1 | yes | 0 | 0 |
| 2 | yes | **1,349** | 0 |
| 3 | yes | 0 | 0 |
| 4 | yes | **2,441** | 0 |
| 5 | yes | **1,463** | 0 |
| 6 | yes | **1,838** | 0 |
| 7 | no | 0 (fallback) | 0 |
| 8 | no | 0 (fallback) | 0 |

7,091 dark px of Japanese left in bubbles whose wall closes, in **4 of the 9** — the bubble in the
report is only the largest instance, not the only one. `changed` moves 112,585 -> 147,651 px of
1,627,560 (the 7,091 plus their halos); every box keeps `wallBefore == wallSurvived`; boxes 7 and 8
report **byte-identical** `glyph`/`structure` counts to the pre-fix build, which is the proof that the
fallback path was not touched — and why §6 item 3 stays open. The pre-fix arm reproduces the
`112,585` already recorded in §4, which is the anchor saying the same page and the same path are being
measured.

Two more things the harness learned: **a failing box no longer hides the other eight** (it reports the
full census, then asserts once — after the artifact is written, so a failing run still leaves an image
to look at), and the box list and page fixture are the ones already recorded in §4.

`BubbleInkMaskTest` (3 cases, run by step 1) locks the mechanism rather than the page: lettering
bridged to a closed wall by **mid-grey** pixels, with one free-standing glyph so the component
classifier stays in charge — without it the enclosure fallback would mask the bug. Pre-fix it fails on
the first assertion; post-fix all three pass. There is also a case pinning that promotion never reaches
outside the box the detector drew.

---

## 3e. The fitted fallback makes a claim too (2026-09-23)

§3d closed the half-wipe from the image, but its invariant is **skipped when no wall closes** — the
case that had already broken twice. What was left unguarded is the **fitted fallback**: the last
shape in the chain, fitted from the detector's box rather than measured from the image, reached only
when the classifier found under `MIN_TEXT_PIXELS` of text *and* the image offered no enclosure.
Nothing in the harness ran a claim against it. Its only assertion was `wallBefore > 0`, and a
**slice passes that**: `wallBefore` stays positive while the stroke is severed.

Worth knowing why this path is failure-prone by construction: reaching it means `glyphPixels < 10`,
and the fitted interior is a subset of the box it was fitted in — so anything the fit covers is ink
the classifier **already rejected as text**. Its only available failure mode is the over-wipe.

**The guard: the fitted shape may not cover ink that reaches the region's own edge.** A glyph sits
inside a bubble and is an island in paper; a stroke, a tail or a piece of artwork runs from one side
of the region to the other. So ink the flood reaches from the edge is not lettering, and the fit is
**clipped back** against it rather than trusted — an oversized shape can now only ever erase less.
Two candidate guards were killed by measurement first, before either was written:

- *"don't paint ink connected to ink outside the box"* — **0%** of the painted ink on boxes 7/8 is
  connected to anything outside the box.
- *"screentone dots are tiny isolated components, so guard on speckle"* — the painted ink forms
  components of **4,892 px** (box 7) and **1,442 px** (box 8). A dense dot field is 8-connected, not
  speckle, so that guard would never fire.

**Proof, in the order it was done** (`project-bulcan`: a guard nobody has seen fail is not a guard):

1. **RED** — `BubbleInkMaskTest`: a bar too long to be lettering, running out through the region on
   both sides, so the fitted shape bisects it. Against the pre-fix source:
   `BubbleInkMaskTest > the fitted fallback must not eat a stroke that leaves the region FAILED` /
   `java.lang.AssertionError at BubbleInkMaskTest.kt:154`. The fallback really did eat it.
2. **GREEN** — `borderConnectedInk`, seeded from the region's edge and 8-connected, with the shape
   never consulted; the same test passes and the plan returns an empty wipe.
3. **No regression** — the focus page is byte-identical: 9/9 boxes, **147,651** of 1,627,560 px
   changed, box 7 `glyph=3004 structure=7584`, box 8 `glyph=913 structure=8975`, every
   `visibleResidual=0`, every `wallBefore == wallSurvived`, every `enclosedLeftover=0`.
4. **The harness asserts it from the image for every box**, whenever no wall closed — deliberately
   an independent flood fill (`edgeConnectedDarkInk`), reading the pixels as they were **before** the
   wipe, so a wipe cannot hide what it painted by having painted it. It does run on boxes 7/8 (both
   `wallCloses=false`) and reports 0 violations, independently confirming that their damage lies
   inside the box and is **not** an edge-connectivity defect.

It fails open in one case worth knowing: for an **inverted** (dark) region the ink polarity flips and
`edgeConnectedDarkInk` returns nothing, so the assertion cannot fire there. The census above it has
the same light-bubble assumption.

**Scope, stated honestly:** this closes the guard gap. It does **not** fix boxes 7/8, which never
reach the fitted path at all — see §6 item 3 for what they actually do.

---

## 4. How to verify — in this order

```bash
cd ~/projects/AniTranslate

# 1. Full JVM suite (the harness skips itself unless -Pharness.page is passed, so CI is untouched).
./gradlew testDebugUnitTest --rerun-tasks --console=plain
#    expect: 115 tests, 0 failures, 0 errors, 1 skipped
#    (8 of them are BubbleTextAnchorTest — the dropped-bubble regression, §3c;
#     4 are BubbleInkMaskTest — the fused wall+lettering repair §3d and the fitted-fallback
#     guard §3e)
#    The 1 skip IS WiperHarnessTest, self-skipping by design — run step 2 to exercise it.
#    Count from app/build/test-results/testDebugUnitTest/*.xml, never from the build banner:
#    "BUILD SUCCESSFUL" is also what you get when the tests were up-to-date and never ran.

# 2. The focus page. Prefer the durable fixture — /tmp is cleared on every reboot.
#    ~/.local/share/anitranslate/fixtures/focus_page.jpg (724,369 B, md5 10fdd04ae630447606cb5ebc6032efe5)
#    Source copy lives in ~/Downloads/atxs52s7fnv21.jpg; both are outside git on purpose (a scan is
#    not ours to bake into history).
cp ~/.local/share/anitranslate/fixtures/focus_page.jpg /tmp/page.jpg
./gradlew testDebugUnitTest --tests '*WiperHarnessTest*' --rerun-tasks --console=plain \
  -Pharness.page=/tmp/page.jpg \
  -Pharness.out=/tmp/wiped_new.png \
  -Pharness.boxes="0.5023,0.5105,0.7401,0.6948,speech;0.5024,0.2650,0.7265,0.4273,speech;0.4637,0.7988,0.7548,0.9579,speech;0.6374,0.0364,0.9064,0.2265,speech;0.7323,0.7667,0.9439,0.9674,speech;0.0599,0.3482,0.2869,0.4961,speech;0.0589,0.8088,0.2925,0.9681,speech;0.3145,0.6192,0.4978,0.7463,speech;0.3454,0.5103,0.5001,0.6141,speech"
#    (those 9 boxes are the detector's own output for this page — you do not need to re-run ONNX)
#    expect: 9 lines, every one visibleResidual=0, every wallBefore == wallSurvived, AND every
#    enclosedLeftover=0 — that last one is measured from the image, not from the classifier (§3d).
#    (last run 2026-09-23: 9/9 boxes, 147,651 of 1,627,560 px changed; box 7 wallCloses=false
#     glyph=3004 structure=7584, box 8 wallCloses=false glyph=913 structure=8975 — unchanged by §3d
#     *and* by §3e, which is the point: byte-identical means UNCHANGED, and neither box is the
#     fallback path. Both counters are non-zero, and the fitted fallback returns glyphPixels=0 /
#     structurePixels=0 by construction (`Plan`'s defaults), so they leave through the classified
#     return with no enclosure — `BubbleInkMask.kt:204`. §6 item 3 corrects the attribution.
#     Pre-§3d the same command reported 112,585 px changed and 4 boxes with ink left inside a closed
#     wall — box 2 = 1,349, box 4 = 2,441, box 5 = 1,463, box 6 = 1,838. Reproduce that arm with
#     `git checkout 3797c94^ -- app/src/main/java/com/example/pipeline/wipe/BubbleInkMask.kt`.)

# 3. Regression page — the smooth-ellipse page the old model was tuned on. Must stay green.
#    FIXTURE LOST (verified 2026-09-21): /tmp/j4/img/working_4.jpg no longer exists and no durable
#    copy was ever made, so this step CANNOT currently be run. Re-extract a page with the same
#    character (smooth clean-ellipse bubbles) and keep it in ~/.local/share/anitranslate/fixtures/.
#    Treat "no regression fixture" as an open coverage gap, not as a pass.
./gradlew testDebugUnitTest --tests '*WiperHarnessTest*' --console=plain \
  -Pharness.page=/tmp/j4/img/working_4.jpg \
  -Pharness.out=/tmp/wiped_regress.png \
  -Pharness.boxes="0.0713,0.0529,0.4296,0.2200,speech;0.6092,0.0461,0.9298,0.2281,speech;0.5679,0.5423,0.9313,0.8290,speech"
```

## Build, sign, install (verified 2026-09-20)

- **The app is debug-signed, and that is currently the only option.** `app/build.gradle.kts` takes the
  release key from `$KEYSTORE_PATH` else `${rootDir}/my-upload-key.jks`, and that file **exists nowhere
  on this host** (it was probably a `/tmp` artifact). `assembleRelease` therefore dies at
  `:app:packageRelease` with *"storeFile specifies file ... which doesn't exist"*. The only APK ever
  produced is `app/build/outputs/apk/debug/app-debug.apk` — which is what the phone runs — so a debug
  rebuild installs over it cleanly. **Do not burn time on `assembleRelease` until the key is restored.**
- **Build:** `./gradlew assembleDebug` (~9 s warm; ~71.7 MB, arm64-v8a only).
- **Installing needs the phone's Wireless debugging toggled ON.** Its port changes on screen lock and on
  `adb` restarts, so it can never be cached: while the toggle is off, `adb mdns services` discovers
  nothing. Turn it on (Settings → Developer options → Wireless debugging), then either read the port off
  that screen or let mDNS find it, and run
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`. **The phone answers ping on the LAN with
  the toggle off, so reachability proves nothing about installability.**

## Verifying on the phone without asking anyone to eyeball it

- `adb mdns services` lists **stale ports for the same device alongside the live one** (three records,
  one device). The live port is whichever accepts `adb connect`; the stale ones return *Connection
  refused*. Try every address it prints before concluding the device is unreachable.
- The debug build is `run-as`-able:
  `adb shell run-as com.aistudio.bubbleforge.mngtr sh -c '...'`.
- Every processed page leaves **`cache/final_<N>.png`** plus a per-job dir (`cache/job_<N>/orig_1.png`),
  and each run writes **`files/run_logs/run_<id>_job_<N>.log`** — detector results, per-bubble source
  text, which translator slot ran and any guardrail refusal, then the wipe and typeset stages.
- **So on-device verification is: run the page, pull `final_<N>.png`, diff it against the PC render.**
  No eyes required.
- **Prove the phone ran the code under test** rather than assuming the build came from the right tree:
  the class must be in the dex (`unzip -p app-debug.apk classes5.dex | strings | grep EnclosedInterior`)
  **and** the local APK's md5 must equal the device's `/data/app/.../base.apk` md5.

**Then the check that matters most — pixel-diff the new wipe against the last shipped build.**
The three fallback boxes must be **byte-identical**; the six measured boxes should change. If a
fallback box moved, you have re-broken the frame rule in §3.3.

**Then an independent visual pass — delegate it, do not do it on the session model:**

```bash
saki-vision-parse <sheet.png> "<narrow question about which column is correct>"
```

Build the sheet as 3 columns per bubble: **SOURCE | SHIPPED | NEW**, one row per bubble, cropped just
around the box. Keep the question **short and narrow** and crop to **3 rows at most**. A 9-row sheet
with a long question is a coin flip: of two attempts at one, one returned a full answer and the other
hit the 5-minute print timeout and returned **nothing but a timeout notice**. Crop to the rows you
actually care about.

> This visual pass is not ceremony. **It caught two regressions that a green harness blessed.**

---

## 5. Traps that have already bitten this work

- **A green harness is not evidence of a correct wipe.** The harness defined "wall" as ink *outside*
  the mask, so an oversized mask counts its own wall as interior and passes — it was green on all 9
  bubbles while 4 were visibly destroyed on screen. The behaviour-based invariant
  (*the wipe must not change which pixels are reachable from outside the bubble*) is the metric that
  actually catches over-wipe.
- **Both regressions came from the same mistake:** treating a widening applied *for measurement* as if
  it did not change the *decision*. First the inset was re-measured on the padded region (a code
  comment claimed the margin "cancels out exactly" — it does not). Then, after fixing the inset, the
  fallback was still *sized* from the region's dimensions, so a box-relative inset produced a
  **larger** ellipse. Each cost one bubble the bottom arc of its outline.
- **The frame you decide in is not the region you run on.** Pass the frame explicitly (`BoxFrame`);
  passing more pixels is not passing more context.
- `saki-vision-parse` can exceed the tool timeout (420 s) — run it with `nohup … &` and poll the
  output file until it contains `DONE rc=`.
- Keep the margin rule in the **pure, Android-free layer** so the harness runs the identical code the
  APK runs. A re-implementation that agrees with the app is not evidence.
- Never let a vision model own geometry. VLM = text only; snap geometry to pixels.
- **Never pay for geometry.** Pay for translation, where quality genuinely varies.

---

## 6. What is actually left

1. **Step A is in** (§3c): anchoring plus the `DETECT_UNREAD` invariant, PC suite 111/0/0/1, and
   verified on three real pages on the phone (§2). What is left is **Step B — manga-ocr ONNX
   on-device**, so that *every* detected text region is read locally and a skipped bubble is covered
   instead of merely reported. Until it ships, unread regions stay loud and untouched.
2. ~~**Close the guard gap — highest value.** The over-wipe invariant is **skipped when no wall
   closes**, which is exactly the case that broke twice. An invariant that only runs in the case that
   already works is not a guard. Make the fallback path assert something too (e.g. the fitted shape
   must not enclose ink that is connected to the region's border).~~ **DONE — §3e (2026-09-23).**
   The fitted fallback now clips its own guess against ink that reaches the region's edge, and the
   harness asserts that claim from the image for every box where no wall closed. Honest scope: the
   fitted path is reached only when there was **no ink to classify at all**, so closing this gap
   moved **no number on the focus page** (147,651 px changed, byte-identical before and after). It
   was still the right guard — an unexercised path with an unasserted guess is where the next defect
   hides — but it is not the fix for boxes 7/8. See item 3.
3. **Boxes 7 and 8 are still visibly broken, in opposite directions** — and they are the two boxes
   §3d does **not** help, because neither ever encloses (both report `wallCloses=false`). Do not read
   "byte-identical to the shipped build" as "correct"; it means **unchanged**. Both are the
   lowest-confidence detections (.849 / .843) and their boxes bound a bubble at **no** threshold
   0.55–0.86, nor with morphological closing.
   - **MECHANISM CORRECTED (2026-09-23, from the harness's own output).** An earlier revision of
     this item — and the roadmap's P2 — held that both boxes take the **fitted fallback** and blamed
     their `wallInset` (4 = the floor; 52 = the `min(w,h)/3` clamp). That is **false**. The harness
     prints `inkMask=true glyph=3004 structure=7584` (box 7) and `glyph=913 structure=8975` (box 8),
     and the fitted fallback returns `Plan(wallInset, interior, BooleanArray(count), false)` —
     `glyphPixels`/`structurePixels` are `Plan` defaults of **0**. Non-zero on both counters means
     both leave through the **classified** return with no enclosure, `BubbleInkMask.kt:204`, where
     `interior` is the detector's **whole box**, no shape is ever fitted, and `wallInset` is
     **carried for reporting only — never used**. Neither box is a geometry failure.
   - **The real mechanism is one mistake, not two:** the classifier's glyph/structure split is being
     judged inside a box that does not bound the bubble. Box 7 calls too much **glyph** (3,004 px,
     including the scalloped wall's lobes) → over-wipe. Box 8 calls too much **structure**
     (8,975 px, including う and both え, which are then exempt from the wipe) → severe under-wipe.
   - **Why no count can see it:** with `interior` = the whole box, every dark pixel inside the box
     reads as "interior", so the wall census only ever measures the region *margin*. Measured from
     the image: 100% of these boxes' painted pixels lie **inside** the box and **none** is
     8-connected to the region's edge — so neither a wall census nor §3e's connectivity guard can
     catch this. It is a detection limit, which is the next bullet's point.
   - **Box 7 over-wipes** (the installed build already does this). The visual read — *"slices
     straight through the scalloped left bubble wall, obliterating the lobes"* — is right as a
     symptom; the fitted-shape blame was wrong. The classifier labels the lobes **glyph**, so the
     wipe paints them.
   - **Box 8 under-wipes severely.** 8,975 px are called **structure** and therefore exempt, so う
     and both え survive (*"う, the ellipsis dots, both え — only the ! is wiped"*). Not a collapsed
     interior — the interior is the full box.
   **This is a detection limit, not a wipe limit** — a box that does not bound the bubble cannot be
   measured. The honest fix is a **segmentation mask rather than a box** —
   `huyvux3005/manga109-segmentation-bubble` (YOLO11n-seg) is the candidate; check its licence before
   bundling, and keep the ONNX session optional so a failed load degrades to the current path rather
   than breaking the app. A cheaper stopgap: for a box whose inset lands **on** a clamp, refuse the
   wipe and hand the region back to the VLM path rather than painting with a number you know is a
   bound.
4. **Only then** rebuild the APK (`abiFilters` stays `arm64-v8a` only — ORT ships ~18 MB per ABI) and
   install it on Brandon's S25 (`SM-S938W`, Android 16, package `com.aistudio.bubbleforge.mngtr`).
5. Two older parts of the original four-part pixel fix remain open: a **contour-snap refiner**, and
   **typesetting into the measured interior** rather than the fitted shape.

---

## 6b. Retrospective grill-me on the half-wipe fix (2026-09-23, agy)

Brandon requested an adversarial retrospective review of `3797c94` via the delegated `agy`
workhorse, using the `grill-me` skill in retrospective mode. Run blocked earlier in the day by the
agy 1.2.9 hook-contract regression (see Cortex `debugging/20260923_agy_headless_tool_denial.md`,
commit 3668c21 in the dotfiles repo — fixed), then executed successfully.

- Full transcript: `~/.hermes/profiles/saki/cache/scratch/agy_grill.txt` (26.9 KB, 4 frontier
  rounds + verdict). Pre/post hashes of `BubbleInkMask.kt` and `EnclosedInterior.kt` match
  (`pre_grill_hashes.txt`) — the review changed nothing, as required (read-only; it ran the unit
  tests itself).
- Verdict — what to change (future work, not applied):
  1. **Inner-wall erasure risk:** the bulk promotion `glyphMask[i] = glyph || (inside && box)` can
     promote ink that is part of an inner closed loop or structural spine, not just lettering.
     Recommended: promote only components of `inside.mask` matching glyph shape criteria
     (bounding box / fill), or reject closed-loop/large-spine components.
  2. **Inverted bubbles:** `EnclosedInterior.PAPER_LUMINANCE` (0.55) does not invert for
     `isInverted` pages; `sampleInteriorColor` fallback also misbehaves on dark backgrounds.
  3. Perf nits: cache `EnclosedInterior.measure` per `plan()` (recomputed at BubbleInkMask.kt:219);
     replace the boxed `ArrayDeque<Int>` flood queue with an `IntArray`.
- Verdict — what to leave: the enclosure principle itself, the `bounds = box && !structure`
  leak-proof invariant, and the image-derived harness census.
- Not determinable: interaction with the planned YOLO11n-seg mask migration; screentone-bubble
  defect prevalence without a wider corpus.
- Remaining user-facing work unchanged: **translation must fill the wiped interior on-device**
  (the "floating english" complaint), SFX placement deferred, Step B (manga-ocr) HELD.

---

## 7. Context to read before you touch anything

```bash
cortex-recall "anitranslate bubble interior"     # curated local knowledge, <30 ms, zero tokens
```

- `~/.config/cortex/debugging/20260920_anitranslate_bubble_interior_topology.md` — **this work**:
  the enclosure model, the two self-inflicted regressions, and the five reusable lessons.
- `~/.config/cortex/debugging/20260920_anitranslate_vlm_geometry_onnx_detector.md` — why an offline
  ONNX detector owns geometry and the VLM only contributes text (§5 has the preceding wipe fix).

**Reporting back to Brandon** (he requires this): state the exact file paths changed, whether the
work was committed and pushed, and whether it belongs in Cortex. If nothing changed, omit it.
Credentials live in slot storage / `.env` — **never** read them into a summary, a log, or a commit.

---

## 8. P2, planner half: the measured interior is now the authority (2026-09-24)

The segmenter is real, and it changes the shape of the fix.
`huyvux3005/manga109-segmentation-bubble` (Apache-2.0, YOLO11n-seg on Manga109) is exported to
`best.onnx` — **11.0 MB fp32, opset 18** — and the spike answered a question the planner could not
answer from the box alone.

**What the spike found, and it is not what §6 assumed.** At both 640 and 1600 the model returns
**8 masks for 9 boxes**: mask 6's bbox `(0.327,0.516,0.494,0.743)` spans box 7 *and* box 8. They are
one bubble, split in two by the detector. The geometry says so three ways: the seven well-bounded
boxes sit at `cover_box` 0.735–0.821 against **π/4 = 0.785** (an ellipse inscribed in its rectangle),
mask 6 covers 0.694 of the two boxes' union box with **0 px falling outside it**, and its ink splits
clean:

| ink | px | components | largest |
| :--- | ---: | ---: | ---: |
| inside the mask (lettering) | 3,747 | 39 | 465 |
| outside it (artwork) | 11,129 | 298 | **7,594** |

The wall is *outside* the mask: the model segments the bubble's interior, not its outline. That is
what makes "ink inside the measured interior is text" safe to assert — the stroke cannot be promoted,
because it is not in the mask being promoted from.

**The repair direction, predicted before the change and matching the census exactly:** box 7's erase
*shrinks* by 1,257 px (3,004 planned → 1,747 inside the bubble; that difference is the wall it was
eating), and box 8's *grows* by 1,065 px (913 → 1,978; that is うええ promoted instead of exempted).

**What landed.** `plan()` takes an optional `bubbleMask`. It is folded into `boxMask` itself rather
than consulted separately, so every gate downstream — the ink candidates, the erase bounds, the
enclosure promotion, the fitted fallback — takes its authority from the measurement at once and none
can be left behind on the box. A second branch promotes ink inside the measured interior ahead of the
topology guess: the same argument line 178 makes, measured instead of inferred, no longer needing the
wall to close. With no mask the intersection is a no-op, so **the path that shipped is bit-identical**
and this cannot regress a page that has no segmenter.

**Evidence.** `BubbleInkMaskTest` 6/6; full suite **117/0/0/1** (was 115: +2 guards, 0 regressions).
The over-tall-box guard was seen to fail — with the measurement stripped back out of the authority it
reports `expected:<0> but was:<260>`, 260 px of artwork repainted at a boundary the bubble never had.
Restored byte-exact (`sha256 0097d4c5…`) and re-verified.

**Cost, measured on wuwei (Ryzen 3500U, a 2019 laptop CPU):** **202 ms median per 640 px page**
through the exported ONNX with the Python wrapper included — a 20-page chapter in 4.0 s. Export
fidelity is **worst per-mask IoU 0.995** against the `.pt`, so what was measured is what the phone
bundles. 11 MB fp32 → ~2.8 MB INT8. The phone is not the constraint.

**What is still open — this is half the work, and it is inert until the rest lands:**

1. **Nothing supplies a mask yet.** The new parameter is exercised only by unit tests. The Android
   side needs an `OnnxBubbleSegmenter` (optional ORT session over the bundled asset, degrading to
   today's path when the load fails), letterbox→page coordinate inversion, and mask→box matching.
   **Note the 8-for-9 problem**: masks do not map 1:1 onto detector boxes, so matching is by overlap
   and containment, never by index.
2. **`FlatWiper.wipeBubbles` must carry the mask through** to `plan` — it is the single caller
   (`FlatWiper.kt:88`) and already crops the box into region coordinates, which is the natural place
   to crop the mask the same way.
3. **The harness cannot pass a mask**, so the real-page census cannot yet run against boxes 7/8 with
   the real measurement. Until it can, §4's box 7/8 numbers describe the *box* path.
4. **The over-split is a typesetting problem too**: two boxes over one bubble means two translated
   text blocks typeset into two halves of one bubble. The mask fixes the wipe; it does not fix the
   layout. Option 1 was chosen for the wipe and that is what it delivers.
