#!/usr/bin/env python3
"""Seed-independent fairway candidates from NAIP RGBIR imagery.

Fairway/rough polygons are deliberately excluded from model selection. OSM
feature masks are consulted only after prediction to report weak-label overlap.
This is an exploratory candidate generator, not a certified boundary model.
"""
from __future__ import annotations
import argparse
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy import ndimage
from skimage.measure import find_contours, label
from skimage.segmentation import slic
from shapely.geometry import Polygon, mapping
from shapely.geometry.polygon import orient
from shapely.ops import unary_union

WEB_MERCATOR_R = 6378137.0
FEATURE_TAGS = ("fairway", "rough", "green", "bunker", "water_hazard", "tee")


def mercator(lon, lat):
    lat = max(-85.05112878, min(85.05112878, lat))
    return (
        WEB_MERCATOR_R * math.radians(lon),
        WEB_MERCATOR_R * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2)),
    )


def feature_rings(geometry):
    if geometry["type"] == "Polygon":
        yield geometry["coordinates"]
    elif geometry["type"] == "MultiPolygon":
        yield from geometry["coordinates"]


def local_std(gray, radius):
    padded = np.pad(gray, ((radius, radius), (radius, radius)), mode="reflect")
    squared = padded * padded
    summed = np.pad(padded, ((1, 0), (1, 0))).cumsum(0).cumsum(1)
    summed_sq = np.pad(squared, ((1, 0), (1, 0))).cumsum(0).cumsum(1)
    width = 2 * radius + 1
    total = (
        summed[width:, width:] - summed[:-width, width:]
        - summed[width:, :-width] + summed[:-width, :-width]
    )
    total_sq = (
        summed_sq[width:, width:] - summed_sq[:-width, width:]
        - summed_sq[width:, :-width] + summed_sq[:-width, :-width]
    )
    mean = total / (width * width)
    return np.sqrt(np.maximum(total_sq / (width * width) - mean * mean, 0.0))


def robust_z(values):
    values = np.asarray(values, dtype=np.float64)
    median = np.median(values)
    mad = np.median(np.abs(values - median))
    scale = max(1.4826 * mad, float(np.std(values)) * 0.25, 1e-6)
    return (values - median) / scale


def rank_clusters(ndvi, excess_green, texture, centerline_distance_m):
    """Rank image clusters without accepting any fairway-label input."""
    ndvi_z = robust_z(ndvi)
    green_z = robust_z(excess_green)
    texture_z = robust_z(texture)
    distance_z = robust_z(centerline_distance_m)
    score = 0.30 * ndvi_z + 0.30 * green_z - 0.25 * texture_z - 0.15 * distance_z
    return score


def rasterize_feature_masks(collection, pixel, width, height):
    masks = {}
    for tag in FEATURE_TAGS:
        image = Image.new("1", (width, height), 0)
        draw = ImageDraw.Draw(image)
        for feature in collection["features"]:
            if feature.get("properties", {}).get("golf") != tag:
                continue
            for polygon in feature_rings(feature["geometry"]):
                if not polygon:
                    continue
                draw.polygon([pixel(point) for point in polygon[0]], fill=1)
                for inner in polygon[1:]:
                    draw.polygon([pixel(point) for point in inner], fill=0)
        masks[tag] = np.asarray(image, dtype=bool)
    return masks


def rasterize_hole_line(line, pixel, width, height, metres_per_pixel):
    spine = Image.new("1", (width, height), 0)
    draw = ImageDraw.Draw(spine)
    draw.line([pixel(point) for point in line], fill=1, width=max(1, round(3.0 / metres_per_pixel)))
    spine_mask = np.asarray(spine, dtype=bool)
    corridor = Image.new("1", (width, height), 0)
    draw = ImageDraw.Draw(corridor)
    draw.line([pixel(point) for point in line], fill=1, width=max(1, round(2 * 40.0 / metres_per_pixel)))
    return spine_mask, np.asarray(corridor, dtype=bool)


