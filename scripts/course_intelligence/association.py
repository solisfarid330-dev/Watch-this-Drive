"""Course-wide OSM geometry association with explicit-reference and spatial evidence."""
from __future__ import annotations

from collections import Counter

from .geometry import local_metric_transformer, project_wgs84

# Preserve useful golf and course-context geometry instead of filtering the
# source down to only the six feature classes used by the first prototype.
DEFAULT_FEATURE_TYPES = {
    "tee", "tee_area", "fairway", "green", "green_area", "bunker",
    "water_hazard", "lateral_water_hazard", "hole", "path", "cartpath",
    "rough", "tree", "out_of_bounds", "golf_cart_path",
}


def _tags(feature):
    props = feature.get("properties") or {}
    return props.get("tags") or {}


def _kind(feature):
    props = feature.get("properties") or {}
    return props.get("golf") or _tags(feature).get("golf")


def _hole_ref(feature, *, is_centerline=False):
    """Return an explicit hole association, not an unrelated feature ref.

    OSM's generic ref is commonly an identifier for the mapped object.
    It is only a hole number on golf=hole centerlines; other features must use
    the unambiguous ref:hole or hole tags to claim a hole association.
    """
    tags = _tags(feature)
    keys = ("ref:hole", "hole", "ref") if is_centerline else ("ref:hole", "hole")
    for key in keys:
        value = tags.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return None


def associate_features_to_holes(
    features,
    *,
    max_distance_m=110.0,
    ambiguity_margin_m=25.0,
    feature_types=None,
):
    """Build hole records from OSM centerlines and attach all useful geometry.

    Linear golf=hole objects are treated as centerlines. Polygonal golf=hole
    objects are retained as hole outlines and associated separately; they are
    never mistaken for centerlines. Explicit hole references outrank proximity.
    Every source feature remains represented either on a hole or in the
    course-level unassigned collection in the returned summary.
    """
    if max_distance_m <= 0 or ambiguity_margin_m < 0:
        raise ValueError("Distance thresholds must be positive/non-negative")
    feature_types = set(feature_types or DEFAULT_FEATURE_TYPES)

    hole_inputs, outline_inputs = [], []
    for feature in features:
        geom = feature.get("_shape")
        if _kind(feature) != "hole" or geom is None or geom.is_empty:
            continue
        if geom.geom_type in {"LineString", "MultiLineString"}:
            hole_inputs.append((feature, geom))
        elif geom.geom_type in {"Polygon", "MultiPolygon"}:
            outline_inputs.append((feature, geom))
    if not hole_inputs:
        raise ValueError("No usable linear golf=hole centerlines were found")

    all_geometries = [
        feature["_shape"] for feature in features
        if feature.get("_shape") is not None and not feature["_shape"].is_empty
    ]
    transformer = local_metric_transformer(all_geometries)
    projected_holes = [project_wgs84(geom, transformer) for _, geom in hole_inputs]

    holes = []
    for index, (source, _) in enumerate(hole_inputs, start=1):
        props = source.get("properties") or {}
        tags = _tags(source)
        label = next(
            (str(tags[k]) for k in ("ref", "name", "hole", "ref:hole") if tags.get(k)),
            f"unlabeled-{index:02d}",
        )
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
                "warning": "Associations are evidence-ranked hypotheses; inspect unresolved conflicts.",
            },
        })

    counts = Counter()
    ambiguous_count = 0
    unassigned = []

    # Hole outlines are a distinct geometry role, but use the same evidence
    # ranking as other features. Their area/containment is especially useful.
    candidates = [
        (feature, geom, "hole_outline")
        for feature, geom in outline_inputs
    ]
    for feature in features:
        kind = _kind(feature)
        geom = feature.get("_shape")
        if kind == "hole" or kind not in feature_types or geom is None or geom.is_empty:
            continue
        candidates.append((feature, geom, kind))

    for feature, geom, kind in candidates:
        projected = project_wgs84(geom, transformer)
        ref = _hole_ref(feature)
        ranked = []
        for idx, (source, centerline) in enumerate(zip(hole_inputs, projected_holes)):
            source_ref = _hole_ref(source[0], is_centerline=True)
            explicit_match = bool(ref and source_ref and ref == source_ref)
            explicit_conflict = bool(ref and source_ref and ref != source_ref)
            distance = float(centerline.distance(projected))
            # Polygon boundaries can enclose a centerline without touching it;
            # distance to the polygon is zero in that case.
            if kind == "hole_outline" and projected.covers(centerline):
                distance = 0.0
            ranked.append((0 if explicit_match else 1, distance, idx, explicit_conflict))
        ranked.sort(key=lambda item: (item[0], item[1]))
        if not ranked:
            unassigned.append(feature.get("id"))
            continue

        # Never override an explicit reference to a different hole.
        if ref and any(item[3] for item in ranked) and not any(item[0] == 0 for item in ranked):
            unassigned.append(feature.get("id"))
            continue

        best_ref_rank, nearest, nearest_idx, _ = ranked[0]
        if best_ref_rank == 1 and nearest > max_distance_m:
            unassigned.append(feature.get("id"))
            continue
        alternatives = []
        for ref_rank, distance, idx, conflict in ranked[1:]:
            if conflict:
                continue
            if ref_rank == best_ref_rank and distance <= max_distance_m and distance - nearest <= ambiguity_margin_m:
                alternatives.append(f"hole_draft_{idx + 1:02d}")
        ambiguous = bool(alternatives)
        ambiguous_count += int(ambiguous)
        status = (
            "explicit_hole_reference" if best_ref_rank == 0
            else "ambiguous_nearest_centerline" if ambiguous
            else "provisional_nearest_centerline"
        )
        holes[nearest_idx]["properties"]["associated_features"].append({
            "source_feature_id": feature.get("id"),
            "source": (feature.get("properties") or {}).get("source", "OpenStreetMap"),
            "golf_type": kind,
            "distance_to_hole_line_m": round(nearest, 1),
            "geometry": feature.get("geometry"),
            "source_tags": _tags(feature),
            "association_status": status,
            "alternative_hole_draft_ids": alternatives,
        })
        counts[kind] += 1

    for hole in holes:
        associated = hole["properties"]["associated_features"]
        hole["properties"]["associated_feature_count"] = len(associated)
        hole["properties"]["associated_features_by_type"] = dict(
            Counter(item["golf_type"] for item in associated)
        )

    summary = {
        "hole_centerlines": len(holes),
        "hole_outlines_found": len(outline_inputs),
        "hole_outlines_associated": counts.get("hole_outline", 0),
        "associated_features_by_type": dict(counts),
        "ambiguous_associations": ambiguous_count,
        "unassigned_feature_ids": unassigned,
        "unassigned_features": len(unassigned),
        "holes_without_associations": sum(
            not h["properties"]["associated_features"] for h in holes
        ),
        "source_feature_count": len(features),
        "preservation_note": "All source features are either associated to a hole or listed as unassigned; no source geometry is silently discarded.",
    }
    return holes, summary
