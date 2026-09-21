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

- Repo: `/home/bchow/projects/AniTranslate`, branch `main`, **pushed HEAD `c2a9512`**, remote tree clean.
- **The working tree is DIRTY and uncommitted.** It holds the complete, measured-interior fix plus
  two regression fixes — see §3.
- The fix is **verified on the PC harness** (94/94 tests green, zero regression — §4) but
  **NOT committed, NOT pushed, and NOT shipped to the phone.** The phone still runs the old build.
- Coverage: on the focus page, **6 of 9 bubbles** now get a measured interior; **3 fall back** and are
  byte-identical to the shipped build (0 px changed).

**Your first action is to review that diff and commit it** — it is finished work that is currently
one `git checkout` away from being lost:

```bash
cd ~/projects/AniTranslate
git status --short
git diff --stat                     # EnclosedInterior.kt is untracked — read it in full
git diff -- app/src/main/java/com/example/pipeline/wipe/
```

Do not sweep unrelated config drift into that commit.

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

## 4. How to verify — in this order

```bash
cd ~/projects/AniTranslate

# 1. Full JVM suite (the harness skips itself unless -Pharness.page is passed, so CI is untouched).
./gradlew testDebugUnitTest --rerun-tasks --console=plain
#    expect: 103 tests, 0 failures, 0 errors, 1 skipped
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
#    expect: 9 lines, every one visibleResidual=0 with wallBefore == wallSurvived
#    (last run 2026-09-21: 9/9 boxes, 112,585 of 1,627,560 px changed; box 7 wallCloses=false
#     glyph=3004 structure=7584, box 8 wallCloses=false glyph=913 structure=8975)

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

1. **Commit and push the uncommitted fix** (§2), after a review you are satisfied with.
2. **Close the guard gap — highest value.** The over-wipe invariant is **skipped when no wall closes**,
   which is exactly the case that broke twice. An invariant that only runs in the case that already
   works is not a guard. Make the fallback path assert something too (e.g. the fitted shape must not
   enclose ink that is connected to the region's border).
3. **Boxes 7 and 8 are still visibly broken, in opposite directions** — and they are the two boxes
   the current fix does **not** help, because neither ever encloses. Do not read "byte-identical to
   the shipped build" as "correct"; it means **unchanged**. Both are the lowest-confidence detections
   (.849 / .843) and their boxes bound a bubble at **no** threshold 0.55–0.86, nor with
   morphological closing.
   - **Box 7 over-wipes.** Its fallback inset hits the floor (4 px) so the shape is too large:
     *"slices straight through the scalloped left bubble wall, obliterating the lobes"*. The
     installed build already does this.
   - **Box 8 under-wipes severely.** Its fallback inset lands on the **exact clamp**
     `min(w,h)/3 = 52`, collapsing the interior to ~9.9% of the box, so the text survives
     (*"う, the ellipsis dots, both え — only the ! is wiped"*).
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
