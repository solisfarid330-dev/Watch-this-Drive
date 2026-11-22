#!/usr/bin/env python3
"""Build one centerline-led, imagery-backed Lake Arlington hole review.

Selects the strongest existing OSM hole record, crops the real NAIP image to
that centerline, and overlays only the feature geometries already associated
with that OSM hole. No straight tee-green substitute or inferred fairway is
drawn. This is a visual QA artifact, not a production hole classifier.
"""
from __future__ import annotations

import json
from pathlib import Path

import rasterio
from PIL import Image, ImageDraw, ImageFont
from pyproj import Transformer
from rasterio.windows import from_bounds
from shapely.geometry import shape, mapping
from shapely.ops import transform as transform_geometry

ROOT = Path("artifacts")
RASTER = ROOT / "lake_arlington_naip_2022_course_clip.tif"
DRAFT = ROOT / "lake_arlington_hole_reconstruction_draft.geojson"
OUT_PNG = ROOT / "lake_arlington_centerline_hole_review.png"
OUT_GEOJSON = ROOT / "lake_arlington_centerline_hole_review.geojson"
BUFFER_M = 70.0
WIDTH, HEIGHT = 1500, 1100


def font(size):
    try:
        return ImageFont.truetype("DejaVuSans.ttf", size)
    except OSError:
        return ImageFont.load_default()


def choose_hole(features):
    ranked = []
    for feature in features:
        props = feature.get("properties") or {}
        associated = props.get("associated_features") or []
        by_type = {}
        for item in associated:
            by_type.setdefault(item.get("golf_type"), []).append(item)
        green_count = len(by_type.get("green", []))
        tee_count = len(by_type.get("tee", []))
        fairway_count = len(by_type.get("fairway", []))
        ambiguous = sum(
            item.get("association_status") == "ambiguous_nearest_centerline"
            for item in associated
        )
        # Prefer a single green, at least two tees, a mapped fairway, and no
        # ambiguous associations. Centerline length breaks ties.
        line = shape(feature["geometry"])
        score = (
            100 * (green_count == 1)
            + 40 * (tee_count >= 2)
            + 40 * (fairway_count >= 1)
            - 25 * ambiguous
            + min(line.length * 100000, 60)
        )
        ranked.append((score, feature, by_type, ambiguous))
    if not ranked:
        raise RuntimeError("No OSM hole centerlines were reconstructed")
    ranked.sort(key=lambda item: item[0], reverse=True)
    return ranked[0]


