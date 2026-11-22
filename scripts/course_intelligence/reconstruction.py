"""Provisional tee-to-green route hypotheses independent of OSM hole lines."""
from __future__ import annotations

from collections import defaultdict
from heapq import heappop, heappush
from itertools import count
from math import hypot

from shapely.geometry import LineString, Point
from shapely.ops import nearest_points, unary_union, transform as transform_geometry

from .geometry import local_metric_transformer_pair, project_wgs84

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


def _grid_fairway_path(start, end, fairway, *, cell_size_m=6.0, max_cells=50000):
    """Find a coarse shortest path through a fairway polygon using an 8-neighbor grid."""
    if fairway.is_empty:
        return None
    min_x, min_y, max_x, max_y = fairway.bounds
    width = int((max_x - min_x) / cell_size_m) + 1
    height = int((max_y - min_y) / cell_size_m) + 1
    if width * height > max_cells:
        return None

    allowed = fairway.buffer(cell_size_m * 1.5)
    def cell_point(cell):
        return Point(min_x + cell[0] * cell_size_m, min_y + cell[1] * cell_size_m)

    def nearest_cell(point):
        x = min(max(round((point.x - min_x) / cell_size_m), 0), width - 1)
        y = min(max(round((point.y - min_y) / cell_size_m), 0), height - 1)
        origin = (x, y)
        if allowed.covers(cell_point(origin)):
            return origin
        # Search expanding rings for the nearest traversable grid cell.
        for radius in range(1, max(width, height)):
            options = []
            for dx in range(-radius, radius + 1):
                for dy in (-radius, radius):
                    options.extend([(x + dx, y + dy), (x + dy, y + dx)])
            for cell in options:
                if 0 <= cell[0] < width and 0 <= cell[1] < height and allowed.covers(cell_point(cell)):
                    return cell
        return None

    start_cell, end_cell = nearest_cell(start), nearest_cell(end)
    if start_cell is None or end_cell is None:
        return None

    sequence = count()
    queue = [(0.0, next(sequence), start_cell)]
    costs = {start_cell: 0.0}
    previous = {}
    directions = [(1, 0), (-1, 0), (0, 1), (0, -1),
                  (1, 1), (1, -1), (-1, 1), (-1, -1)]
    while queue:
        _, _, current = heappop(queue)
        if current == end_cell:
            cells = [current]
            while current in previous:
                current = previous[current]
                cells.append(current)
            cells.reverse()
            return [start, *[cell_point(cell) for cell in cells], end]
        current_cost = costs[current]
        for dx, dy in directions:
            nxt = (current[0] + dx, current[1] + dy)
            if not (0 <= nxt[0] < width and 0 <= nxt[1] < height):
                continue
            if not allowed.covers(cell_point(nxt)):
                continue
            step = cell_size_m * (1.41421356237 if dx and dy else 1.0)
            new_cost = current_cost + step
            if new_cost < costs.get(nxt, float("inf")):
                costs[nxt] = new_cost
                previous[nxt] = current
                heuristic = hypot(nxt[0] - end_cell[0], nxt[1] - end_cell[1]) * cell_size_m
                heappush(queue, (new_cost + heuristic, next(sequence), nxt))
    return None


def propose_hole_routes_from_features(
    features,
    *,
    max_hole_length_m=650.0,
    corridor_half_width_m=22.0,
    ambiguity_score_margin=0.12,
    fairway_grid_cell_size_m=6.0,
):
    """Propose routes without requiring golf=hole lines.

    Tee/green/fairway candidates may originate from imagery or another source.
    When fairway polygons exist, a coarse grid path attempts to follow their
    shape; otherwise a straight line is emitted. Outputs remain hypotheses,
    never validated playable centerlines.
    """
    if max_hole_length_m <= 0 or corridor_half_width_m <= 0 or fairway_grid_cell_size_m <= 0:
        raise ValueError("Length and geometry parameters must be positive")
    if ambiguity_score_margin < 0:
        raise ValueError("Ambiguity margin cannot be negative")

    tees, greens, fairways = (
        _usable(features, TEE_TYPES),
        _usable(features, GREEN_TYPES),
        _usable(features, FAIRWAY_TYPES),
    )
    if not tees or not greens:
        raise ValueError("At least one tee candidate and one green candidate are required")

    forward, inverse = local_metric_transformer_pair(
        [geom.centroid for _, geom in tees + greens + fairways]
    )
    projected_tees = [(f, project_wgs84(g, forward)) for f, g in tees]
    projected_greens = [(f, project_wgs84(g, forward)) for f, g in greens]
    projected_fairways = [project_wgs84(g, forward) for _, g in fairways]
    fairway_union = unary_union(projected_fairways) if projected_fairways else None

    candidates = []
    for tee_feature, tee_metric in projected_tees:
        tee_point = tee_metric.centroid
        for green_feature, green_metric in projected_greens:
            green_point = green_metric.centroid
            distance = float(tee_point.distance(green_point))
            if distance <= 1.0 or distance > max_hole_length_m:
                continue

            route_points = None
            route_method = "straight_line_fallback"
            overlap = 0.0
            if fairway_union is not None:
                corridor = LineString([tee_point, green_point]).buffer(corridor_half_width_m)
                overlap = float(corridor.intersection(fairway_union).area / corridor.area)
                # Snap endpoints to the mapped/detected fairway, then route inside it.
                tee_snap = nearest_points(tee_point, fairway_union)[1]
                green_snap = nearest_points(green_point, fairway_union)[1]
                route_points = _grid_fairway_path(
                    tee_snap, green_snap, fairway_union,
                    cell_size_m=fairway_grid_cell_size_m,
                )
                if route_points:
                    route_method = "fairway_grid_path"
                    route_points = [tee_point, *route_points, green_point]
            if not route_points:
                route_points = [tee_point, green_point]

            metric_line = LineString(route_points)
            route_length = float(metric_line.length)
            if route_length > max_hole_length_m * 1.8:
                # Avoid absurd detours caused by merged or neighboring fairways.
                metric_line = LineString([tee_point, green_point])
                route_method = "straight_line_fallback"
                route_length = distance

            wgs84_line = transform_geometry(inverse, metric_line)
            tee_props = tee_feature.get("properties") or {}
            green_props = green_feature.get("properties") or {}
            tee_id = str(tee_feature.get("id", tee_props.get("candidate_id", "tee-unknown")))
            green_id = str(green_feature.get("id", green_props.get("candidate_id", "green-unknown")))
            score = (distance / max_hole_length_m) * (1.0 - 0.25 * min(overlap, 1.0))
            candidates.append({
                "type": "Feature",
                "id": f"route_hypothesis_{len(candidates) + 1:03d}",
                "geometry": {
                    "type": "LineString",
                    "coordinates": [[float(x), float(y)] for x, y in wgs84_line.coords],
                },
                "properties": {
                    "geometry_role": "provisional_tee_to_green_route_hypothesis",
                    "tee_candidate_id": tee_id,
                    "green_candidate_id": green_id,
                    "estimated_straight_line_length_m": round(distance, 1),
                    "provisional_route_length_m": round(route_length, 1),
                    "fairway_corridor_overlap": round(overlap, 3),
                    "route_method": route_method,
                    "ranking_score": round(score, 5),
                    "source": "imagery_or_external_feature_candidates",
                    "confidence": "unassessed",
                    "validation_status": "requires_visual_review",
                    "warning": "Fairway-following path is a geometric hypothesis; dogleg intent, hole order, and neighboring-hole ambiguity remain unresolved.",
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
