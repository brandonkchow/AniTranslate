# AniTranslate Implementation Roadmap

This roadmap governs the evolution of AniTranslate from basic bounding-box flat wiping to a production-grade scanlation studio.

---

## 🚀 Milestones

### Phase 1: On-Device Core Quality Leap (Current Focus)
- [ ] **Step 1.1: Smart Ink-Mask Wiper (`FlatWiper.kt` -> `SmartWiper.kt`)**
  - Implement adaptive binarization (Otsu thresholding) inside bubble bounds.
  - Implement 2px morphological dilation to capture text anti-aliasing.
  - Fill only dilated ink pixels using local neighborhood median background sampling.
  - Eliminate the "white sticker" effect; preserve 100% of bubble outlines and screentones.
- [ ] **Step 1.2: Convex Elliptical Typesetter (`Typesetter.kt`)**
  - Implement elliptical line constraint solver ($W(y) = W \cdot \sqrt{1 - ((2y - H)/H)^2}$).
  - Implement word-wrapping and line balancing for diamond-shaped manga dialogue bubbles.
  - Implement dual-pass rendering (Pass 1: 3px white outline stroke, Pass 2: black fill) for guaranteed legibility across any background.
  - Ensure comic typography support via `FontHelper.kt`.
- [ ] **Step 1.3: Visual Test Harness Verification**
  - Run `MangaDetectionIntegrationTest` against authentic manga asset (`assets/sample.jpg`).
  - Verify generated visual artifacts in `build/outputs/test_pipeline/` (`1_original.png`, `2_masked_wiped.png`, `3_typeset_result.png`).

---

### Phase 2: Engine Routing & Free Cloud Integration
- [ ] **Step 2.1: Free Cloud Translation (Groq Cloud)**
  - Integrate Groq Llama 3.3 70B client in `net/` for fast, free-tier conversational translation.
- [ ] **Step 2.2: Free HuggingFace Inpainting Client**
  - Add optional REST client targeting public Hugging Face Spaces running `manga-image-translator` / `lama-cleaner`.
- [ ] **Step 2.3: Workstation API Bridge (`xuande` via Tailscale)**
  - Add optional endpoint config in `SlotStorage` for `http://100.110.101.42:5003` to offload batch inpainting to RTX 5080.
- [ ] **Step 2.4: Settings Engine Selector UI**
  - Add UI toggle in `SettingsScreen.kt` allowing users to select engine mode:
    - `[On-Device Smart]` (Default)
    - `[Cloud Free (Gemini + Groq)]`
    - `[Workstation GPU (xuande)]`

---

### Phase 3: Project Bulcan On-Device ML & Testing
- [ ] **Step 3.1: ONNX Runtime Android Integration**
  - Add `com.microsoft.onnxruntime:onnxruntime-android` dependency.
  - Package/stage lightweight Comic Text Detector (CTD, ~12MB) ONNX model.
  - Extract pixel-accurate polygon masks directly on-device.
- [ ] **Step 3.2: Maestro End-to-End Test Suite**
  - Author `tests/maestro/editor_smoke_flow.yaml`.
  - Validate image load -> detection -> smart wipe -> typeset -> box adjustments -> PNG export.
