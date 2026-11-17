#!/usr/bin/env python3
"""Standing pixel gate for AniTranslate wipe containment and residue.

Requires only Python 3, Pillow (PIL), and NumPy.

Usage:
    python3 tools/pixel_gate.py <render_dir> <clean_dir>
"""

import argparse
import glob
import json
import os
import re
import sys
from collections import deque

import numpy as np
from PIL import Image


NON_BALLOON_TYPES = {"side_text"}


def find_clean_image(clean_dir: str, page_stem: str) -> str | None:
    for ext in ("jpg", "jpeg", "png", "webp"):
        candidate = os.path.join(clean_dir, f"{page_stem}.{ext}")
        if os.path.isfile(candidate):
            return candidate
    return None


def pair_pages_and_sidecars(render_dir: str):
    wiped_files = sorted(glob.glob(os.path.join(render_dir, "page-*_wiped.*")))
    sidecar_files = glob.glob(os.path.join(render_dir, "sidecar_*.json"))

    def sidecar_key(path: str) -> int:
        m = re.search(r"sidecar_(\d+)\.json$", os.path.basename(path))
        return int(m.group(1)) if m else 0

    sidecar_files.sort(key=sidecar_key)

    pairs = []
    if len(wiped_files) == len(sidecar_files) and len(wiped_files) > 0:
        for wiped_path, sidecar_path in zip(wiped_files, sidecar_files):
            base = os.path.basename(wiped_path)
            stem = re.sub(r"_wiped\.[^.]+$", "", base)
            pairs.append((stem, wiped_path, sidecar_path))
    else:
        for wiped_path in wiped_files:
            base = os.path.basename(wiped_path)
            stem = re.sub(r"_wiped\.[^.]+$", "", base)
            m = re.search(r"page-(\d+)", stem)
            sidecar_path = None
            if m:
                idx = int(m.group(1))
                for cand in (
                    os.path.join(render_dir, f"sidecar_{900 + idx - 1}.json"),
                    os.path.join(render_dir, f"sidecar_{idx}.json"),
                    os.path.join(render_dir, f"sidecar_{stem}.json"),
                ):
                    if os.path.isfile(cand):
                        sidecar_path = cand
                        break
            if sidecar_path:
                pairs.append((stem, wiped_path, sidecar_path))
    return pairs


def connected_clusters(mask: np.ndarray, min_size: int = 80):
    """Find 8-connected components of `mask` with pixel count >= `min_size`."""
    h, w = mask.shape
    ys, xs = np.nonzero(mask)
    if len(ys) == 0:
        return []

    visited = np.zeros_like(mask, dtype=bool)
    clusters = []

    for start_y, start_x in zip(ys.tolist(), xs.tolist()):
        if visited[start_y, start_x]:
            continue
        visited[start_y, start_x] = True
        q = deque([(start_y, start_x)])
        count = 0
        min_x = max_x = start_x
        min_y = max_y = start_y

        while q:
            cy, cx = q.popleft()
            count += 1
            if cx < min_x:
                min_x = cx
            if cx > max_x:
                max_x = cx
            if cy < min_y:
                min_y = cy
            if cy > max_y:
                max_y = cy

            y0 = max(0, cy - 1)
            y1 = min(h - 1, cy + 1)
            x0 = max(0, cx - 1)
            x1 = min(w - 1, cx + 1)
            for ny in range(y0, y1 + 1):
                for nx in range(x0, x1 + 1):
                    if mask[ny, nx] and not visited[ny, nx]:
                        visited[ny, nx] = True
                        q.append((ny, nx))

        if count >= min_size:
            clusters.append({
                "pixels": count,
                "x1": min_x / w,
                "y1": min_y / h,
                "x2": (max_x + 1) / w,
                "y2": (max_y + 1) / h,
            })

    clusters.sort(key=lambda c: c["pixels"], reverse=True)
    return clusters


