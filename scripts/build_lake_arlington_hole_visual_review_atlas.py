#!/usr/bin/env python3
"""Build an imagery-backed visual atlas of plausible Lake Arlington tee/green pairs.

This is a human-review aid, not an automatic hole classifier. Every card shows
real NAIP pixels, the candidate tee and green, and available mapped features.
The straight connector is only a pairing cue; it is not a fairway estimate.
"""
from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
import rasterio
from PIL import Image, ImageDraw, ImageFont
from rasterio.windows import from_bounds
from shapely.geometry import Point, shape, mapping
from shapely.ops import transform as transform_geometry
from pyproj import Transformer

ROOT = Path("artifacts")
RASTER = ROOT / "lake_arlington_naip_2022_course_clip.tif"
MANIFEST = ROOT / "lake_arlington_naip_2022_course_manifest.json"
OSM = ROOT / "lake_arlington_osm_golf_features.geojson"
OUT = ROOT / "lake_arlington_hole_visual_review"
GEOJSON = ROOT / "lake_arlington_tee_green_pair_candidates.geojson"

CARD_W, CARD_H = 760, 560
IMAGE_W, IMAGE_H = 720, 450
COLS, ROWS = 2, 2
PER_PAGE = COLS * ROWS
MIN_LENGTH_M, MAX_LENGTH_M = 60.0, 550.0
TEE_CLUSTER_M = 22.0
MAX_CARDS = 80


def feature_kind(feature):
    props = feature.get("properties") or {}
    return props.get("golf") or props.get("feature_class")


def metric_geometry(geometry, transformer):
    return transform_geometry(transformer.transform, geometry)


def cluster_tees(tees):
    clusters = []
    for feature, geom in sorted(tees, key=lambda item: str(item[0].get("id", ""))):
        point = geom if geom.geom_type == "Point" else geom.representative_point()
        hole_ref = (feature.get("properties") or {}).get("tags", {}).get("ref:hole")
        target = None
        for cluster in clusters:
            if point.distance(cluster["center"]) <= TEE_CLUSTER_M:
                existing = cluster["hole_refs"]
                if hole_ref and existing and hole_ref not in existing:
                    continue
                target = cluster
                break
        if target is None:
            target = {"points": [], "hole_refs": set(), "source_ids": []}
            clusters.append(target)
        target["points"].append(point)
        target["source_ids"].append(str(feature.get("id", "unknown")))
        if hole_ref:
            target["hole_refs"].add(str(hole_ref))
        from shapely.ops import unary_union
        target["center"] = unary_union(target["points"]).centroid
    return clusters