def vectorize_mask(mask, bbox, width, height, minimum_area_m2=20.0):
    minx, miny, maxx, maxy = bbox
    rings = []
    for contour in find_contours(mask.astype(np.uint8), 0.5, fully_connected="high"):
        if len(contour) < 5 or np.linalg.norm(contour[0] - contour[-1]) > 1.5:
            continue
        coordinates = []
        for row, col in contour:
            x = minx + (col / max(width - 1, 1)) * (maxx - minx)
            y = maxy - (row / max(height - 1, 1)) * (maxy - miny)
            lon = math.degrees(x / WEB_MERCATOR_R)
            lat = math.degrees(2 * math.atan(math.exp(y / WEB_MERCATOR_R)) - math.pi / 2)
            coordinates.append((lon, lat))
        if coordinates[0] != coordinates[-1]:
            coordinates.append(coordinates[0])
        polygon = Polygon(coordinates)
        if polygon.is_valid and polygon.area > 0:
            rings.append(polygon)

    rings.sort(key=lambda polygon: polygon.area, reverse=True)
    parents = [None] * len(rings)
    depths = [0] * len(rings)
    for index, ring in enumerate(rings):
        containers = [
            parent for parent, candidate in enumerate(rings[:index])
            if candidate.contains(ring.representative_point())
        ]
        if containers:
            parents[index] = min(containers, key=lambda parent: rings[parent].area)
            depths[index] = depths[parents[index]] + 1

    polygons = []
    for index, outer in enumerate(rings):
        if depths[index] % 2:
            continue
        holes = [
            list(rings[child].exterior.coords)
            for child in range(len(rings))
            if parents[child] == index and depths[child] == depths[index] + 1
        ]
        polygon = Polygon(list(outer.exterior.coords), holes)
        if polygon.is_valid and polygon.area > 0:
            polygons.append(orient(polygon, sign=1.0))
    if not polygons:
        return None
    merged = unary_union(polygons)
    if not merged.is_valid:
        merged = merged.buffer(0)
    if merged.is_empty:
        return None
    return mapping(merged.simplify(0.000002, preserve_topology=True))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--geojson", required=True)
    parser.add_argument("--metadata", required=True)
    parser.add_argument("--tiff", required=True)
    parser.add_argument("--hole-ref", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--segments", type=int, default=2600)
    parser.add_argument("--corridor-m", type=float, default=40.0)
    parser.add_argument("--centerline-link-m", type=float, default=18.0)
    args = parser.parse_args()

    collection = json.loads(Path(args.geojson).read_text())
    metadata = json.loads(Path(args.metadata).read_text())
    raw = np.asarray(Image.open(args.tiff)).astype(np.float32)
    if raw.ndim != 3 or raw.shape[2] != 4:
        raise SystemExit("Expected a four-band RGBIR TIFF")
    height, width = raw.shape[:2]
    if [width, height] != metadata["export_size_pixels"]:
        raise SystemExit("TIFF dimensions do not match aerial metadata")

    bbox = metadata["export_bbox_web_mercator_m"]
    minx, miny, maxx, maxy = bbox
    hole = next((
        feature for feature in collection["features"]
        if feature.get("properties", {}).get("golf") == "hole"
        and str(feature["properties"].get("ref") or feature["properties"].get("golf:hole") or "") == args.hole_ref
    ), None)
    if hole is None:
        raise SystemExit(f"Explicitly numbered Hole {args.hole_ref} not found")
    line = hole["geometry"]["coordinates"]
    if hole["geometry"]["type"] != "LineString" or len(line) < 2:
        raise SystemExit("Selected hole does not have a usable LineString")

    latitude = sum(point[1] for point in line) / len(line)
    metres_per_pixel = ((maxx - minx) * math.cos(math.radians(latitude))) / width
    if metres_per_pixel <= 0:
        raise SystemExit("Invalid image georeferencing scale")

    def pixel(point):
        x, y = mercator(*point)
        return (
            (x - minx) / (maxx - minx) * (width - 1),
            (maxy - y) / (maxy - miny) * (height - 1),
        )

    spine, corridor = rasterize_hole_line(line, pixel, width, height, metres_per_pixel)
    corridor = corridor & (ndimage.distance_transform_edt(~spine) * metres_per_pixel <= args.corridor_m)
    masks = rasterize_feature_masks(collection, pixel, width, height)
    exclusions = masks["green"] | masks["bunker"] | masks["water_hazard"] | masks["tee"]

    red, green, blue, nir = [raw[:, :, index] for index in range(4)]
    valid = (red + green + nir) > 0
    ndvi = (nir - red) / np.maximum(nir + red, 1.0)
    excess_green = (2.0 * green - red - blue) / 255.0
    gray = (0.299 * red + 0.587 * green + 0.114 * blue) / 255.0
    texture_small = local_std(gray, 3)
    texture_large = local_std(gray, 9)
    texture = 0.55 * texture_small + 0.45 * texture_large

    analysis = corridor & valid & ~exclusions
    if int(analysis.sum()) < 1000:
        raise SystemExit("Insufficient valid pixels inside the hole corridor")

    rgbir = np.stack([red, green, blue, nir], axis=-1) / 255.0
    segments = slic(
        rgbir, n_segments=args.segments, compactness=7.0, sigma=1.0,
        start_label=0, channel_axis=-1, convert2lab=False,
    )
    count = int(segments.max()) + 1
    flat = segments.ravel()
    pixel_count = np.bincount(flat, minlength=count).astype(np.float64)
    analysis_weights = analysis.ravel().astype(np.float64)
    analysis_pixel_count = np.bincount(
        flat, weights=analysis_weights, minlength=count
    )

    def average_by_segment(values):
        return np.bincount(
            flat,
            weights=values.ravel().astype(np.float64) * analysis_weights,
            minlength=count,
        ) / np.maximum(analysis_pixel_count, 1.0)

    centerline_distance = ndimage.distance_transform_edt(~spine) * metres_per_pixel
    feature_matrix = {
        "ndvi": average_by_segment(ndvi),
        "excess_green": average_by_segment(excess_green),
        "texture": average_by_segment(texture),
        "centerline_distance_m": average_by_segment(centerline_distance),
    }
    # Score every valid corridor pixel rather than selecting one SLIC label.
    # A fairway can span many superpixels; single-cluster selection was too brittle.
    def corridor_robust_z(values):
        sample = values[analysis]
        median = float(np.median(sample))
        mad = float(np.median(np.abs(sample - median)))
        scale = max(1.4826 * mad, float(np.std(sample)) * 0.25, 1e-6)
        return (values - median) / scale

    pixel_score = (
        0.32 * corridor_robust_z(ndvi)
        + 0.28 * corridor_robust_z(excess_green)
        - 0.20 * corridor_robust_z(texture)
        - 0.20 * corridor_robust_z(centerline_distance)
    )
    pixel_score[~analysis] = -10.0
    smoothed_score = ndimage.gaussian_filter(pixel_score, sigma=2.0)
    threshold = float(np.percentile(smoothed_score[analysis], 58.0))
    raw_candidate = (smoothed_score >= threshold) & analysis

    # Join mowing/lighting fragments, then keep only substantial components
    # that approach the centreline. Never expand outside valid corridor pixels.
    structure = np.ones((5, 5), dtype=bool)
    closed = ndimage.binary_closing(raw_candidate, structure=structure)
    closed &= analysis
    component_labels, component_count = ndimage.label(closed)
    retained = np.zeros_like(closed)
    component_reports = []
    link_distance = ndimage.distance_transform_edt(~spine) * metres_per_pixel
    for component_id in range(1, component_count + 1):
        component = component_labels == component_id
        area_m2 = float(component.sum()) * metres_per_pixel ** 2
        near_line = bool(np.any(component & (link_distance <= args.centerline_link_m)))
        if area_m2 >= 35.0 and near_line:
            retained |= component
            component_reports.append({"component": component_id, "area_m2": round(area_m2, 1)})
    candidate = ndimage.binary_opening(retained, structure=np.ones((3, 3)))
    candidate &= analysis

    # SLIC cluster ranking is retained as a diagnostic, not a selection rule.
    scores = rank_clusters(
        feature_matrix["ndvi"],
        feature_matrix["excess_green"],
        feature_matrix["texture"],
        feature_matrix["centerline_distance_m"],
    )
    analysis_fraction = analysis_pixel_count / np.maximum(pixel_count, 1.0)
    candidate_segment_scores = np.where(analysis_fraction >= 0.35, scores, -np.inf)
    selected_cluster = int(np.argmax(candidate_segment_scores))

    # Weak-label metrics are computed only after selection. They do not affect the chosen cluster.
    fairway_seed = masks["fairway"] & analysis
    rough_seed = masks["rough"] & analysis
    candidate_fraction = lambda mask: round(float(candidate[mask].mean()), 4) if mask.any() else None
    fairway_recall = candidate_fraction(fairway_seed)
    rough_false_positive_rate = candidate_fraction(rough_seed)
    line_samples = [pixel(point) for point in line]
    line_coverage = []
    distance_to_candidate = ndimage.distance_transform_edt(~candidate) * metres_per_pixel
    for x, y in line_samples:
        col = min(width - 1, max(0, round(x)))
        row = min(height - 1, max(0, round(y)))
        line_coverage.append(float(distance_to_candidate[row, col] <= args.centerline_link_m))

    # Diagnostic threshold sweep: report the precision/coverage trade-off
    # against incomplete OSM labels without using those labels to choose a threshold.
    threshold_sweep = []
    for percentile in (50, 58, 65, 70, 75, 80, 85):
        cut = float(np.percentile(smoothed_score[analysis], percentile))
        mask = (smoothed_score >= cut) & analysis
        fairway_overlap = float(mask[fairway_seed].mean()) if fairway_seed.any() else None
        rough_overlap = float(mask[rough_seed].mean()) if rough_seed.any() else None
        threshold_sweep.append({
            "score_percentile": percentile,
            "threshold": round(cut, 5),
            "candidate_area_m2": round(float(mask.sum()) * metres_per_pixel ** 2, 1),
            "corridor_fraction": round(float(mask.sum() / max(int(corridor.sum()), 1)), 4),
            "mapped_fairway_overlap": round(fairway_overlap, 4) if fairway_overlap is not None else None,
            "mapped_rough_overlap": round(rough_overlap, 4) if rough_overlap is not None else None,
        })

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    base = np.stack([red, green, blue], axis=-1).clip(0, 255).astype(np.uint8)
    overlay = base.copy()
    overlay[candidate] = (0.40 * base[candidate] + 0.60 * np.array([30, 230, 80])).clip(0, 255).astype(np.uint8)
    Image.fromarray(overlay, "RGB").save(out / "seedless-fairway-candidate.jpg", quality=92)
    cluster_preview = np.zeros((height, width, 3), dtype=np.uint8)
    palette = np.array([
        [230, 80, 80], [80, 180, 240], [230, 210, 60],
        [180, 100, 220], [60, 210, 150], [230, 150, 70],
    ], dtype=np.uint8)
    for cluster_id in range(count):
        cluster_preview[(segments == cluster_id) & analysis] = palette[cluster_id % len(palette)]
    Image.fromarray(cluster_preview, "RGB").save(out / "seedless-clusters.jpg", quality=88)

    geometry = vectorize_mask(candidate, bbox, width, height)
    if geometry:
        feature = {
            "type": "Feature",
            "properties": {
                "golf": "fairway_candidate",
                "hole_ref": args.hole_ref,
                "source_osm_hole_id": hole["properties"].get("osm_id"),
                "method": "Seed-independent RGBIR SLIC ranking + centerline-linked components",
                "status": "unverified_candidate",
                "fairway_polygons_used_for_selection": False,
                "rough_polygons_used_for_selection": False,
                "imagery": "Texas NAIP 2022",
            },
            "geometry": geometry,
        }
        output_geojson = {
            "type": "FeatureCollection",
            "features": [feature],
            "metadata": {
                "coordinate_reference_system": "EPSG:4326",
                "status": "candidate_only_not_ground_truth",
                "warning": "Seed-independent candidate; requires independent reference-boundary validation.",
                "osm_attribution": "© OpenStreetMap contributors",
                "imagery_attribution": "Texas Geographic Information Office, USDA",
            },
        }
        (out / "seedless-fairway-candidate.geojson").write_text(json.dumps(output_geojson, indent=2) + "\n")

    report = {
        "course_hole": args.hole_ref,
        "hole_osm_id": hole["properties"].get("osm_id"),
        "method": "Pixel-wise robust RGBIR vegetation/texture score, Gaussian smoothing, corridor-local threshold, and connected-component filtering near the hole centreline.",
        "selection_independence": {
            "fairway_polygons_used_for_selection": False,
            "rough_polygons_used_for_selection": False,
            "mapped_green_bunker_water_tee_used_only_as_exclusions": True,
            "selection_features": ["NDVI", "excess_green", "multi_scale_texture", "centerline_distance", "per_pixel_corridor_score"],
        },
        "image": {"width": width, "height": height, "metres_per_pixel": round(metres_per_pixel, 3)},
        "analysis_pixels": int(analysis.sum()),
        "superpixel_count": count,
        "selected_cluster": selected_cluster,
        "selected_cluster_score_diagnostic_only": round(float(scores[selected_cluster]), 4),
        "pixel_score_threshold_percentile": 58.0,
        "pixel_score_threshold": round(threshold, 5),
        "threshold_sweep_diagnostics": threshold_sweep,
        "cluster_feature_means": {
            key: round(float(values[selected_cluster]), 5)
            for key, values in feature_matrix.items()
        },
        "candidate_pixels": int(candidate.sum()),
        "candidate_selection": "per-pixel score threshold; not a single superpixel",
        "candidate_area_m2": round(float(candidate.sum()) * metres_per_pixel ** 2, 1),
        "candidate_fraction_of_corridor": round(float(candidate.sum() / max(int(corridor.sum()), 1)), 4),
        "centerline_sample_coverage_within_link_distance": round(float(np.mean(line_coverage)), 4),
        "retained_components": component_reports,
        "weak_label_evaluation_only": {
            "mapped_fairway_seed_pixels": int(fairway_seed.sum()),
            "candidate_overlap_with_mapped_fairway_seed": fairway_recall,
            "mapped_rough_seed_pixels": int(rough_seed.sum()),
            "candidate_overlap_with_mapped_rough_seed": rough_false_positive_rate,
            "warning": "These are incomplete OSM agreement diagnostics, not independent accuracy estimates. They did not influence candidate selection.",
        },
        "status": ("unverified_candidate_high_rough_overlap" if candidate.any() and rough_false_positive_rate is not None and rough_false_positive_rate > 0.25 else ("unverified_candidate" if candidate.any() else "no_candidate_found")),
        "files": ["seedless-fairway-candidate.jpg", "seedless-clusters.jpg", "seedless-fairway-candidate.geojson", "seedless-report.json"],
    }
    (out / "seedless-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
