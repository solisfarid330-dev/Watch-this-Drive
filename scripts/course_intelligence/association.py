"""Reusable nearest-hole feature association with ambiguity reporting."""
from __future__ import annotations

from collections import Counter

from .geometry import local_metric_transformer, project_wgs84

DEFAULT_FEATURE_TYPES = {
    "tee", "fairway", "green", "bunker", "water_hazard",
    "lateral_water_hazard",
}


def associate_features_to_holes(
    features,
    *,
    max_distance_m=110.0,
    ambiguity_margin_m=25.0,
    feature_types=None,
):
    """Associate supported WGS84 GeoJSON features to nearest golf=hole lines.

    Returns hole draft features and summary metrics. Associations are always
    provisional; ambiguous alternatives and unassigned features are retained
    in summary metadata rather than silently forced into a hole.
    """
    if max_distance_m <= 0 or ambiguity_margin_m < 0:
        raise ValueError("Distance thresholds must be positive/non-negative")
    feature_types = set(feature_types or DEFAULT_FEATURE_TYPES)
    hole_inputs = []
    for feature in features:
        props = feature.get("properties") or {}
        geom = feature.get("_shape")
        if props.get("golf") == "hole" and geom is not None and not geom.is_empty:
            if geom.geom_type in {"LineString", "MultiLineString"}:
                hole_inputs.append((feature, geom))
    if not hole_inputs:
        raise ValueError("No usable golf=hole line geometries were found")

    transformer = local_metric_transformer(
        [geom for _, geom in hole_inputs]
        + [feature["_shape"] for feature in features if feature.get("_shape") is not None]
    )
    projected_holes = [project_wgs84(geom, transformer) for _, geom in hole_inputs]
    holes = []
    for index, (source, _) in enumerate(hole_inputs, start=1):
        props = source.get("properties") or {}
        tags = props.get("tags") or {}
        label = next((str(tags[k]) for k in ("ref", "name", "hole", "ref:hole") if tags.get(k)), f"unlabeled-{index:02d}")
        holes.append({
            "type": "Feature",
            "id": f"hole_draft_{index:02d}",
            "geometry": source["geometry"],
            "properties": {
                "hole_label": label,
                "geometry_role": "osm_hole_centerline",
                "source": props.get("source", "OpenStreetMap"),
                "source_feature_id": source.get("id"),
                "source_tags": tags,
                "confidence": "unassessed",
                "validation_status": "requires_visual_review",
                "associated_features": [],
                "associated_feature_count": 0,
                "warning": "Provisional nearest-centerline grouping; visual review required before use.",
            },
        })

    counts = Counter()
    ambiguous_count = 0
    unassigned_count = 0
    for feature in features:
        props = feature.get("properties") or {}
        kind = props.get("golf")
        geom = feature.get("_shape")
        if kind not in feature_types or geom is None or geom.is_empty:
            continue
        projected = project_wgs84(geom, transformer)
        ranked = sorted(
            ((float(line.distance(projected)), idx) for idx, line in enumerate(projected_holes)),
            key=lambda pair: pair[0],
        )
        if not ranked or ranked[0][0] > max_distance_m:
            unassigned_count += 1
            continue
        nearest, nearest_idx = ranked[0]
        second = ranked[1][0] if len(ranked) > 1 else None
        alternatives = [
            f"hole_draft_{idx + 1:02d}"
            for distance, idx in ranked[1:]
            if distance <= max_distance_m and distance - nearest <= ambiguity_margin_m
        ]
        ambiguous = bool(alternatives)
        ambiguous_count += int(ambiguous)
        holes[nearest_idx]["properties"]["associated_features"].append({
            "source_feature_id": feature.get("id"),
            "source": props.get("source", "OpenStreetMap"),
            "golf_type": kind,
            "distance_to_hole_line_m": round(nearest, 1),
            "geometry": feature["geometry"],
            "source_tags": props.get("tags") or {},
            "association_status": "ambiguous_nearest_centerline" if ambiguous else "provisional_nearest_centerline",
            "alternative_hole_draft_ids": alternatives,
            "second_nearest_distance_m": round(second, 1) if second is not None else None,
        })
        counts[kind] += 1

    for hole in holes:
        hole["properties"]["associated_feature_count"] = len(hole["properties"]["associated_features"])
    summary = {
        "hole_centerlines": len(holes),
        "associated_features_by_type": dict(counts),
        "ambiguous_associations": ambiguous_count,
        "unassigned_features": unassigned_count,
        "holes_without_associations": sum(not h["properties"]["associated_features"] for h in holes),
    }
    return holes, summary
