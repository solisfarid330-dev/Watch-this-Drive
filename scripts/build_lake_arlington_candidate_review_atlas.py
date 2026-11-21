#!/usr/bin/env python3
"""Build a numbered, close-up visual review atlas for bare-ground candidates."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import rasterio
from PIL import Image, ImageDraw
from rasterio.windows import Window
from shapely.geometry import shape

ROOT = Path("artifacts")
RASTER = ROOT / "lake_arlington_naip_2022_course_clip.tif"
CANDIDATES = ROOT / "lake_arlington_bare_ground_candidates.geojson"
OUT = ROOT / "candidate_review_atlas"
CARD_W, CARD_H = 540, 340
COLS, ROWS = 2, 3
PER_PAGE = COLS * ROWS
CROP_SIZE = 160
IMAGE_SIZE = 260


def main() -> None:
    data = json.loads(CANDIDATES.read_text(encoding="utf-8"))
    features = sorted(data["features"], key=lambda f: f["id"])
    OUT.mkdir(parents=True, exist_ok=True)
    index = {
        "course": "Lake Arlington Golf Course",
        "status": "unreviewed",
        "candidate_count": len(features),
        "pages": [],
        "review_warning": "Bare-ground candidates are not confirmed bunkers. Inspect the source image and classify or reject manually.",
    }

    with rasterio.open(RASTER) as ds:
        inv = ~ds.transform
        for page_start in range(0, len(features), PER_PAGE):
            page_features = features[page_start:page_start + PER_PAGE]
            page = Image.new("RGB", (COLS * CARD_W, ROWS * CARD_H), (24, 27, 31))
            draw_page = ImageDraw.Draw(page)
            for slot, feature in enumerate(page_features):
                geom = shape(feature["geometry"])
                center_col, center_row = inv * (geom.centroid.x, geom.centroid.y)
                # Shift edge windows inward instead of introducing black no-data wedges.
                col0 = max(0, min(ds.width - CROP_SIZE, int(round(center_col - CROP_SIZE / 2))))
                row0 = max(0, min(ds.height - CROP_SIZE, int(round(center_row - CROP_SIZE / 2))))
                window = Window(col0, row0, CROP_SIZE, CROP_SIZE)
                rgb = ds.read([1, 2, 3], window=window)
                rgb = np.moveaxis(rgb, 0, -1).clip(0, 255).astype(np.uint8)
                thumb = Image.fromarray(rgb, mode="RGB").resize(
                    (IMAGE_SIZE, IMAGE_SIZE), Image.Resampling.LANCZOS
                )
                local = ImageDraw.Draw(thumb)
                outline = []
                if geom.geom_type == "Polygon":
                    for x, y in geom.exterior.coords:
                        col, row = inv * (x, y)
                        outline.append((
                            (col - col0) * IMAGE_SIZE / CROP_SIZE,
                            (row - row0) * IMAGE_SIZE / CROP_SIZE,
                        ))
                if len(outline) >= 3:
                    local.line(outline + [outline[0]], fill=(255, 90, 0), width=3)

                x = (slot % COLS) * CARD_W
                y = (slot // COLS) * CARD_H
                page.paste(thumb, (x + 14, y + 12))
                props = feature["properties"]
                label = f'{feature["id"]}  |  {props.get("area_m2", 0):.1f} m2'
                draw_page.text((x + 292, y + 40), label, fill=(255, 255, 255))
                draw_page.text((x + 292, y + 78), "UNREVIEWED", fill=(255, 190, 120))
                draw_page.text((x + 292, y + 104), "Not a confirmed bunker", fill=(210, 210, 210))
                draw_page.text((x + 292, y + 142), "Orange outline = candidate", fill=(210, 210, 210))

            page_number = page_start // PER_PAGE + 1
            filename = f"candidate_review_page_{page_number:02d}.png"
            page.save(OUT / filename, optimize=True)
            index["pages"].append({
                "page": page_number,
                "file": str(OUT / filename),
                "candidate_ids": [f["id"] for f in page_features],
            })

    (OUT / "index.json").write_text(json.dumps(index, indent=2), encoding="utf-8")
    print(json.dumps({
        "candidate_count": len(features),
        "page_count": len(index["pages"]),
        "pages": index["pages"],
    }, indent=2))


if __name__ == "__main__":
    main()
