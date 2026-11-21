"""Provisional tee-to-green route hypotheses independent of OSM hole lines."""
from __future__ import annotations

from collections import defaultdict
from shapely.geometry import LineString
from shapely.ops import unary_union

from .geometry import local_metric_transformer, project_wgs84

TEE_TYPES = {"tee", "tee_area"}
GREEN_TYPES = {"green", "green_area"}
FAIRWAY_TYPES = {"fairway", "fairway_corridor"}


def _feature_kind(feature):
    props = feature.get("properties") or {}
    return props.get("feature_class") or props.get("golf") or props.get("candidate_type")


def _usable(features, accepted_types):
    return [
        (feature, feature["_shape"])
        for feature in features
        if feature.get("_shape") is not None
        and not feature["_shape"].is_empty
        and _feature_kind(feature) in accepted_types
    ]


def propose_hole_routes_from_features(
    features,
    *,
    max_hole_length_m=650.0,
    corridor_half_width_m=22.0,
    ambiguity_score_margin=0.12,
):
    """Propose straight tee-to-green links without requiring golf=hole lines.

    Inputs are WGS84 GeoJSON-like features with Shapely geometries in _shape.
    Candidate classes may be in feature_class, candidate_type, or golf.
    Fairway overlap is only a bounded ranking hint. All results require review;
    this does not infer doglegs, hole numbering, or intended routing.
    """
    if max_hole_length_m <= 0 or corridor_half_width_m <= 0:
        raise ValueError("Length and corridor width must be positive")
    if ambiguity_score_margin < 0:
        raise ValueError("Ambiguity margin cannot be negative")

    tees = _usable(features, TEE_TYPES)
    greens = _usable(features, GREEN_TYPES)
    fairways = _usable(features, FAIRWAY_TYPES)
    if not tees or not greens:
        raise ValueError("At least one tee candidate and one green candidate are required")

    transformer = local_metric_transformer(
        [geom.centroid for _, geom in tees + greens + fairways]
    )
    projected_tees = [(f, g, project_wgs84(g, transformer)) for f, g in tees]
    projected_greens = [(f, g, project_wgs84(g, transformer)) for f, g in greens]
    projected_fairways = [project_wgs84(g, transformer) for _, g in fairways]
    fairway_union = unary_union(projected_fairways) if projected_fairways else None

    candidates = []
    for tee_feature, tee_geom, tee_metric in projected_tees:
        tee_point = tee_metric.centroid
        for green_feature, green_geom, green_metric in projected_greens:
            green_point = green_metric.centroid
            distance = float(tee_point.distance(green_point))
            if distance <= 1.0 or distance > max_hole_length_m:
                continue
            route = LineString([tee_point, green_point])
            corridor = route.buffer(corridor_half_width_m)
            overlap = (
                float(corridor.intersection(fairway_union).area / corridor.area)
                if fairway_union is not None and corridor.area else 0.0
            )
            score = (distance / max_hole_length_m) * (1.0 - 0.25 * min(overlap, 1.0))
            tee_props = tee_feature.get("properties") or {}
            green_props = green_feature.get("properties") or {}
            tee_id = str(tee_feature.get("id", tee_props.get("candidate_id", "tee-unknown")))
            green_id = str(green_feature.get("id", green_props.get("candidate_id", "green-unknown")))
            tee_wgs84, green_wgs84 = tee_geom.centroid, green_geom.centroid
            candidates.append({
                "type": "Feature",
                "id": f"route_hypothesis_{len(candidates) + 1:03d}",
                "geometry": {
                    "type": "LineString",
                    "coordinates": [
                        [float(tee_wgs84.x), float(tee_wgs84.y)],
                        [float(green_wgs84.x), float(green_wgs84.y)],
                    ],
                },
                "properties": {
                    "geometry_role": "provisional_tee_to_green_route_hypothesis",
                    "tee_candidate_id": tee_id,
                    "green_candidate_id": green_id,
                    "estimated_straight_line_length_m": round(distance, 1),
                    "fairway_corridor_overlap": round(overlap, 3),
                    "ranking_score": round(score, 5),
                    "source": "imagery_or_external_feature_candidates",
                    "confidence": "unassessed",
                    "validation_status": "requires_visual_review",
                    "warning": "Straight tee-to-green hypothesis only; doglegs and intended routing remain unresolved.",
                },
            })

    by_tee, by_green = defaultdict(list), defaultdict(list)
    for candidate in candidates:
        props = candidate["properties"]
        by_tee[props["tee_candidate_id"]].append(candidate)
        by_green[props["green_candidate_id"]].append(candidate)

    ambiguous_count = 0
    for candidate in candidates:
        props = candidate["properties"]
        related = [
            other for other in by_tee[props["tee_candidate_id"]] + by_green[props["green_candidate_id"]]
            if other is not candidate
        ]
        best_score = min([props["ranking_score"]] + [
            other["properties"]["ranking_score"] for other in related
        ])
        alternatives = [
            other["id"] for other in related
            if other["properties"]["ranking_score"] - best_score <= ambiguity_score_margin
        ]
        props["alternative_route_hypothesis_ids"] = alternatives
        props["association_status"] = "ambiguous" if alternatives else "unambiguous_candidate"
        ambiguous_count += int(bool(alternatives))

    candidates.sort(key=lambda item: item["properties"]["ranking_score"])
    paired_tees = {item["properties"]["tee_candidate_id"] for item in candidates}
    summary = {
        "tee_candidates": len(tees),
        "green_candidates": len(greens),
        "fairway_candidates": len(fairways),
        "route_hypotheses": len(candidates),
        "ambiguous_hypotheses": ambiguous_count,
        "unpaired_tee_candidates": len({
            str(feature.get("id", (feature.get("properties") or {}).get("candidate_id", "tee-unknown")))
            for feature, _ in tees
        } - paired_tees),
        "status": "provisional_requires_visual_review",
    }
    return candidates, summary
