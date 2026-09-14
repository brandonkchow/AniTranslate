# AniTranslate 📖⚡

> Modern, AI-powered manga scanlation & translation studio for Android. Built with Jetpack Compose, Kotlin Coroutines, and a 3-Tier Multi-Engine Architecture.

---

## 🌟 Features

- **3-Tier Multi-Engine Processing**:
  - **Tier 1 (On-Device)**: Smart localized text-ink wiping (preserving screentones and speech bubble borders) and convex elliptical comic typesetting.
  - **Tier 2 (Cloud Free)**: BYOK slots for Google Gemini 2.5 Flash, Groq Cloud (Llama 3.3 70B), and Hugging Face serverless APIs.
  - **Tier 3 (Workstation Accelerator)**: Offload heavy inpainting and segmentation to a private desktop GPU (`manga-image-translator` via Tailscale).
- **Interactive Box Editor**: Drag-and-drop bounding box adjustment, manual Japanese OCR correction, English text editing, and dynamic font sizing.
- **Manga Reading Order Sorting**: Automatically sequences speech bubbles Right-to-Left (RTL) and Top-to-Bottom (TTB) across comic panels.
- **Batch Export**: Export translated pages directly to device storage as pristine high-resolution PNGs or ZIP archives.

---

## 🏗️ Architecture & Documentation

- [Architecture Specification](docs/ARCHITECTURE.md) — 3-Tier engine model, Project Bulcan laws, and inpainting algorithms.
- [Implementation Roadmap](docs/ROADMAP.md) — Phase-by-phase development milestones.
- [Engineering & Flywheel Guidelines](AGENTS.md) — Continuous iteration flywheel, test harness, and visual benchmarking.

---

## 🛠️ Building & Testing

### Prerequisites
- JDK 17 (Temurin recommended)
- Android SDK 34 / 35 (Android 14 / 15)
- Gradle 8.5+ / 9.x via `./gradlew`

### Run Verification Tests
```bash
./gradlew :app:testDebugUnitTest --tests "com.example.MangaDetectionIntegrationTest"
```

Visual test artifacts and metrics will be generated in `app/build/outputs/test_pipeline/`.

### Build Debug APK
```bash
./gradlew :app:assembleDebug
```
The output APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