def main():
    draft = json.loads(DRAFT.read_text(encoding="utf-8"))
    score, hole, by_type, ambiguous = choose_hole(draft.get("features", []))
    props = hole["properties"]
    centerline = shape(hole["geometry"])

    with rasterio.open(RASTER) as ds:
        to_raster = Transformer.from_crs("EPSG:4326", ds.crs, always_xy=True)
        to_wgs84 = Transformer.from_crs(ds.crs, "EPSG:4326", always_xy=True)
        line_m = transform_geometry(to_raster.transform, centerline)
        minx, miny, maxx, maxy = line_m.buffer(BUFFER_M).bounds
        bounds = ds.bounds
        minx, miny = max(minx, bounds.left), max(miny, bounds.bottom)
        maxx, maxy = min(maxx, bounds.right), min(maxy, bounds.top)
        window = from_bounds(minx, miny, maxx, maxy, ds.transform).round_offsets().round_lengths()
        window = window.intersection(rasterio.windows.Window(0, 0, ds.width, ds.height))
        rgb = ds.read([1, 2, 3], window=window)
        image = Image.fromarray(rgb.transpose(1, 2, 0)).convert("RGB")
        image.thumbnail((WIDTH, HEIGHT), Image.Resampling.LANCZOS)
        draw = ImageDraw.Draw(image)
        window_transform = ds.window_transform(window)

        def pixel_geometry(geom):
            projected = transform_geometry(to_raster.transform, geom)
            def to_pixel(x, y, z=None):
                col, row = (~window_transform) * (x, y)
                px = col / window.width * image.width
                py = row / window.height * image.height
                return (px, py) if z is None else (px, py, z)
            return transform_geometry(to_pixel, projected)

        def draw_geom(geom, color, width=5):
            g = pixel_geometry(geom)
            parts = list(g.geoms) if hasattr(g, "geoms") else [g]
            for part in parts:
                if part.geom_type in ("LineString", "LinearRing"):
                    pts = list(part.coords)
                    if len(pts) > 1:
                        draw.line(pts, fill=color, width=width)
                elif part.geom_type == "Polygon":
                    pts = list(part.exterior.coords)
                    if len(pts) > 2:
                        draw.line(pts, fill=color, width=width)
                    for ring in part.interiors:
                        pts = list(ring.coords)
                        if len(pts) > 2:
                            draw.line(pts, fill=color, width=max(2, width - 1))
                elif part.geom_type == "Point":
                    x, y = part.x, part.y
                    draw.ellipse((x-9, y-9, x+9, y+9), fill=color, outline="white", width=2)

        # Mapped feature classes only. Fairway boundaries are OSM observations,
        # not image-derived or inferred boundaries.
        styles = {
            "fairway": ((255, 210, 45), 6),
            "green": ((65, 255, 100), 7),
            "tee": ((0, 235, 255), 7),
            "bunker": ((255, 145, 40), 6),
            "water_hazard": ((55, 155, 255), 7),
            "lateral_water_hazard": ((55, 155, 255), 7),
        }
        for kind, items in by_type.items():
            if kind not in styles:
                continue
            color, line_width = styles[kind]
            for item in items:
                if item.get("geometry"):
                    draw_geom(shape(item["geometry"]), color, line_width)
        draw_geom(centerline, (255, 255, 255), 4)

        legend = [
            ("OSM hole centerline", (255, 255, 255)),
            ("Mapped fairway", (255, 210, 45)),
            ("Mapped green", (65, 255, 100)),
            ("Mapped tee", (0, 235, 255)),
            ("Mapped bunker", (255, 145, 40)),
            ("Mapped water", (55, 155, 255)),
        ]
        panel = Image.new("RGBA", (image.width, 145), (0, 0, 0, 185))
        image = image.convert("RGBA")
        image.alpha_composite(panel, (0, image.height - 145))
        draw = ImageDraw.Draw(image)
        title = f"Lake Arlington — OSM Hole {props.get('hole_label', 'unlabeled')}"
        draw.text((22, 18), title, font=font(34), fill="white", stroke_width=2, stroke_fill="black")
        draw.text((22, image.height - 132),
                  "Aerial imagery + mapped OSM geometry | Not a finished hole map",
                  font=font(22), fill="white")
        x, y = 24, image.height - 92
        for label, color in legend:
            draw.line((x, y+10, x+30, y+10), fill=color, width=6)
            draw.text((x+38, y), label, font=font(16), fill="white")
            x += 230
            if x > image.width - 220:
                x, y = 24, y + 30
        image.convert("RGB").save(OUT_PNG, optimize=True)

    output = {
        "type": "FeatureCollection",
        "name": "Lake Arlington centerline-led hole review",
        "course": "Lake Arlington Golf Course",
        "status": "visual_review_required",
        "selected_hole": props.get("hole_label"),
        "source_centerline_id": props.get("source_feature_id"),
        "selection_score": round(score, 2),
        "ambiguous_associations": ambiguous,
        "features": [{
            "type": "Feature",
            "id": hole.get("id"),
            "geometry": hole["geometry"],
            "properties": {
                "role": "osm_hole_centerline",
                "source_feature_id": props.get("source_feature_id"),
                "hole_label": props.get("hole_label"),
            },
        }] + [
            {
                "type": "Feature",
                "id": f"{hole.get('id')}_{item.get('golf_type')}_{index}",
                "geometry": item.get("geometry"),
                "properties": {
                    "role": "mapped_osm_feature",
                    "golf_type": item.get("golf_type"),
                    "source_feature_id": item.get("source_feature_id"),
                    "association_status": item.get("association_status"),
                },
            }
            for kind, items in by_type.items()
            for index, item in enumerate(items, start=1)
            if item.get("geometry")
        ],
        "limitations": [
            "The fairway outline is an existing OSM feature, not extracted from imagery.",
            "Aerial imagery is shown as reference; no image-derived golf geometry is asserted.",
            "The centerline and feature associations remain provisional until visually reviewed.",
        ],
        "review_image": str(OUT_PNG),
    }
    OUT_GEOJSON.write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(json.dumps({
        "selected_hole": output["selected_hole"],
        "source_centerline_id": output["source_centerline_id"],
        "ambiguous_associations": ambiguous,
        "feature_counts": {kind: len(items) for kind, items in by_type.items()},
        "image": str(OUT_PNG),
        "geojson": str(OUT_GEOJSON),
    }, indent=2))


if __name__ == "__main__":
    main()