def load_font(size):
    try:
        return ImageFont.truetype("DejaVuSans.ttf", size)
    except OSError:
        return ImageFont.load_default()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    osm = json.loads(OSM.read_text(encoding="utf-8"))
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

    with rasterio.open(RASTER) as ds:
        raster_crs = ds.crs
        raster_bounds = ds.bounds
        raster_transform = ds.transform
        raster_width, raster_height = ds.width, ds.height
        rgb_full = np.moveaxis(ds.read([1, 2, 3]), 0, -1).astype(np.uint8)

        to_raster = Transformer.from_crs("EPSG:4326", raster_crs, always_xy=True)
        to_wgs84 = Transformer.from_crs(raster_crs, "EPSG:4326", always_xy=True)

        features = []
        for item in osm.get("features", []):
            if not item.get("geometry"):
                continue
            geom = metric_geometry(shape(item["geometry"]), to_raster)
            if not geom.is_empty:
                features.append((item, geom))

        tees = [(f, g) for f, g in features if feature_kind(f) == "tee"]
        greens = [(f, g) for f, g in features if feature_kind(f) == "green"]
        overlays = [(f, g) for f, g in features if feature_kind(f) in {
            "fairway", "bunker", "water_hazard", "lateral_water_hazard"
        }]

        tee_clusters = cluster_tees(tees)
        pairs = []
        for tee_index, cluster in enumerate(tee_clusters, start=1):
            tee_point = cluster["center"]
            for green_feature, green_geom in greens:
                green_point = green_geom.representative_point()
                distance = tee_point.distance(green_point)
                if not MIN_LENGTH_M <= distance <= MAX_LENGTH_M:
                    continue
                green_hole = (green_feature.get("properties") or {}).get("tags", {}).get("ref:hole")
                tee_refs = cluster["hole_refs"]
                matching_ref = bool(green_hole and tee_refs and str(green_hole) in tee_refs)
                conflicting_ref = bool(green_hole and tee_refs and str(green_hole) not in tee_refs)
                if conflicting_ref:
                    continue
                pairs.append({
                    "tee_index": tee_index,
                    "tee": tee_point,
                    "green": green_point,
                    "green_geom": green_geom,
                    "distance_m": distance,
                    "matching_hole_ref": matching_ref,
                    "tee_source_ids": cluster["source_ids"],
                    "green_source_id": str(green_feature.get("id", "unknown")),
                    "hole_ref": green_hole or (next(iter(tee_refs)) if tee_refs else None),
                })

        # Preserve alternatives but rank explicit hole-number matches first,
        # then distance. This ordering is for review only, not automatic truth.
        pairs.sort(key=lambda p: (not p["matching_hole_ref"], p["distance_m"]))
        pairs = pairs[:MAX_CARDS]

        font_title = load_font(22)
        font_body = load_font(17)
        font_small = load_font(14)
        index = {
            "course": manifest.get("course"),
            "imagery_vintage": manifest.get("vintage"),
            "acquisition_dates": manifest.get("acquisition_dates"),
            "ground_sample_distance_m_per_pixel": manifest.get("source_gsd_meters_per_pixel"),
            "status": "candidate_pairs_require_human_review",
            "tee_cluster_count": len(tee_clusters),
            "mapped_green_count": len(greens),
            "candidate_pair_count": len(pairs),
            "warning": "These are plausible tee/green pairings, not confirmed hole assignments. Straight connectors are not fairways. OSM coverage is incomplete.",
            "pages": [],
        }
        output_features = []

        for page_start in range(0, len(pairs), PER_PAGE):
            page_pairs = pairs[page_start:page_start + PER_PAGE]
            page = Image.new("RGB", (COLS * CARD_W, ROWS * CARD_H), (20, 27, 24))
            draw_page = ImageDraw.Draw(page)
            for slot, pair in enumerate(page_pairs):
                x0 = (slot % COLS) * CARD_W
                y0 = (slot // COLS) * CARD_H
                tee, green = pair["tee"], pair["green"]
                line = __import__("shapely.geometry", fromlist=["LineString"]).LineString([tee, green])
                crop_bounds = line.buffer(42).bounds
                minx, miny, maxx, maxy = crop_bounds
                minx, miny = max(minx, raster_bounds.left), max(miny, raster_bounds.bottom)
                maxx, maxy = min(maxx, raster_bounds.right), min(maxy, raster_bounds.top)
                if maxx <= minx or maxy <= miny:
                    continue
                window = from_bounds(minx, miny, maxx, maxy, raster_transform)
                window = window.round_offsets().round_lengths()
                window = window.intersection(rasterio.windows.Window(0, 0, raster_width, raster_height))
                if window.width < 2 or window.height < 2:
                    continue
                crop = rgb_full[
                    int(window.row_off):int(window.row_off + window.height),
                    int(window.col_off):int(window.col_off + window.width)
                ]
                image = Image.fromarray(crop, mode="RGB").resize(
                    (IMAGE_W, IMAGE_H), Image.Resampling.LANCZOS
                )
                image_draw = ImageDraw.Draw(image)

                def pixel(point):
                    col, row = (~raster_transform) * (point.x, point.y)
                    return (
                        (col - window.col_off) / window.width * IMAGE_W,
                        (row - window.row_off) / window.height * IMAGE_H,
                    )

                # Draw available OSM features as context, never as exhaustive coverage.
                for feature, geom in overlays:
                    if not geom.intersects(line.buffer(48)):
                        continue
                    color = {
                        "fairway": (40, 220, 120),
                        "bunker": (255, 180, 50),
                        "water_hazard": (50, 150, 255),
                        "lateral_water_hazard": (50, 150, 255),
                    }.get(feature_kind(feature), (255, 255, 255))
                    geoms = list(geom.geoms) if hasattr(geom, "geoms") else [geom]
                    for part in geoms:
                        if part.geom_type == "Polygon":
                            pts = [pixel(Point(c[0], c[1])) for c in part.exterior.coords]
                            if len(pts) > 2:
                                image_draw.line(pts, fill=color, width=3)
                        elif part.geom_type in ("LineString", "LinearRing"):
                            pts = [pixel(Point(c[0], c[1])) for c in part.coords]
                            if len(pts) > 1:
                                image_draw.line(pts, fill=color, width=3)

                tee_px, green_px = pixel(tee), pixel(green)
                image_draw.line([tee_px, green_px], fill=(255, 255, 255), width=2)
                for center, color, label in [
                    (tee_px, (0, 240, 255), "TEE"),
                    (green_px, (80, 255, 80), "GREEN"),
                ]:
                    cx, cy = center
                    image_draw.ellipse((cx-10, cy-10, cx+10, cy+10), fill=color, outline=(0, 0, 0), width=3)
                    image_draw.text((cx+12, cy-12), label, fill=(255,255,255), stroke_width=2, stroke_fill=(0,0,0))

                page.paste(image, (x0 + 20, y0 + 62))
                card_draw = ImageDraw.Draw(page)
                pair_number = page_start + slot + 1
                ref = pair["hole_ref"] or "unlabeled"
                card_draw.text((x0 + 20, y0 + 14), f"PAIR {pair_number:03d}  |  Hole ref: {ref}", font=font_title, fill=(255,255,255))
                card_draw.text((x0 + 20, y0 + 520), f"Tee cluster {pair['tee_index']}  →  Green {pair['green_source_id']}  |  {pair['distance_m']:.0f} m", font=font_body, fill=(230,240,230))
                match_text = "OSM hole refs agree" if pair["matching_hole_ref"] else "Unconfirmed pairing — inspect shape and routing"
                card_draw.text((x0 + 20, y0 + 542), match_text, font=font_small, fill=(130,230,170) if pair["matching_hole_ref"] else (255,205,130))

                tee_lon, tee_lat = to_wgs84.transform(tee.x, tee.y)
                green_lon, green_lat = to_wgs84.transform(green.x, green.y)
                output_features.append({
                    "type": "Feature",
                    "id": f"tee_green_pair_{pair_number:03d}",
                    "geometry": {
                        "type": "LineString",
                        "coordinates": [[tee_lon, tee_lat], [green_lon, green_lat]]
                    },
                    "properties": {
                        "tee_cluster_index": pair["tee_index"],
                        "tee_source_ids": pair["tee_source_ids"],
                        "green_source_id": pair["green_source_id"],
                        "hole_ref": pair["hole_ref"],
                        "matching_hole_ref": pair["matching_hole_ref"],
                        "straight_line_distance_m": round(pair["distance_m"], 1),
                        "status": "unconfirmed_pairing_requires_visual_review",
                        "not_a_fairway_centerline": True,
                    },
                })

            page_number = page_start // PER_PAGE + 1
            filename = f"hole_pair_review_{page_number:02d}.png"
            page.save(OUT / filename, optimize=True)
            index["pages"].append({"page": page_number, "file": str(OUT / filename)})

        GEOJSON.write_text(json.dumps({
            "type": "FeatureCollection",
            "name": "Lake Arlington unconfirmed tee-green pair candidates",
            "crs": "EPSG:4326",
            "features": output_features,
            "status": "human_review_required",
        }, indent=2), encoding="utf-8")
        (OUT / "index.json").write_text(json.dumps(index, indent=2), encoding="utf-8")
        print(json.dumps(index, indent=2))


if __name__ == "__main__":
    main()
