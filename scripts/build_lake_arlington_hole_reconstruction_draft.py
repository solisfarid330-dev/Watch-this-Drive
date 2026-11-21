#!/usr/bin/env python3
"""Create a conservative, review-required draft of hole geometry from OSM features.

This does not infer missing holes from imagery. It associates existing OSM hole
centerlines with nearby mapped golf features and preserves source provenance.
"""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import shape, mapping
from shapely.ops import transform as transform_geometry

ROOT = Path("artifacts")
INPUT = ROOT / "lake_arlington_osm_golf_features.geojson"
OUTPUT = ROOT / "lake_arlington_hole_reconstruction_draft.geojson"
METERS = Transformer.from_crs("EPSG:4326", "EPSG:26914", always_xy=True).transform
MAX_ASSOCIATION_DISTANCE_M = 110.0


def projected(feature: dict):
    return transform_geometry(METERS, shape(feature["geometry"]))


def number_for(feature: dict, fallback: str) -> str:
    tags = feature["properties"].get("tags") or {}
    for key in ("ref", "name", "hole", "ref:hole"):
        value = tags.get(key)
        if value:
            return str(value)
    return fallback


def main() -> None:
    source = json.loads(INPUT.read_text(encoding="utf-8"))
    features = source.get("features", [])
    hole_features = []
    for feature in features:
        props = feature.get("properties") or {}
        if props.get("golf") != "hole":
            continue
        geom = projected(feature)
        if geom.geom_type in ("LineString", "MultiLineString") and not geom.is_empty:
            hole_features.append((feature, geom))

    if not hole_features:
        raise RuntimeError("No usable OSM golf=hole line geometries were found")

    golf_features = []
    for feature in features:
        props = feature.get("properties") or {}
        golf_type = props.get("golf")
        if golf_type not in {"tee", "fairway", "green", "bunker", "water_hazard", "lateral_water_hazard"}:
            continue
        try:
            geom = projected(feature)
        except Exception:
            continue
        if not geom.is_empty:
            golf_features.append((feature, geom))

    holes = []
    association_counts = Counter()
    for index, (hole_feature, hole_line) in enumerate(hole_features, start=1):
        source_props = hole_feature["properties"]
        hole_label = number_for(hole_feature, f"unlabeled-{index:02d}")
        associations = []
        for feature, geom in golf_features:
            distance = float(hole_line.distance(geom))
            if distance > MAX_ASSOCIATION_DISTANCE_M:
                continue
            props = feature["properties"]
            kind = props.get("golf")
            associations.append({
                "osm_feature_id": feature.get("id"),
                "osm_type": props.get("osm_type"),
                "osm_id": props.get("osm_id"),
                "golf_type": kind,
                "distance_to_hole_line_m": round(distance, 1),
                "geometry": feature["geometry"],
                "tags": props.get("tags") or {},
                "association_status": "provisional_nearest_centerline",
                "source": "OpenStreetMap",
            })
            association_counts[kind] += 1

        holes.append({
            "type": "Feature",
            "id": f"hole_draft_{index:02d}",
            "geometry": hole_feature["geometry"],
            "properties": {
                "hole_label": hole_label,
                "geometry_role": "osm_hole_centerline",
                "source": "OpenStreetMap",
                "source_feature_id": hole_feature.get("id"),
                "source_tags": source_props.get("tags") or {},
                "confidence": "unassessed",
                "validation_status": "requires_visual_review",
                "associated_features": associations,
                "association_limit_m": MAX_ASSOCIATION_DISTANCE_M,
                "warning": "Nearest-centerline association is provisional; adjacent holes may be confused. Do not use for caddy or hazard decisions before review.",
            },
        })

    output = {
        "type": "FeatureCollection",
        "name": "Lake Arlington provisional hole reconstruction draft",
        "course": "Lake Arlington Golf Course",
        "crs": "EPSG:4326",
        "status": "draft_requires_visual_review",
        "method": {
            "description": "Preserve OSM golf=hole centerlines and associate mapped golf features by nearest centerline distance in UTM zone 14N.",
            "association_limit_m": MAX_ASSOCIATION_DISTANCE_M,
            "imagery_used_for_geometry": False,
            "missing_geometry_inferred": False,
        },
        "summary": {
            "hole_centerlines": len(holes),
            "associated_features_by_type": dict(association_counts),
            "holes_without_associations": sum(not h["properties"]["associated_features"] for h in holes),
        },
        "features": holes,
    }
    OUTPUT.write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(json.dumps(output["summary"], indent=2))


if __name__ == "__main__":
    main()
