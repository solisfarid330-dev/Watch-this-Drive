#!/usr/bin/env python3
"""Compare OSM golf-tagged features with the georeferenced NAIP course crop."""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

import requests
import rasterio
from PIL import Image, ImageDraw
from rasterio.transform import rowcol, Affine
from rasterio.warp import transform
from shapely.geometry import shape, mapping

ROOT = Path("artifacts")
CATALOG = ROOT / "lake_arlington_naip_candidates.json"
CROP_MANIFEST = ROOT / "lake_arlington_naip_2022_course_manifest.json"
PREVIEW = ROOT / "lake_arlington_naip_2022_course_preview.png"
RAW_OUT = ROOT / "lake_arlington_osm_golf_features.json"
GEOJSON_OUT = ROOT / "lake_arlington_osm_golf_features.geojson"
OVERLAY_OUT = ROOT / "lake_arlington_naip_2022_osm_overlay.png"
OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
HEADERS = {
    "User-Agent": "WatchThisDrive-course-imagery-research/1.0 (OSM feature comparison)"
}
COLORS = {
    "green": "#00FF55",
    "tee": "#00FFFF",
    "fairway": "#00BFFF",
    "bunker": "#FF9C27",
    "water_hazard": "#168BFF",
    "hole": "#FF42D0",
    "rough": "#F5D742",
}


def fetch_osm_features(polygon: dict) -> dict:
    ring = polygon["coordinates"][0]
    poly = " ".join(f"{lat:.7f} {lon:.7f}" for lon, lat in ring)
    query = f"""
[out:json][timeout:120];
(
  node["golf"](poly:"{poly}");
  way["golf"](poly:"{poly}");
  relation["golf"](poly:"{poly}");
);
out body geom;
"""
    last_error = None
    for endpoint in OVERPASS_ENDPOINTS:
        try:
            response = requests.post(
                endpoint, data={"data": query}, headers=HEADERS, timeout=180
            )
            response.raise_for_status()
            return response.json()
        except Exception as exc:
            last_error = exc
    raise RuntimeError(f"All Overpass endpoints failed: {last_error}")


def element_geometry(element: dict):
    kind = element.get("type")
    if kind == "node" and "lon" in element and "lat" in element:
        return {"type": "Point", "coordinates": [element["lon"], element["lat"]]}
    if kind == "way":
        coords = [[p["lon"], p["lat"]] for p in element.get("geometry", []) if "lon" in p and "lat" in p]
        if len(coords) < 2:
            return None
        if len(coords) >= 4 and coords[0] == coords[-1]:
            return {"type": "Polygon", "coordinates": [coords]}
        return {"type": "LineString", "coordinates": coords}
    if kind == "relation":
        parts = []
        for member in element.get("members", []):
            coords = [[p["lon"], p["lat"]] for p in member.get("geometry", []) if "lon" in p and "lat" in p]
            if len(coords) >= 2:
                if len(coords) >= 4 and coords[0] == coords[-1]:
                    parts.append({"type": "Polygon", "coordinates": [coords]})
                else:
                    parts.append({"type": "LineString", "coordinates": coords})
        if parts:
            return {"type": "GeometryCollection", "geometries": parts}
    return None


def main() -> None:
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    manifest = json.loads(CROP_MANIFEST.read_text(encoding="utf-8"))
    polygon = data["boundary_geojson"]
    osm = fetch_osm_features(polygon)
    RAW_OUT.write_text(json.dumps(osm, indent=2), encoding="utf-8")

    features = []
    counts = Counter()
    for element in osm.get("elements", []):
        tags = element.get("tags", {})
        golf_type = tags.get("golf", "unknown")
        counts[golf_type] += 1
        geometry = element_geometry(element)
        if geometry:
            features.append({
                "type": "Feature",
                "id": f"{element.get('type')}/{element.get('id')}",
                "geometry": geometry,
                "properties": {
                    "osm_type": element.get("type"),
                    "osm_id": element.get("id"),
                    "golf": golf_type,
                    "name": tags.get("name"),
                    "tags": tags,
                },
            })
    feature_collection = {
        "type": "FeatureCollection",
        "name": "Lake Arlington OSM golf-tagged features",
        "source": "OpenStreetMap contributors",
        "features": features,
    }
    GEOJSON_OUT.write_text(json.dumps(feature_collection, indent=2), encoding="utf-8")

    image = Image.open(PREVIEW).convert("RGB")
    draw = ImageDraw.Draw(image)
    output_width, output_height = manifest["output_dimensions_pixels"]
    scale_x = image.width / output_width
    scale_y = image.height / output_height
    affine = Affine(*manifest["output_transform"])
    target_crs = manifest["output_crs"]

    def project_point(point):
        xs, ys = transform("EPSG:4326", target_crs, [point[0]], [point[1]])
        row, col = rowcol(affine, xs[0], ys[0])
        return (col * scale_x, row * scale_y)

    def draw_geometry(geometry, color, width=3):
        kind = geometry.get("type")
        if kind == "Point":
            x, y = project_point(geometry["coordinates"])
            draw.ellipse((x - 5, y - 5, x + 5, y + 5), outline=color, width=3)
        elif kind == "LineString":
            points = [project_point(p) for p in geometry["coordinates"]]
            if len(points) >= 2:
                draw.line(points, fill=color, width=width)
        elif kind == "Polygon":
            for ring in geometry["coordinates"]:
                points = [project_point(p) for p in ring]
                if len(points) >= 2:
                    draw.line(points + [points[0]], fill=color, width=width)
        elif kind == "GeometryCollection":
            for part in geometry.get("geometries", []):
                draw_geometry(part, color, width)

    for feature in features:
        golf_type = feature["properties"]["golf"]
        color = COLORS.get(golf_type, "#FFFFFF")
        draw_geometry(feature["geometry"], color)

    # Add a compact legend without obscuring the course.
    legend = [
        ("green", COLORS["green"]),
        ("tee", COLORS["tee"]),
        ("fairway", COLORS["fairway"]),
        ("bunker", COLORS["bunker"]),
        ("water_hazard", COLORS["water_hazard"]),
        ("hole", COLORS["hole"]),
    ]
    y = 12
    for label, color in legend:
        draw.rectangle((12, y, 28, y + 12), fill=color)
        draw.text((36, y - 2), label, fill="white", stroke_width=2, stroke_fill="black")
        y += 22
    image.save(OVERLAY_OUT, optimize=True)

    summary = {
        "course": data["course"],
        "boundary_source": data["boundary_source"],
        "overpass_elements_total": len(osm.get("elements", [])),
        "geojson_features_written": len(features),
        "counts_by_golf_tag": dict(counts),
        "raw_osm_json": str(RAW_OUT),
        "geojson": str(GEOJSON_OUT),
        "imagery_overlay_preview": str(OVERLAY_OUT),
        "note": "OSM feature presence is not proof of completeness. Compare overlays against imagery; do not treat absent OSM features as absent on the ground.",
    }
    (ROOT / "lake_arlington_osm_imagery_comparison.json").write_text(
        json.dumps(summary, indent=2), encoding="utf-8"
    )
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"OSM imagery comparison failed: {exc}", file=sys.stderr)
        raise
