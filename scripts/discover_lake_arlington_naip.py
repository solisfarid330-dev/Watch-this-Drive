#!/usr/bin/env python3
"""Discover NAIP STAC items intersecting the Lake Arlington OSM boundary.

This is a metadata-only discovery step. It does not download or accept imagery.
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import requests
from shapely.geometry import box, shape
from shapely.ops import unary_union

COURSE_NAME = "Lake Arlington Golf Course"
OSM_WAY_ID = 412547642
OSM_API = f"https://api.openstreetmap.org/api/0.6/way/{OSM_WAY_ID}/full.json"
STAC_ROOT = "https://planetarycomputer.microsoft.com/api/stac/v1"
STAC_SEARCH = f"{STAC_ROOT}/search"
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
    method = "POST"
    next_body = body
    while url:
        response = requests.request(
            method, url, json=next_body if method == "POST" else None,
            headers=HEADERS, timeout=90
        )
        response.raise_for_status()
        page = response.json()
        items.extend(page.get("features", []))
        next_link = next((link for link in page.get("links", []) if link.get("rel") == "next"), None)
        if next_link:
            method = next_link.get("method", "GET").upper()
            url = next_link.get("href")
            next_body = next_link.get("body") if method == "POST" else None
        else:
            url = None
    return items


def fetch_collection_metadata() -> dict:
    response = requests.get(f"{STAC_ROOT}/collections/{COLLECTION}", headers=HEADERS, timeout=45)
    response.raise_for_status()
    collection = response.json()
    return {
        "id": collection.get("id"),
        "title": collection.get("title"),
        "description": collection.get("description"),
        "license": collection.get("license"),
        "providers": collection.get("providers"),
        "extent": collection.get("extent"),
        "links": [
            {"rel": link.get("rel"), "href": link.get("href"), "title": link.get("title")}
            for link in collection.get("links", [])
            if link.get("rel") in ("license", "about", "describedby", "root")
        ],
    }


def main() -> int:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    polygon = fetch_course_polygon()
    collection_metadata = fetch_collection_metadata()
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
            "projection_shape": props.get("proj:shape"),
            "projection_transform": props.get("proj:transform"),
            "raster_bands": props.get("raster:bands"),
            "eo_bands": props.get("eo:bands"),
            "bbox": item.get("bbox"),
            "geometry": item.get("geometry"),
            "assets": {
                key: {
                    "type": asset.get("type"),
                    "roles": asset.get("roles"),
                    "title": asset.get("title"),
                    "gsd": asset.get("gsd"),
                    "eo:bands": asset.get("eo:bands"),
                    "raster:bands": asset.get("raster:bands"),
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

    # Estimate coverage by vintage using STAC tile footprints.
    course_shape = shape(polygon)
    coverage_by_vintage = []
    years = sorted({str(item.get("naip_year")) for item in candidates if item.get("naip_year")}, reverse=True)
    for year in years:
        vintage_items = [item for item in candidates if str(item.get("naip_year")) == year]
        footprints = [
            shape(item["geometry"]) if item.get("geometry") else box(*item["bbox"])
            for item in vintage_items if item.get("geometry") or item.get("bbox")
        ]
        if footprints:
            covered = course_shape.intersection(unary_union(footprints))
            coverage_by_vintage.append({
                "year": year,
                "item_count": len(vintage_items),
                "course_coverage_percent": round(100 * covered.area / course_shape.area, 4),
                "footprint_method": "STAC item geometry" if all(item.get("geometry") for item in vintage_items) else "STAC geometry/bbox fallback",
                "item_ids": [item["id"] for item in vintage_items],
                "dates": sorted({item.get("datetime") for item in vintage_items if item.get("datetime")}),
                "gsd_meters_per_pixel": sorted({item.get("gsd_meters_per_pixel") for item in vintage_items if item.get("gsd_meters_per_pixel")}),
            })

    # Download low-resolution previews for visual triage, not as accepted imagery assets.
    preview_dir = OUT.parent / "previews"
    preview_dir.mkdir(parents=True, exist_ok=True)
    newest_year = years[0] if years else None
    preview_results = []
    for item in candidates:
        if str(item.get("naip_year")) != newest_year:
            continue
        preview_asset = next(
            (asset for asset in item.get("assets", {}).values() if "overview" in (asset.get("roles") or [])),
            None,
        )
        if not preview_asset:
            continue
        preview_response = requests.get(preview_asset["href"], headers=HEADERS, timeout=90)
        preview_response.raise_for_status()
        preview_path = preview_dir / f"{item['id']}.png"
        preview_path.write_bytes(preview_response.content)
        preview_results.append({"item_id": item["id"], "file": str(preview_path)})

    output = {
        "status": "MATCHES_FOUND" if candidates else "NO_MATCHES",
        "course": COURSE_NAME,
        "boundary_source": f"OpenStreetMap way {OSM_WAY_ID}",
        "boundary_geojson": polygon,
        "catalog": STAC_SEARCH,
        "collection": COLLECTION,
        "collection_metadata": collection_metadata,
        "queried_at_utc": datetime.now(timezone.utc).isoformat(),
        "candidate_count": len(candidates),
        "vintage_coverage": coverage_by_vintage,
        "newest_vintage_previews": preview_results,
        "license_review_required": collection_metadata.get("license") not in ("public-domain", "CC0-1.0") or not collection_metadata.get("license"),
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
    for vintage in coverage_by_vintage:
        print(f"- Vintage {vintage['year']}: {vintage['course_coverage_percent']}% course footprint coverage")
    print(f"Newest vintage previews saved: {len(preview_results)}")
    print(f"Collection license label: {collection_metadata.get('license')} (verify license links)")
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
