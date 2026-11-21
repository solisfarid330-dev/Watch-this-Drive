#!/usr/bin/env python3
"""Build a numbered visual review atlas for unreviewed bare-ground candidates."""
from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
import rasterio
from PIL import Image, ImageDraw, ImageFont
from rasterio.windows import Window
from shapely.geometry import shape
from rasterio.transform import rowcol

ROOT = Path("artifacts")
RASTER = ROOT / "lake_arlington_naip_2022_course_clip.tif"
CANDIDATES = ROOT / "lake_arlington_bare_ground_candidates.geojson"
OUT = ROOT / "candidate_review_atlas"
CARD_W, CARD_H = 360, 280
COLS, ROWS = 3, 4
PER_PAGE = COLS * ROWS


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
                cx, cy = geom.centroid.x, geom.centroid.y
                center_col, center_row = inv * (cx, cy)
                half = 100  # 200 x 200 source pixels, approximately 120 m across
                col0, row0 = int(round(center_col - half)), int(round(center_row - half))
                window = Window(col0, row0, 200, 200)
                rgb = ds.read([1, 2, 3], window=window, boundless=True, fill_value=0)
                rgb = np.moveaxis(rgb, 0, -1).clip(0, 255).astype(np.uint8)
                thumb = Image.fromarray(rgb, mode="RGB").resize((CARD_W - 20, 190), Image.Resampling.LANCZOS)

                local = ImageDraw.Draw(thumb)
                coords = list(geom.exterior.coords) if geom.geom_type == "Polygon" else []
                outline = []
                for x, y in coords:
                    col, row = inv * (x, y)
                    outline.append(((col - col0) * (CARD_W - 20) / 200,
                                    (row - row0) * 190 / 200))
                if len(outline) >= 3:
                    local.line(outline + [outline[0]], fill=(255, 90, 0), width=3)

                x = (slot % COLS) * CARD_W
                y = (slot // COLS) * CARD_H
                page.paste(thumb, (x + 10, y + 10))
                props = feature["properties"]
                label = f'{feature["id"]}  |  {props.get("area_m2", 0):.1f} m2'
                draw_page.text((x + 10, y + 208), label, fill=(255, 255, 255))
                draw_page.text((x + 10, y + 232), "UNREVIEWED — NOT A BUNKER LABEL", fill=(255, 190, 120))
            page_number = page_start // PER_PAGE + 1
            filename = f"candidate_review_page_{page_number:02d}.png"
            page.save(OUT / filename, optimize=True)
            index["pages"].append({
                "page": page_number,
                "file": str(OUT / filename),
                "candidate_ids": [f["id"] for f in page_features],
            })

    (OUT / "index.json").write_text(json.dumps(index, indent=2), encoding="utf-8")
    print(json.dumps(index, indent=2))


if __name__ == "__main__":
    main()
