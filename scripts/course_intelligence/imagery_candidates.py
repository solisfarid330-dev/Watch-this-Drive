"""Course-agnostic spectral candidate extraction from licensed multispectral imagery.

This module emits broad spectral/shape candidates, not semantic golf labels.
It deliberately does not call a region a tee, green, fairway, bunker, or hazard.
"""
from __future__ import annotations

from collections import Counter
from math import hypot

import numpy as np
from rasterio.features import shapes
from shapely.geometry import shape, mapping
from scipy import ndimage


def extract_imagery_candidates(
    bands,
    transform,
    *,
    course_mask=None,
    min_area_m2=20.0,
    max_area_m2=100000.0,
    turf_ndvi_min=0.35,
    bare_ndvi_max=0.18,
    min_brightness=55.0,
    morphology_iterations=1,
):
    """Extract generic vegetated-turf and bare-ground polygons from RGB+NIR.

    Args:
        bands: HxWx4 array in Red, Green, Blue, NIR order (8-bit or scaled).
        transform: rasterio affine transform mapping pixel coordinates to CRS.
        course_mask: optional HxW boolean mask limiting extraction to course.
        min/max_area_m2: component area bounds in the transform's linear units.
        turf_ndvi_min: minimum NDVI for vegetated turf candidates.
        bare_ndvi_max: maximum NDVI for bright bare-ground candidates.
        min_brightness: minimum mean RGB brightness for bare-ground candidates.

    The thresholds are generic starting points, not trained classifier
    parameters. All polygons are unreviewed candidates with low confidence.
    """
    array = np.asarray(bands, dtype=np.float32)
    if array.ndim != 3 or array.shape[2] < 4:
        raise ValueError("Expected an HxWx4+ array ordered Red, Green, Blue, NIR")
    if min_area_m2 <= 0 or max_area_m2 < min_area_m2:
        raise ValueError("Invalid component area range")
    height, width, _ = array.shape
    if course_mask is None:
        valid = np.ones((height, width), dtype=bool)
    else:
        valid = np.asarray(course_mask, dtype=bool)
        if valid.shape != (height, width):
            raise ValueError("course_mask dimensions must match imagery")

    red, green, blue, nir = (array[:, :, i] for i in range(4))
    denominator = nir + red
    ndvi = np.divide(
        nir - red, denominator,
        out=np.zeros_like(red),
        where=np.abs(denominator) > 1e-6,
    )
    brightness = (red + green + blue) / 3.0
    nonzero = (red > 0) & (green > 0) & (blue > 0) & (nir > 0)

    masks = {
        "vegetated_turf_candidate": valid & nonzero & (ndvi >= turf_ndvi_min),
        "bare_ground_candidate": (
            valid & nonzero & (ndvi <= bare_ndvi_max) & (brightness >= min_brightness)
        ),
    }
    pixel_area = abs(transform.a * transform.e - transform.b * transform.d)
    if pixel_area <= 0:
        raise ValueError("Raster transform must have a positive pixel area")

    features = []
    counts = Counter()
    for candidate_type, raw_mask in masks.items():
        cleaned = ndimage.binary_opening(raw_mask, iterations=morphology_iterations)
        cleaned = ndimage.binary_closing(cleaned, iterations=morphology_iterations)
        labels, count = ndimage.label(cleaned)
        counts[candidate_type] = 0
        for label_id in range(1, count + 1):
            component = labels == label_id
            area = float(component.sum() * pixel_area)
            if area < min_area_m2 or area > max_area_m2:
                continue
            for geometry, value in shapes(
                component.astype(np.uint8),
                mask=component,
                transform=transform,
            ):
                if value != 1:
                    continue
                polygon = shape(geometry)
                if polygon.is_empty or polygon.area < min_area_m2:
                    continue
                counts[candidate_type] += 1
                perimeter = float(polygon.length)
                hull_area = float(polygon.convex_hull.area)
                rotated = polygon.minimum_rotated_rectangle
                rectangle_coords = list(rotated.exterior.coords) if not rotated.is_empty else []
                edge_lengths = [
                    hypot(
                        rectangle_coords[i + 1][0] - rectangle_coords[i][0],
                        rectangle_coords[i + 1][1] - rectangle_coords[i][1],
                    )
                    for i in range(len(rectangle_coords) - 1)
                ]
                edge_lengths = sorted((edge for edge in edge_lengths if edge > 0), reverse=True)
                elongation = edge_lengths[0] / edge_lengths[-1] if len(edge_lengths) >= 2 else None
                component_ndvi = ndvi[component]
                component_brightness = brightness[component]
                component_rgb = array[component][:, :3]
                features.append({
                    "type": "Feature",
                    "id": f"imagery_candidate_{len(features) + 1:05d}",
                    "geometry": mapping(polygon),
                    "properties": {
                        "candidate_type": candidate_type,
                        "area_m2": round(float(polygon.area), 2),
                        "shape_metrics": {
                            "perimeter_m": round(perimeter, 2),
                            "compactness": round(
                                float(4.0 * np.pi * polygon.area / (perimeter ** 2)), 4
                            ) if perimeter > 0 else None,
                            "solidity": round(
                                float(polygon.area / hull_area), 4
                            ) if hull_area > 0 else None,
                            "oriented_bbox_elongation": round(elongation, 3) if elongation is not None else None,
                        },
                        "spectral_metrics": {
                            "ndvi_mean": round(float(component_ndvi.mean()), 4),
                            "ndvi_stddev": round(float(component_ndvi.std()), 4),
                            "brightness_mean": round(float(component_brightness.mean()), 2),
                            "brightness_stddev": round(float(component_brightness.std()), 2),
                            "red_mean": round(float(component_rgb[:, 0].mean()), 2),
                            "green_mean": round(float(component_rgb[:, 1].mean()), 2),
                            "blue_mean": round(float(component_rgb[:, 2].mean()), 2),
                            "nir_mean": round(float(array[component][:, 3].mean()), 2),
                            "pixel_count": int(component.sum()),
                        },
                        "source": "multispectral_imagery_spectral_heuristic",
                        "spectral_signal": (
                            "high_ndvi_vegetation"
                            if candidate_type == "vegetated_turf_candidate"
                            else "low_ndvi_bright_surface"
                        ),
                        "confidence": "low",
                        "validation_status": "unreviewed",
                        "semantic_golf_feature": None,
                        "not_ground_truth": True,
                    },
                })

    summary = {
        "candidate_count": len(features),
        "candidate_counts_by_type": dict(counts),
        "imagery_bands": ["red", "green", "blue", "near_infrared"],
        "pixel_area_m2": round(pixel_area, 4),
        "thresholds": {
            "turf_ndvi_min": turf_ndvi_min,
            "bare_ndvi_max": bare_ndvi_max,
            "bare_min_brightness": min_brightness,
            "component_area_m2": [min_area_m2, max_area_m2],
        },
        "confidence": "low",
        "validation_status": "unreviewed",
        "warning": (
            "Spectral classes are not golf-feature labels. Vegetation may be "
            "trees or rough; bare ground may be paths, soil, bunkers, or roofs. "
            "Do not use as tees, greens, fairways, or hazards without review."
        ),
    }
    return {"type": "FeatureCollection", "features": features}, summary
