#!/usr/bin/env python3
"""Generic OpenStreetMap golf-course data audit.

Research prototype only. Accepts a course name and location, discovers a likely
OSM golf-course element, retrieves golf-tagged features, and writes a raw-data
snapshot plus a transparent inventory report. No course IDs or coordinates are
embedded in this program.

Uses only Python's standard library.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

USER_AGENT = "WatchThisDrive-Research/0.1 (generic golf-course data audit)"
NOMINATIM = "https://nominatim.openstreetmap.org"
OVERPASS = (
    "https://ovp.dresel.systems/api-na/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
)
TIMEOUT = 90


class PipelineError(RuntimeError):
    pass


def get_json(url: str, *, data: bytes | None = None, timeout: int = 30):
    request = urllib.request.Request(
        url,
        data=data,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "application/json",
            **({"Content-Type": "application/x-www-form-urlencoded"} if data else {}),
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def normalize(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", value.lower()).strip()


def token_similarity(a: str, b: str) -> float:
    aa, bb = set(normalize(a).split()), set(normalize(b).split())
    if not aa or not bb:
        return 0.0
    return len(aa & bb) / len(aa | bb)


def discover_course(course_name: str, location: str) -> dict:
    query = urllib.parse.urlencode({
        "q": f"{course_name}, {location}",
        "format": "jsonv2",
        "addressdetails": 1,
        "limit": 10,
        "namedetails": 1,
        "extratags": 1,
    })
    results = get_json(f"{NOMINATIM}/search?{query}")
    if not results:
        raise PipelineError("Geocoder found no candidates for that course and location.")

    candidates = []
    for item in results:
        osm_type = item.get("osm_type", "").lower()
        osm_id = item.get("osm_id")
        category = item.get("category") or item.get("class")
        kind = item.get("type")
        display = item.get("name") or item.get("display_name", "")
        tags = item.get("extratags") or {}
        is_golf = (
            (category == "leisure" and kind == "golf_course")
            or tags.get("leisure") == "golf_course"
        )
        score = token_similarity(course_name, display)
        if is_golf:
            score += 1.0
        if osm_type in {"way", "relation"}:
            score += 0.15
        candidates.append((score, is_golf, item))

    candidates.sort(key=lambda row: (row[0], row[1]), reverse=True)
    best_score, is_golf, best = candidates[0]
    if not is_golf:
        # A generic location result is not sufficient evidence that we found
        # the golf course boundary. Refuse to silently treat it as one.
        raise PipelineError(
            "Search returned locations but did not identify a result explicitly "
            "tagged as leisure=golf_course. Try a more specific course name/location."
        )
    return {
        "osm_type": best["osm_type"].lower(),
        "osm_id": int(best["osm_id"]),
        "name": best.get("name") or best.get("display_name", course_name),
        "display_name": best.get("display_name", ""),
        "lat": float(best["lat"]),
        "lon": float(best["lon"]),
        "score": round(best_score, 4),
        "nominatim_result": best,
        "alternatives": [
            {
                "osm_type": row[2].get("osm_type"),
                "osm_id": row[2].get("osm_id"),
                "name": row[2].get("name") or row[2].get("display_name"),
                "category": row[2].get("category") or row[2].get("class"),
                "type": row[2].get("type"),
                "score": round(row[0], 4),
            }
            for row in candidates[1:6]
        ],
    }


def build_overpass_query(course: dict) -> str:
    element_type = course["osm_type"]
    if element_type not in {"way", "relation"}:
        raise PipelineError(
            "The discovered golf-course object is a node, not a usable boundary. "
            "Boundary discovery/association is required; refusing to invent one."
        )
    return f"""[out:json][timeout:75];
{element_type}({course['osm_id']});
map_to_area -> .courseArea;
(
  nwr(area.courseArea)["golf"];
);
out body geom;
"""


def retrieve_features(query: str) -> tuple[dict, str]:
    body = urllib.parse.urlencode({"data": query}).encode()
    errors = []
    for endpoint in OVERPASS:
        for attempt in range(2):
            try:
                payload = get_json(endpoint, data=body, timeout=TIMEOUT)
                return payload, endpoint
            except Exception as exc:
                errors.append(
                    f"{endpoint} (attempt {attempt + 1}): "
                    f"{type(exc).__name__}: {exc}"
                )
                if attempt == 0:
                    time.sleep(2.0)
    raise PipelineError("All Overpass endpoints failed:\n" + "\n".join(errors))


def inventory(payload: dict) -> dict:
    elements = payload.get("elements", [])
    counts = Counter()
    type_counts = Counter()
    missing_geometry = Counter()
    hole_refs = Counter()
    features = []

    for element in elements:
        tags = element.get("tags") or {}
        golf = tags.get("golf")
        if not golf:
            continue
        counts[golf] += 1
        type_counts[f"{golf}:{element.get('type', 'unknown')}"] += 1
        geometry = element.get("geometry")
        members = element.get("members")
        if not geometry and not members and element.get("type") != "node":
            missing_geometry[golf] += 1
        hole_ref = tags.get("ref") or tags.get("golf:hole")
        if hole_ref:
            hole_refs[f"{golf}:{hole_ref}"] += 1

        features.append({
            "osm_type": element.get("type"),
            "osm_id": element.get("id"),
            "golf": golf,
            "name": tags.get("name"),
            "ref": tags.get("ref"),
            "golf:hole": tags.get("golf:hole"),
            "geometry_vertex_count": len(geometry or []),
            "relation_member_count": len(members or []),
            "tags": tags,
        })

    return {
        "element_count": len(elements),
        "golf_feature_count": len(features),
        "counts_by_golf_tag": dict(sorted(counts.items())),
        "counts_by_element_and_tag": dict(sorted(type_counts.items())),
        "features_missing_direct_geometry": dict(sorted(missing_geometry.items())),
        "hole_reference_counts": dict(sorted(hole_refs.items())),
        "features": features,
        "limitations": [
            "OSM feature counts are element counts, not guaranteed counts of physical objects.",
            "A relation may represent a multipolygon or grouped feature; member rings must be reconstructed before measuring its footprint.",
            "A feature without an explicit hole reference is not automatically assigned to a hole.",
            "Missing OSM features are unknown, not evidence that the real course lacks them.",
            "A successful HTTP response and valid JSON do not establish real-world geometric accuracy.",
        ],
    }



def _ring_area(ring: list[list[float]]) -> float:
    return 0.5 * sum(
        ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1]
        for i in range(len(ring) - 1)
    )


def _point_in_ring(point: list[float], ring: list[list[float]]) -> bool:
    x, y = point
    inside = False
    for i in range(len(ring) - 1):
        x1, y1 = ring[i]
        x2, y2 = ring[i + 1]
        if (y1 > y) != (y2 > y):
            crossing_x = (x2 - x1) * (y - y1) / (y2 - y1) + x1
            if x < crossing_x:
                inside = not inside
    return inside


def _assemble_member_rings(members: list[dict], role: str) -> tuple[list[list[list[float]]], list[str]]:
    segments = []
    errors = []
    for member in members:
        if member.get("role") != role:
            continue
        if member.get("type") != "way":
            errors.append(f"{role} member is not a way: {member.get('type')}")
            continue
        coords = [
            [float(point["lon"]), float(point["lat"])]
            for point in (member.get("geometry") or [])
            if "lon" in point and "lat" in point
        ]
        if len(coords) < 2:
            errors.append(f"{role} member way/{member.get('ref')} has no usable geometry")
            continue
        segments.append(coords)

    rings = []
    while segments:
        ring = segments.pop(0)
        while ring[-1] != ring[0]:
            match_index = None
            match_segment = None
            reverse_segment = False
            for index, segment in enumerate(segments):
                if segment[0] == ring[-1]:
                    match_index, match_segment = index, segment
                    break
                if segment[-1] == ring[-1]:
                    match_index, match_segment = index, list(reversed(segment))
                    reverse_segment = True
                    break
            if match_index is None:
                errors.append(f"Could not close a {role} ring from relation members")
                break
            segments.pop(match_index)
            ring.extend(match_segment[1:])
        if ring[-1] == ring[0] and len(ring) >= 4:
            rings.append(ring)
    return rings, errors


def _relation_multipolygon_geometry(element: dict) -> tuple[dict | None, list[str]]:
    members = element.get("members") or []
    outers, outer_errors = _assemble_member_rings(members, "outer")
    inners, inner_errors = _assemble_member_rings(members, "inner")
    errors = outer_errors + inner_errors
    if not outers:
        return None, errors + ["Multipolygon has no complete outer ring"]

    polygons = [[outer] for outer in outers]
    for inner in inners:
        containing = [
            index for index, outer in enumerate(outers)
            if _point_in_ring(inner[0], outer)
        ]
        if not containing:
            errors.append("An inner ring could not be assigned to an outer ring")
            continue
        owner = min(containing, key=lambda index: abs(_ring_area(outers[index])))
        polygons[owner].append(inner)

    if any("inner ring could not be assigned" in error for error in errors):
        return None, errors

    # RFC 7946 winding: outer rings counter-clockwise, holes clockwise.
    for polygon in polygons:
        if _ring_area(polygon[0]) < 0:
            polygon[0].reverse()
        for hole in polygon[1:]:
            if _ring_area(hole) > 0:
                hole.reverse()

    if len(polygons) == 1:
        return {"type": "Polygon", "coordinates": polygons[0]}, errors
    return {"type": "MultiPolygon", "coordinates": polygons}, errors


def to_geojson(payload: dict) -> dict:
    """Export nodes, ways, and assembled multipolygon relations.

    Relation outer/inner member rings are stitched and validated. If a relation
    cannot be assembled without guessing, it is omitted from this preview and
    the reason is recorded in metadata; its complete raw source remains saved.
    """
    features = []
    skipped = []
    relation_count = 0

    for element in payload.get("elements", []):
        tags = element.get("tags") or {}
        if not tags.get("golf"):
            continue

        geometry = None
        if element.get("type") == "node":
            if "lon" in element and "lat" in element:
                geometry = {
                    "type": "Point",
                    "coordinates": [float(element["lon"]), float(element["lat"])],
                }
        elif element.get("type") == "way":
            coords = [
                [float(point["lon"]), float(point["lat"])]
                for point in (element.get("geometry") or [])
                if "lon" in point and "lat" in point
            ]
            if len(coords) >= 2:
                closed = len(coords) >= 4 and coords[0] == coords[-1]
                if closed:
                    if _ring_area(coords) < 0:
                        coords.reverse()
                    geometry = {"type": "Polygon", "coordinates": [coords]}
                else:
                    geometry = {"type": "LineString", "coordinates": coords}
        elif element.get("type") == "relation":
            relation_count += 1
            if tags.get("type") == "multipolygon":
                geometry, errors = _relation_multipolygon_geometry(element)
                if geometry is None:
                    skipped.append({
                        "osm_type": "relation",
                        "osm_id": element.get("id"),
                        "reason": "; ".join(errors) or "Multipolygon geometry could not be assembled",
                    })
            else:
                skipped.append({
                    "osm_type": "relation",
                    "osm_id": element.get("id"),
                    "reason": "Relation is not tagged as a supported multipolygon",
                })

        if geometry is None:
            if element.get("type") != "relation":
                skipped.append({
                    "osm_type": element.get("type"),
                    "osm_id": element.get("id"),
                    "reason": "No usable direct geometry",
                })
            continue

        features.append({
            "type": "Feature",
            "id": f"{element.get('type')}/{element.get('id')}",
            "geometry": geometry,
            "properties": {
                "osm_type": element.get("type"),
                "osm_id": element.get("id"),
                **tags,
            },
        })

    return {
        "type": "FeatureCollection",
        "name": "OSM golf features (assembled direct and relation geometries)",
        "features": features,
        "metadata": {
            "coordinate_order": "longitude, latitude (EPSG:4326)",
            "relation_count": relation_count,
            "relations_or_features_omitted": skipped,
            "warning": (
                "Multipolygon relations are included only when all outer rings "
                "can be assembled and every inner ring can be assigned. Review "
                "the omission list and compare the result with aerial imagery."
            ),
        },
    }

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--course", required=True, help="Golf course name")
    parser.add_argument("--location", required=True, help="City/region/country")
    parser.add_argument("--out", default="course-audit", help="Output directory")
    args = parser.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    print(f"Discovering: {args.course}, {args.location}", flush=True)
    course = discover_course(args.course, args.location)
    print(
        f"Candidate: {course['name']} ({course['osm_type']}/{course['osm_id']})",
        flush=True,
    )

    query = build_overpass_query(course)
    payload, endpoint = retrieve_features(query)
    report = inventory(payload)
    print(f"Overpass endpoint: {endpoint}", flush=True)
    print(f"OSM data timestamp: {payload.get('osm3s', {}).get('timestamp_osm_base', 'not supplied')}", flush=True)

    metadata = {
        "requested_course": args.course,
        "requested_location": args.location,
        "discovered_course": {
            key: value for key, value in course.items()
            if key != "nominatim_result"
        },
        "nominatim_result": course["nominatim_result"],
        "overpass_endpoint": endpoint,
        "overpass_query": query,
        "retrieved_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "osm_copyright": "© OpenStreetMap contributors; data available under ODbL",
    }

    (out / "source-response.json").write_text(
        json.dumps({"metadata": metadata, "overpass": payload}, indent=2),
        encoding="utf-8",
    )
    (out / "inventory-report.json").write_text(
        json.dumps({"metadata": metadata, "inventory": report}, indent=2),
        encoding="utf-8",
    )
    geojson = to_geojson(payload)
    (out / "features.geojson").write_text(
        json.dumps(geojson, indent=2),
        encoding="utf-8",
    )

    print("\nFeature inventory:")
    for tag, count in report["counts_by_golf_tag"].items():
        print(f"  {tag:20s} {count:4d}")
    print(f"\nTotal golf-tagged elements: {report['golf_feature_count']}")
    print(f"Raw response: {(out / 'source-response.json').resolve()}")
    print(f"Report:       {(out / 'inventory-report.json').resolve()}")
    print(f"GeoJSON:      {(out / 'features.geojson').resolve()}")


if __name__ == "__main__":
    try:
        main()
    except (PipelineError, urllib.error.URLError, TimeoutError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(2)
