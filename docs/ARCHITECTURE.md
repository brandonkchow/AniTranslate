# AniTranslate Architecture: 3-Tier Multi-Engine Manga Scanlation

## 1. System Vision & Problem Statement

Standard multimodal LLMs (e.g., Gemini, GPT-4o) struggle with manga scanlation when treated as a single-step "box + flat wipe + text" pipeline.
- **Problem A (The White Sticker):** Filling bounding boxes with solid shapes obliterates hand-drawn bubble borders, halftones, speedlines, and screentones.
- **Problem B (Geometry Mismatch):** Japanese dialogue runs vertically (*Tategaki*) in tall ovals; English dialogue runs horizontally (*Yokogaki*). Naive rectangular layouts either clip curved edges or shrink fonts into unreadable micro-text.
- **Problem C (Lack of Contrast):** Without dual-pass stroke halos (white outline + black fill), text rendered over art or tones becomes illegible.

AniTranslate solves this with a **3-Tier Multi-Engine Architecture**, providing graceful degradation from local on-device algorithms to free cloud APIs and workstation GPU acceleration.

---

## 2. The 3-Tier Multi-Engine Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        AniTranslate Engine Router                      │
└──────────────────┬─────────────────┬─────────────────┬─────────────────┘
                   │                 │                 │
         [Tier 1: On-Device]  [Tier 2: Cloud Free] [Tier 3: Workstation]
         Local Kotlin / ONNX   HuggingFace / Groq  RTX 5080 / Tailscale
         ───────────────────   ──────────────────  ────────────────────
         • 100% Offline        • $0 Serverless API • Beast-Mode Speed
         • Smart Ink-Erase     • Free HF Spaces    • 80ms per page
         • Elliptical Layout   • Gemini / Groq     • Full LaMa + CTD
```

### Tier 1: 100% Contained On-Device (Project Bulcan Standard)
- **Zero Cost & Zero Network Latency**: Fully operational offline on mobile.
- **Smart Ink-Erase**:
  1. Crop bubble/text bounding box.
  2. Compute Otsu / adaptive binarization to isolate the dark Japanese text glyphs (`InkMask`).
  3. Morphologically dilate the mask by 2–3px to capture anti-aliasing edges and furigana.
  4. Inpaint *only* the dilated ink pixels using local neighborhood sampling or Telea fast inpainting. Bubble borders and screentones remain 100% untouched.
- **Convex Elliptical Typesetter**:
  - Dynamically calculates line width constraints using the ellipse equation:
    $$W(y) = W \cdot \sqrt{1 - \left(\frac{2y - H}{H}\right)^2} - 2 \cdot \text{margin}$$
  - Balances words into diamond/convex shapes (short $\to$ long $\to$ short).
  - Dual-pass rendering: Pass 1 draws a 3px white outline stroke (`Paint.Style.STROKE`), Pass 2 draws solid black fill (`Paint.Style.FILL`).
- **ONNX Runtime Android**:
  - Pre-built `com.microsoft.onnxruntime:onnxruntime-android` executes lightweight Comic Text Detector (CTD, ~12MB) on-device with CPU/NNAPI fallback.

### Tier 2: Free Cloud Serverless APIs ($0 Cost)
- **Translation**: Groq Cloud API (Llama 3.3 70B Versatile) for ultra-fast, natural comic dialogue translation with 0 marginal cost.
- **Vision / Detection**: Google Gemini 2.5 Flash free tier (BYOK slot with 15 RPM).
- **Inpainting Option**: Free Hugging Face Spaces running `manga-image-translator` / `lama-cleaner` via public REST/Gradio endpoints.

### Tier 3: Private Workstation Beast Mode (RTX 5080 via Tailscale)
- **Workstation Node**: `xuande` (`100.110.101.42`) running an NVIDIA RTX 5080.
- **Backend Service**: `manga-image-translator` running via Docker:
  ```bash
  docker run -d --name manga-translator -p 5003:5003 --gpus all \
    zyddnys/manga-image-translator:main \
    server/main.py --start-instance --host=0.0.0.0 --port=5003 --use-gpu
  ```
- **Performance**: High-resolution LaMa inpainting and CTD polygon detection in < 120ms per page.
- **Connectivity**: Private WireGuard mesh via Tailscale with encrypted direct peer connection.

---

## 3. Project Bulcan Android Engineering Laws

1. **Pre-Built Binaries First**: Never compile native C++ engines via CMake in the repo. Use official Maven dependencies (`onnxruntime-android`).
2. **Model Decoupling**: Large model weights (> 10MB) must never be checked into git. Models are downloaded on-demand or staged into `context.getExternalFilesDir(null)`.
3. **Graceful Degradation**: If Tier 3 (Workstation) is unreachable, fall back to Tier 2 (Cloud) or Tier 1 (Local Ink-Erase).
4. **Deterministic UI Testing**: All editor manipulations (drag handles, text edits, font size sliders) must be verifiable with Maestro flows.
