#!/usr/bin/env python3
"""Run generic multispectral candidate extraction on the Lake Arlington NAIP pilot."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import rasterio
from PIL import Image, ImageDraw
from rasterio.features import geometry_mask
from shapely.geometry import shape
from scripts.course_intelligence.imagery_candidates import extract_imagery_candidates

ROOT = Path("artifacts")
RASTER = ROOT / "lake_arlington_naip_2022_course_clip.tif"
MANIFEST = ROOT / "lake_arlington_naip_2022_course_manifest.json"
GEOJSON = ROOT / "lake_arlington_imagery_candidates.geojson"
OVERLAY = ROOT / "lake_arlington_imagery_candidates_overlay.png"
SUMMARY = ROOT / "lake_arlington_imagery_candidates_summary.json"


def main():
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    with rasterio.open(RASTER) as ds:
        if ds.count < 4:
            raise RuntimeError(f"Expected RGB+NIR raster, found {ds.count} bands")
        bands = np.moveaxis(ds.read([1, 2, 3, 4]), 0, -1)
        transform = ds.transform
        crs = ds.crs
        valid_mask = ds.read(1) > 0
        rgb = np.moveaxis(bands[:, :, :3], -1, 0)
        height, width = ds.height, ds.width

    collection, summary = extract_imagery_candidates(
        bands,
        transform,
        course_mask=valid_mask,
        min_area_m2=20.0,
        max_area_m2=100000.0,
        turf_ndvi_min=0.35,
        bare_ndvi_max=0.18,
        min_brightness=55.0,
        morphology_iterations=1,
    )
    collection["name"] = "Lake Arlington unreviewed multispectral candidates"
    collection["coordinate_reference_system"] = crs.to_string()
    collection["imagery_vintage"] = manifest.get("vintage")
    collection["acquisition_dates"] = manifest.get("acquisition_dates")
    collection["source_item_ids"] = manifest.get("source_item_ids")
    collection["features"] = [
        {
            **feature,
            "properties": {
                **feature["properties"],
                "imagery_vintage": manifest.get("vintage"),
                "acquisition_dates": manifest.get("acquisition_dates"),
                "source_item_ids": manifest.get("source_item_ids"),
                "course": manifest.get("course"),
                "coordinate_reference_system": crs.to_string(),
            },
        }
        for feature in collection["features"]
    ]
    GEOJSON.write_text(json.dumps(collection, indent=2), encoding="utf-8")

    image = Image.fromarray(np.moveaxis(rgb, 0, -1).astype(np.uint8), mode="RGB")
    image.thumbnail((2000, 2000), Image.Resampling.LANCZOS)
    draw = ImageDraw.Draw(image)
    sx, sy = image.width / width, image.height / height
    counts = {"vegetated_turf_candidate": 0, "bare_ground_candidate": 0}
    for feature in collection["features"]:
        props = feature["properties"]
        kind = props["candidate_type"]
        counts[kind] = counts.get(kind, 0) + 1
        geom = shape(feature["geometry"])
        color = (0, 220, 80) if kind == "vegetated_turf_candidate" else (255, 120, 0)
        geoms = list(geom.geoms) if geom.geom_type == "MultiPolygon" else [geom]
        for polygon in geoms:
            points = []
            for x, y in polygon.exterior.coords:
                row, col = rasterio.transform.rowcol(transform, x, y)
                points.append((col * sx, row * sy))
            if len(points) >= 3:
                draw.line(points + [points[0]], fill=color, width=2)

    draw.rectangle((8, 8, 670, 55), fill=(0, 0, 0))
    draw.text((16, 16), f"Green: {counts.get('vegetated_turf_candidate', 0)} vegetation candidates", fill=(0, 255, 100))
    draw.text((16, 34), f"Orange: {counts.get('bare_ground_candidate', 0)} bare-ground candidates", fill=(255, 160, 50))
    image.save(OVERLAY, optimize=True)

    summary.update({
        "course": manifest.get("course"),
        "imagery_vintage": manifest.get("vintage"),
        "acquisition_dates": manifest.get("acquisition_dates"),
        "source_item_ids": manifest.get("source_item_ids"),
        "crs": crs.to_string(),
        "raster_dimensions_pixels": [width, height],
        "course_mask_valid_pixel_count": int(valid_mask.sum()),
        "candidate_count_by_type": counts,
        "outputs": {
            "geojson": str(GEOJSON),
            "overlay": str(OVERLAY),
        },
        "interpretation": (
            "This is a real-image pilot of generic spectral candidates, not "
            "semantic golf-feature detection. Visual review is required."
        ),
    })
    SUMMARY.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
