#!/usr/bin/env python3
"""Discover NAIP STAC items intersecting the Lake Arlington OSM boundary.

This is a metadata-only discovery step. It does not download or accept imagery.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import requests

COURSE_NAME = "Lake Arlington Golf Course"
OSM_WAY_ID = 412547642
OSM_API = f"https://api.openstreetmap.org/api/0.6/way/{OSM_WAY_ID}/full.json"
STAC_SEARCH = "https://planetarycomputer.microsoft.com/api/stac/v1/search"
COLLECTION = "naip"
OUT = Path("artifacts/lake_arlington_naip_candidates.json")
HEADERS = {
    "User-Agent": "WatchThisDrive-course-imagery-research/1.0 (open-source pilot metadata discovery)"
}


def fetch_course_polygon() -> dict:
    response = requests.get(OSM_API, headers=HEADERS, timeout=45)
    response.raise_for_status()
    payload = response.json()
    nodes = {
        element["id"]: [element["lon"], element["lat"]]
        for element in payload.get("elements", [])
        if element.get("type") == "node" and "lon" in element and "lat" in element
    }
    ways = [
        element for element in payload.get("elements", [])
        if element.get("type") == "way" and element.get("id") == OSM_WAY_ID
    ]
    if not ways:
        raise RuntimeError(f"OSM way {OSM_WAY_ID} was not returned")
    refs = ways[0].get("nodes", [])
    ring = [nodes[node_id] for node_id in refs if node_id in nodes]
    if len(ring) < 4:
        raise RuntimeError(f"OSM way {OSM_WAY_ID} did not yield a closed polygon")
    if ring[0] != ring[-1]:
        ring.append(ring[0])
    return {
        "type": "Polygon",
        "coordinates": [ring],
    }


def search_items(polygon: dict) -> list[dict]:
    body = {
        "collections": [COLLECTION],
        "intersects": polygon,
        "limit": 100,
    }
    url = STAC_SEARCH
    params = None
    items: list[dict] = []
    while url:
        response = requests.post(
            url, json=body if params is None else None,
            params=params, headers=HEADERS, timeout=90
        ) if params is None else requests.get(url, params=params, headers=HEADERS, timeout=90)
        response.raise_for_status()
        page = response.json()
        items.extend(page.get("features", []))
        next_link = next((link for link in page.get("links", []) if link.get("rel") == "next"), None)
        url = next_link.get("href") if next_link else None
        params = None
    return items


def main() -> int:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    polygon = fetch_course_polygon()
    items = search_items(polygon)
    candidates = []
    for item in items:
        props = item.get("properties", {})
        assets = item.get("assets", {})
        candidates.append({
            "id": item.get("id"),
            "collection": item.get("collection"),
            "datetime": props.get("datetime"),
            "naip_year": props.get("naip:year"),
            "gsd_meters_per_pixel": props.get("gsd"),
            "state": props.get("naip:state"),
            "projection_epsg": props.get("proj:epsg"),
            "bbox": item.get("bbox"),
            "assets": {
                key: {
                    "type": asset.get("type"),
                    "roles": asset.get("roles"),
                    "title": asset.get("title"),
                    "href": asset.get("href"),
                }
                for key, asset in assets.items()
            },
            "license": item.get("license"),
            "providers": props.get("providers"),
            "item_links": [
                {"rel": link.get("rel"), "href": link.get("href")}
                for link in item.get("links", [])
                if link.get("rel") in ("self", "collection", "license", "describedby")
            ],
        })
    candidates.sort(key=lambda item: (str(item.get("datetime") or ""), str(item.get("id") or "")), reverse=True)
    output = {
        "status": "MATCHES_FOUND" if candidates else "NO_MATCHES",
        "course": COURSE_NAME,
        "boundary_source": f"OpenStreetMap way {OSM_WAY_ID}",
        "boundary_geojson": polygon,
        "catalog": STAC_SEARCH,
        "collection": COLLECTION,
        "queried_at_utc": datetime.now(timezone.utc).isoformat(),
        "candidate_count": len(candidates),
        "candidates": candidates,
        "acceptance_note": (
            "Metadata discovery only. Verify the returned item footprint covers the complete course, "
            "inspect image quality, confirm provider terms/attribution, and georegister against OSM "
            "before accepting or downloading a pilot asset."
        ),
    }
    OUT.write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(f"Status: {output['status']}")
    print(f"OSM boundary: way {OSM_WAY_ID}")
    print(f"NAIP STAC candidates: {len(candidates)}")
    for candidate in candidates:
        print(
            f"- {candidate['id']} | year={candidate['naip_year']} | "
            f"date={candidate['datetime']} | gsd={candidate['gsd_meters_per_pixel']} m/px | "
            f"EPSG:{candidate['projection_epsg']}"
        )
    print(f"Metadata written to {OUT}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"Imagery metadata discovery failed: {exc}", file=sys.stderr)
        raise