def analyze_page(stem: str, wiped_path: str, clean_path: str, sidecar_path: str):
    wiped_img = Image.open(wiped_path).convert("L")
    clean_img = Image.open(clean_path).convert("L")
    if wiped_img.size != clean_img.size:
        clean_img = clean_img.resize(wiped_img.size, Image.BILINEAR)

    w, h = wiped_img.size
    wiped_arr = np.asarray(wiped_img, dtype=np.int16)
    clean_arr = np.asarray(clean_img, dtype=np.int16)

    with open(sidecar_path, "r", encoding="utf-8") as f:
        units = json.load(f)

    wiped_units = [
        u for u in units
        if u.get("visible", True) and u.get("type", "bubble").lower() not in NON_BALLOON_TYPES
    ]

    allowed = np.zeros((h, w), dtype=bool)
    for u in wiped_units:
        x1, y1, x2, y2 = u["box"]
        px1 = max(0, min(w, int(x1 * w)))
        py1 = max(0, min(h, int(y1 * h)))
        px2 = max(0, min(w, int(x2 * w)))
        py2 = max(0, min(h, int(y2 * h)))
        if px2 > px1 and py2 > py1:
            allowed[py1:py2, px1:px2] = True

    diff = np.abs(clean_arr - wiped_arr) > 60
    outside_diff = diff & (~allowed)
    outside_changed_px = int(np.count_nonzero(outside_diff))
    clusters = connected_clusters(outside_diff, min_size=80)

    residue_rows = []
    for u in wiped_units:
        x1, y1, x2, y2 = u["box"]
        bw = x2 - x1
        bh = y2 - y1
        ix1 = max(0, min(w, int((x1 + 0.15 * bw) * w)))
        iy1 = max(0, min(h, int((y1 + 0.15 * bh) * h)))
        ix2 = max(0, min(w, int((x2 - 0.15 * bw) * w)))
        iy2 = max(0, min(h, int((y2 - 0.15 * bh) * h)))

        if ix2 <= ix1 or iy2 <= iy1:
            clean_dark = wiped_dark = clean_faint = wiped_faint = 0
        else:
            c_crop = clean_arr[iy1:iy2, ix1:ix2]
            w_crop = wiped_arr[iy1:iy2, ix1:ix2]
            clean_dark = int(np.count_nonzero(c_crop < 110))
            wiped_dark = int(np.count_nonzero(w_crop < 110))
            clean_faint = int(np.count_nonzero(c_crop < 180))
            wiped_faint = int(np.count_nonzero(w_crop < 180))

        dark_ratio = (wiped_dark / clean_dark) if clean_dark > 0 else 0.0
        faint_ratio = (wiped_faint / clean_faint) if clean_faint > 0 else 0.0
        residue_rows.append({
            "id": u.get("id"),
            "type": u.get("type", "bubble"),
            "clean_dark": clean_dark,
            "wiped_dark": wiped_dark,
            "dark_ratio": dark_ratio,
            "clean_faint": clean_faint,
            "wiped_faint": wiped_faint,
            "faint_ratio": faint_ratio,
        })

    return {
        "page": stem,
        "width": w,
        "height": h,
        "outside_changed_px": outside_changed_px,
        "clusters": clusters,
        "residue": residue_rows,
    }


def main():
    parser = argparse.ArgumentParser(description="AniTranslate wipe pixel gate")
    parser.add_argument("render_dir", help="Directory containing page-NN_wiped.jpg and sidecar_*.json")
    parser.add_argument("clean_dir", help="Directory containing clean page-NN.jpg|webp assets")
    parser.add_argument("--page", help="Optional page stem filter, e.g. page-01")
    parser.add_argument("--json", action="store_true", help="Output JSON instead of human-readable report")
    args = parser.parse_args()

    pairs = pair_pages_and_sidecars(args.render_dir)
    if args.page:
        pairs = [p for p in pairs if p[0] == args.page]

    if not pairs:
        print(f"No page/sidecar pairs found in {args.render_dir}", file=sys.stderr)
        sys.exit(1)

    results = []
    for stem, wiped_path, sidecar_path in pairs:
        clean_path = find_clean_image(args.clean_dir, stem)
        if not clean_path:
            print(f"Missing clean asset for {stem} in {args.clean_dir}", file=sys.stderr)
            sys.exit(1)
        results.append(analyze_page(stem, wiped_path, clean_path, sidecar_path))

    if args.json:
        print(json.dumps(results, indent=2))
        return

    for r in results:
        print(f"=== {r['page']} ({r['width']}x{r['height']}) ===")
        print(f"  outside_changed_px (lum diff > 60): {r['outside_changed_px']}")
        if r["clusters"]:
            for idx, c in enumerate(r["clusters"], 1):
                print(
                    f"    cluster {idx}: {c['pixels']} px @ "
                    f"x={c['x1']:.3f}-{c['x2']:.3f}, y={c['y1']:.3f}-{c['y2']:.3f}"
                )
        else:
            print("    clusters (>=80 px): none")
        print("  residue (15% inset):")
        for row in r["residue"]:
            print(
                f"    id={row['id']} ({row['type']}): "
                f"dark(<110) clean={row['clean_dark']} wiped={row['wiped_dark']} ratio={row['dark_ratio']:.3f} | "
                f"faint(<180) clean={row['clean_faint']} wiped={row['wiped_faint']} ratio={row['faint_ratio']:.3f}"
            )


if __name__ == "__main__":
    main()
