#!/usr/bin/env python3
"""Create a conservative, review-required hole grouping draft from OSM features.

Each mapped golf feature is assigned to its nearest mapped hole centerline only.
Close runner-up lines are retained as ambiguity metadata for human review.
This does not infer missing features or create final playable geometry.
"""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import shape
from shapely.ops import transform as transform_geometry

ROOT = Path("artifacts")
INPUT = ROOT / "lake_arlington_osm_golf_features.geojson"
OUTPUT = ROOT / "lake_arlington_hole_reconstruction_draft.geojson"
METERS = Transformer.from_crs("EPSG:4326", "EPSG:26914", always_xy=True).transform
MAX_ASSOCIATION_DISTANCE_M = 110.0
AMBIGUITY_MARGIN_M = 25.0


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

    holes = []
    for index, (hole_feature, hole_line) in enumerate(hole_features, start=1):
        source_props = hole_feature["properties"]
        holes.append({
            "type": "Feature",
            "id": f"hole_draft_{index:02d}",
            "geometry": hole_feature["geometry"],
            "properties": {
                "hole_label": number_for(hole_feature, f"unlabeled-{index:02d}"),
                "geometry_role": "osm_hole_centerline",
                "source": "OpenStreetMap",
                "source_feature_id": hole_feature.get("id"),
                "source_tags": source_props.get("tags") or {},
                "confidence": "unassessed",
                "validation_status": "requires_visual_review",
                "associated_features": [],
                "association_limit_m": MAX_ASSOCIATION_DISTANCE_M,
                "warning": "Provisional nearest-centerline grouping only. Verify against imagery; do not use for caddy or hazard decisions.",
            },
        })

    association_counts = Counter()
    ambiguous_count = 0
    unassigned_count = 0
    excluded_types = {"hole"}
    for feature in features:
        props = feature.get("properties") or {}
        golf_type = props.get("golf")
        if golf_type not in {"tee", "fairway", "green", "bunker", "water_hazard", "lateral_water_hazard"}:
            continue
        geom = projected(feature)
        if geom.is_empty:
            continue
        ranked = sorted(
            ((float(line.distance(geom)), idx) for idx, (_, line) in enumerate(hole_features)),
            key=lambda item: item[0],
        )
        if not ranked or ranked[0][0] > MAX_ASSOCIATION_DISTANCE_M:
            unassigned_count += 1
            continue

        nearest_distance, nearest_index = ranked[0]
        second_distance = ranked[1][0] if len(ranked) > 1 else None
        ambiguous = second_distance is not None and (second_distance - nearest_distance) <= AMBIGUITY_MARGIN_M
        if ambiguous:
            ambiguous_count += 1

        association = {
            "osm_feature_id": feature.get("id"),
            "osm_type": props.get("osm_type"),
            "osm_id": props.get("osm_id"),
            "golf_type": golf_type,
            "distance_to_hole_line_m": round(nearest_distance, 1),
            "geometry": feature["geometry"],
            "tags": props.get("tags") or {},
            "association_status": "ambiguous_nearest_centerline" if ambiguous else "provisional_nearest_centerline",
            "alternative_hole_draft_ids": [
                f"hole_draft_{idx + 1:02d}"
                for distance, idx in ranked[1:]
                if distance <= MAX_ASSOCIATION_DISTANCE_M
                and (distance - nearest_distance) <= AMBIGUITY_MARGIN_M
            ],
            "second_nearest_distance_m": round(second_distance, 1) if second_distance is not None else None,
            "source": "OpenStreetMap",
        }
        holes[nearest_index]["properties"]["associated_features"].append(association)
        association_counts[golf_type] += 1

    for hole in holes:
        hole["properties"]["associated_feature_count"] = len(hole["properties"]["associated_features"])

    output = {
        "type": "FeatureCollection",
        "name": "Lake Arlington provisional hole reconstruction draft",
        "course": "Lake Arlington Golf Course",
        "crs": "EPSG:4326",
        "status": "draft_requires_visual_review",
        "method": {
            "description": "Assign each mapped golf feature to its nearest OSM golf=hole centerline; retain close runner-up centerlines as ambiguity metadata.",
            "association_limit_m": MAX_ASSOCIATION_DISTANCE_M,
            "ambiguity_margin_m": AMBIGUITY_MARGIN_M,
            "imagery_used_for_geometry": False,
            "missing_geometry_inferred": False,
        },
        "summary": {
            "hole_centerlines": len(holes),
            "associated_features_by_type": dict(association_counts),
            "ambiguous_associations": ambiguous_count,
            "unassigned_features": unassigned_count,
            "holes_without_associations": sum(not h["properties"]["associated_features"] for h in holes),
        },
        "features": holes,
    }
    OUTPUT.write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(json.dumps(output["summary"], indent=2))


if __name__ == "__main__":
    main()
