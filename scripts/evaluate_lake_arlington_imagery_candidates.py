#!/usr/bin/env python3
"""Measure spectral candidate overlap and image signals against OSM references."""
from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path

import numpy as np
import rasterio
from pyproj import Transformer
from rasterio.features import geometry_mask
from shapely.geometry import mapping, shape
from shapely.ops import transform as transform_geometry, unary_union

ROOT = Path("artifacts")
CANDIDATES = ROOT / "lake_arlington_imagery_candidates.geojson"
OSM = ROOT / "lake_arlington_osm_golf_features.geojson"
RASTER = ROOT / "lake_arlington_naip_2022_course_clip.tif"
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
        geometry = transform_geometry(to_metric, shape(feature["geometry"]))
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

    all_candidate_geometries = [
        geometry for geometries in candidates_by_type.values() for geometry in geometries
    ]
    all_candidate_union = (
        unary_union(all_candidate_geometries) if all_candidate_geometries else None
    )
    reference_coverage = {}
    for reference_type, reference in reference_unions.items():
        coverage = (
            float(reference.intersection(all_candidate_union).area / reference.area)
            if all_candidate_union is not None and reference.area else 0.0
        )
        reference_coverage[reference_type] = {
            "reference_area_m2": round(float(reference.area), 1),
            "covered_by_all_candidate_classes_fraction": round(coverage, 4),
            "note": "Rough comparison to incomplete OSM reference geometry, not ground-truth accuracy.",
        }

    # Inspect the actual NAIP spectral distributions inside each mapped feature.
    spectral_reference_stats = {}
    with rasterio.open(RASTER) as raster:
        red, green, blue, nir = raster.read([1, 2, 3, 4]).astype(np.float32)
        denominator = nir + red
        ndvi = np.divide(
            nir - red,
            denominator,
            out=np.zeros_like(red),
            where=np.abs(denominator) > 1e-6,
        )
        brightness = (red + green + blue) / 3.0
        valid = raster.read(1) > 0
        for reference_type, reference in reference_unions.items():
            mask = geometry_mask(
                [mapping(reference)],
                out_shape=(raster.height, raster.width),
                transform=raster.transform,
                invert=True,
            ) & valid
            values = ndvi[mask]
            brightness_values = brightness[mask]
            if values.size == 0:
                continue
            spectral_reference_stats[reference_type] = {
                "pixel_count": int(values.size),
                "ndvi_percentiles": {
                    str(p): round(float(np.percentile(values, p)), 4)
                    for p in [10, 25, 50, 75, 90]
                },
                "fraction_ndvi_at_least_0_35": round(float(np.mean(values >= 0.35)), 4),
                "fraction_ndvi_at_most_0_18": round(float(np.mean(values <= 0.18)), 4),
                "brightness_percentiles": {
                    str(p): round(float(np.percentile(brightness_values, p)), 1)
                    for p in [10, 50, 90]
                },
            }

    result = {
        "course": "Lake Arlington Golf Course",
        "imagery_vintage": candidates.get("imagery_vintage"),
        "candidate_crs": candidates.get("coordinate_reference_system"),
        "reference_source": "OpenStreetMap golf-tagged features",
        "candidate_metrics": metrics,
        "osm_reference_coverage": reference_coverage,
        "spectral_signals_inside_osm_reference_geometries": spectral_reference_stats,
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
