- Gemini 4 Hydrogen (Medium): DONE
COMMIT: 5b6030c (cherry-pick of 8cf2f01 androidTest driver) + ALLPAGES3_RESULT.md commit on fm/ALLPAGES3
PROOF:
- Stub diff (`git -C /home/bchow/rapi-work/bulcan-anitranslate/prod-render diff stub_gemini_server.py`):
```diff
diff --git i/rapi-work/bulcan-anitranslate/prod-render/stub_gemini_server.py w/rapi-work/bulcan-anitranslate/prod-render/stub_gemini_server.py
index 0ff87ef..2e2eff9 100644
--- i/rapi-work/bulcan-anitranslate/prod-render/stub_gemini_server.py
+++ w/rapi-work/bulcan-anitranslate/prod-render/stub_gemini_server.py
@@ -130,6 +130,10 @@ class H(BaseHTTPRequestHandler):
                     src = "agy" if en else "unresolved"
                     if not en: en = "(untranslated)"
                     log({"id": b["id"], "ja": b.get("text"), "how": src, "ratio": round(r, 3), "en": en})
+                elif u is None:
+                    en = "(untranslated)"
+                    src = how
+                    log({"id": b["id"], "ja": b.get("text"), "how": src, "en": en})
                 else:
                     en = u["en"]; src = how
                     log({"id": b["id"], "ja": b.get("text"), "how": src, "ratio": round(r, 3), "gt": u["id"], "en": en})
```
- `python3 -m py_compile /home/bchow/rapi-work/bulcan-anitranslate/prod-render/stub_gemini_server.py` -> `py_compile OK`
- Driver: cherry-picked `8cf2f01` (`test(render-proof): export wiped images, sidecars, and layout_dump.txt`) onto `fm/ALLPAGES3` as `5b6030c`; restarted `anitranslate-stub.service` (listening on `0.0.0.0:18712`) and `anitranslate-emu.service` (`emulator-5554`, AVD `bulcan_api36`).
- Render subset: `adb shell am instrument -w -e class com.example.PageRenderProofInstrumentedTest#renderAll11PagesThroughAppPipeline -e renderPages page-05,page-09 com.aistudio.bubbleforge.mngtr.test/androidx.test.runner.AndroidJUnitRunner` -> `OK (1 test)` in `214.399s` (first attempt hit transient `bg anr` during emulator cold-boot settling; second attempt succeeded cleanly).
- Stub log verification: `page-05` id=4 (`"……"`) and `page-09` id=5 (`"．．．"`), id=6 (`"．．．．．．"`) hit `how="empty"`, `en="(untranslated)"`, then `agy-reword` -> `"..."`; all 8 GT units on `page-05` and all 6 GT units on `page-09` matched `exact`.
- `render_summary.json`: 11/11 pages `DONE` (`123` detected bubbles = `123` typeset units in `layout_dump.txt`, `0` residual Japanese units).
- Row-4 scan (`python3 -c '...'` over `/home/bchow/rapi-work/bulcan-anitranslate/rendered-pages/all-pages-accepted/layout_dump.txt`): `units=123 unhyphenated_splits=0 hyphenated=0`.
- Negative control SHA-256 (`sha256sum` vs `rendered-pages/row4-prod/`):
  - `page-03_translated.jpg`: `15c93faeb762fa9a3fcdd761f2de7835b35805a25cf9a5ef1dbca27e46618d0c` (identical)
  - `page-04_translated.jpg`: `5b96b162306ace8ca16ca6b9138cb07595bd1f880011324c017accd75193e8f5` (identical)
  - `page-06_translated.jpg`: `9e4b2a64f243dc7ba1e11986eb369f423cd6364c6b12b29f6a4c4efc00683382` (identical)
- Artifact counts in `/home/bchow/rapi-work/bulcan-anitranslate/rendered-pages/all-pages-accepted/`: `11` `*_translated.jpg`, `11` `*_wiped.jpg`, `11` `sidecar_900..910.json`, `11` `compare/compare_page-01..11.jpg` (via `uv run --with pillow python3 /home/bchow/rapi-work/bulcan-anitranslate/prod-render/make_sidebyside_allpages.py`), `layout_dump.txt` (123 lines), `render_summary.json` (11 pages), `GATES.md`.
- Unit suite: not re-run (no product or unit-test code modified; citing `efac66e` baseline: `./gradlew :app:testDebugUnitTest` — `220 tests / 0 failures / 3 skipped`).
BLOCKERS: none
CORTEX-DISPOSITION: SKIP: scoped disposable worker run completing an existing bench-stub render set; no durable product or fleet pattern added.
