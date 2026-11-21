#!/usr/bin/env python3
"""Generate unreviewed bare-ground candidates near mapped greens from NAIP bands.

This is a transparent spectral heuristic, not a trained bunker detector.
"""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

import numpy as np
import rasterio
from PIL import Image, ImageDraw
from rasterio.features import rasterize, shapes
from rasterio.transform import rowcol
from rasterio.warp import transform_geom
from rasterio.windows import Window, transform as window_transform
from scipy import ndimage
from shapely.geometry import mapping, shape

ROOT = Path("artifacts")
RASTER = ROOT / "lake_arlington_naip_2022_course_clip.tif"
OSM_GEOJSON = ROOT / "lake_arlington_osm_golf_features.geojson"
MANIFEST = ROOT / "lake_arlington_naip_2022_course_manifest.json"
CANDIDATES_OUT = ROOT / "lake_arlington_bare_ground_candidates.geojson"
OVERLAY_OUT = ROOT / "lake_arlington_bare_ground_candidate_overlay.png"
SUMMARY_OUT = ROOT / "lake_arlington_bare_ground_candidate_summary.json"


def main() -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    osm = json.loads(OSM_GEOJSON.read_text(encoding="utf-8"))

    with rasterio.open(RASTER) as ds:
        bands = ds.read().astype(np.float32)
        transform = ds.transform
        crs = ds.crs
        height, width = ds.height, ds.width
        course_mask = ds.read(1) > 0

    green_buffers = []
    for feature in osm["features"]:
        if feature["properties"].get("golf") == "green":
            geom = transform_geom(
                "EPSG:4326", crs, feature["geometry"], precision=3
            )
            green_buffers.append(shape(geom).buffer(25))
    if not green_buffers:
        raise RuntimeError("No OSM green geometries available for candidate search")

    allowed = rasterize(
        [(mapping(geom), 1) for geom in green_buffers],
        out_shape=(height, width),
        transform=transform,
        fill=0,
        dtype="uint8",
    ).astype(bool)

    red, green, blue, nir = bands[:4]
    ndvi = (nir - red) / (nir + red + 1e-6)
    brightness = (red + green + blue) / 3.0

    # Broad low-vegetation, bright, warm-color test; deliberately high recall.
    candidate_mask = (
        (ndvi < 0.13)
        & (brightness > 120)
        & (red > green * 0.93)
        & (green > blue * 0.90)
        & allowed
        & course_mask
        & (red > 0)
    )
    candidate_mask = ndimage.binary_opening(candidate_mask, iterations=1)
    candidate_mask = ndimage.binary_closing(candidate_mask, iterations=2)

    labels, _ = ndimage.label(candidate_mask)
    objects = ndimage.find_objects(labels)
    features = []
    area_bins = Counter()
    for label_id, slices in enumerate(objects, start=1):
        if slices is None:
            continue
        local = labels[slices] == label_id
        pixel_count = int(local.sum())
        area_m2 = pixel_count * abs(transform.a * transform.e - transform.b * transform.d)
        if not 4.0 <= area_m2 <= 250.0:
            continue
        window = Window(
            slices[1].start,
            slices[0].start,
            slices[1].stop - slices[1].start,
            slices[0].stop - slices[0].start,
        )
        local_transform = window_transform(window, transform)
        for geom_json, value in shapes(
            local.astype("uint8"),
            mask=local,
            transform=local_transform,
        ):
            if value != 1:
                continue
            geom = shape(geom_json)
            if geom.is_empty or geom.area < 4.0:
                continue
            if geom.geom_type != "Polygon":
                continue
            area = float(geom.area)
            if not 4.0 <= area <= 250.0:
                continue
            if area < 20:
                area_bins["4-20 m2"] += 1
            elif area < 60:
                area_bins["20-60 m2"] += 1
            elif area < 120:
                area_bins["60-120 m2"] += 1
            else:
                area_bins["120-250 m2"] += 1
            features.append({
                "type": "Feature",
                "id": f"bare_ground_candidate_{len(features) + 1:03d}",
                "geometry": mapping(geom),
                "properties": {
                    "candidate_class": "bare_ground_candidate",
                    "possible_interpretation": "bunker_or_other_bare_ground",
                    "area_m2": round(area, 1),
                    "source": "NAIP 2022 spectral heuristic",
                    "source_item_ids": manifest["source_item_ids"],
                    "acquisition_date": manifest["acquisition_dates"],
                    "method": "low_ndvi_bright_warm_pixels_within_25m_of_osm_green",
                    "confidence": "low",
                    "validation_status": "unreviewed",
                    "not_ground_truth": True,
                },
            })

    CANDIDATES_OUT.write_text(
        json.dumps({
            "type": "FeatureCollection",
            "name": "Unreviewed bare-ground candidates",
            "crs": crs.to_string(),
            "features": features,
        }, indent=2),
        encoding="utf-8",
    )

    rgb = np.moveaxis(bands[:3], 0, -1).astype(np.uint8)
    image = Image.fromarray(rgb, mode="RGB")
    image.thumbnail((1800, 1800), Image.Resampling.LANCZOS)
    draw = ImageDraw.Draw(image)
    sx = image.width / width
    sy = image.height / height
    for feature in features:
        geom = shape(feature["geometry"])
        points = []
        for x, y in geom.exterior.coords:
            row, col = rowcol(transform, x, y)
            points.append((col * sx, row * sy))
        if len(points) >= 3:
            draw.line(points + [points[0]], fill=(255, 90, 0), width=2)
    draw.rectangle((10, 10, 380, 42), fill=(0, 0, 0))
    draw.text((18, 18), f"{len(features)} unreviewed bare-ground candidates", fill="white")
    image.save(OVERLAY_OUT, optimize=True)

    summary = {
        "course": manifest["course"],
        "candidate_count": len(features),
        "area_bins": dict(area_bins),
        "search_area": "25 m buffers around OSM-mapped greens",
        "thresholds": {
            "ndvi_max_exclusive": 0.13,
            "mean_rgb_brightness_min": 120,
            "red_gt_green_factor": 0.93,
            "green_gt_blue_factor": 0.90,
            "area_range_m2": [4, 250],
        },
        "confidence": "low",
        "validation_status": "unreviewed",
        "warning": "Candidates may include paths, exposed soil, shadows, or other bare ground. Do not label or use them as bunkers without review.",
        "geojson": str(CANDIDATES_OUT),
        "overlay": str(OVERLAY_OUT),
    }
    SUMMARY_OUT.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
