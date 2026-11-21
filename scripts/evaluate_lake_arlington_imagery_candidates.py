#!/usr/bin/env python3
"""Measure spectral candidate overlap with available OSM golf feature references."""
from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import shape
from shapely.ops import transform as transform_geometry, unary_union

ROOT = Path("artifacts")
CANDIDATES = ROOT / "lake_arlington_imagery_candidates.geojson"
OSM = ROOT / "lake_arlington_osm_golf_features.geojson"
SUMMARY = ROOT / "lake_arlington_imagery_candidate_evaluation.json"
TARGET_CRS = "EPSG:26914"

REFERENCE_CLASSES = (
    "fairway", "green", "tee", "cartpath",
    "water_hazard", "lateral_water_hazard",
)


def _area_geometry(geometry):
    if geometry.is_empty:
        return geometry
    if geometry.area <= 0:
        return geometry.buffer(2.0)
    return geometry


def main():
    candidates = json.loads(CANDIDATES.read_text(encoding="utf-8"))
    osm = json.loads(OSM.read_text(encoding="utf-8"))
    to_metric = Transformer.from_crs(
        "EPSG:4326", TARGET_CRS, always_xy=True
    ).transform

    candidates_by_type = defaultdict(list)
    for feature in candidates.get("features", []):
        kind = feature.get("properties", {}).get("candidate_type")
        geometry = shape(feature["geometry"])
        if not geometry.is_empty:
            candidates_by_type[kind].append(geometry)

    reference_by_type = defaultdict(list)
    for feature in osm.get("features", []):
        kind = feature.get("properties", {}).get("golf")
        if kind not in REFERENCE_CLASSES:
            continue
        geometry = shape(feature["geometry"])
        geometry = transform_geometry(to_metric, geometry)
        geometry = _area_geometry(geometry)
        if not geometry.is_empty:
            reference_by_type[kind].append(geometry)

    candidate_unions = {
        kind: unary_union(geometries)
        for kind, geometries in candidates_by_type.items() if geometries
    }
    reference_unions = {
        kind: unary_union(geometries)
        for kind, geometries in reference_by_type.items() if geometries
    }

    metrics = {}
    for candidate_type, geometries in candidates_by_type.items():
        candidate_union = candidate_unions[candidate_type]
        total_area = float(candidate_union.area)
        overlaps = {}
        for reference_type in REFERENCE_CLASSES:
            reference = reference_unions.get(reference_type)
            if reference is None:
                continue
            overlap_area = float(candidate_union.intersection(reference).area)
            reference_area = float(reference.area)
            overlaps[reference_type] = {
                "overlap_area_m2": round(overlap_area, 1),
                "candidate_area_fraction": round(
                    overlap_area / total_area, 4
                ) if total_area else 0.0,
                "reference_area_coverage": round(
                    overlap_area / reference_area, 4
                ) if reference_area else None,
            }
        metrics[candidate_type] = {
            "candidate_polygon_count": len(geometries),
            "candidate_union_area_m2": round(total_area, 1),
            "overlap_with_osm_reference_classes": overlaps,
        }

    # Reference-side coverage is useful for spotting spectral omissions.
    all_candidate_geometries = [
        geometry for geometries in candidates_by_type.values() for geometry in geometries
    ]
    all_candidate_union = unary_union(all_candidate_geometries) if all_candidate_geometries else None
    reference_coverage = {}
    for reference_type, reference in reference_unions.items():
        if all_candidate_union is None:
            coverage = 0.0
        else:
            coverage = float(reference.intersection(all_candidate_union).area / reference.area)
        reference_coverage[reference_type] = {
            "reference_area_m2": round(float(reference.area), 1),
            "covered_by_all_candidate_classes_fraction": round(coverage, 4),
            "note": "Coverage is a rough comparison to incomplete OSM reference geometry, not accuracy against ground truth.",
        }

    result = {
        "course": "Lake Arlington Golf Course",
        "imagery_vintage": candidates.get("imagery_vintage"),
        "candidate_crs": candidates.get("coordinate_reference_system"),
        "reference_source": "OpenStreetMap golf-tagged features",
        "candidate_metrics": metrics,
        "osm_reference_coverage": reference_coverage,
        "limitations": [
            "OSM golf features are incomplete and are not ground truth.",
            "Overlap is a spatial screening metric, not precision/recall against verified labels.",
            "Vegetation can represent trees, rough, fairways, greens, or other plants.",
            "Bare ground can represent paths, soil, sand, roofs, or bunkers.",
            "A candidate may overlap multiple nearby mapped feature types.",
        ],
        "status": "exploratory_spatial_comparison_not_model_validation",
    }
    SUMMARY.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
