#!/usr/bin/env python3
"""Collect independent course metadata and NAIP imagery for a generic course audit.

This is a research-only intake tool. It does not modify Android course geometry.
OpenGolfAPI records are preserved separately from OSM features, and NAIP imagery
is saved as source evidence for later segmentation/validation.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

OPEN_GOLF_API = "https://api.opengolfapi.org/v1"
NAIP_IMAGE_SERVER = (
    "https://imagery.geographic.texas.gov/server/rest/services/"
    "NAIP/NAIP22_NCCIR_60cm/ImageServer"
)
USER_AGENT = "WatchThisDriveResearch/1.0 (course data audit)"


class SourceError(RuntimeError):
    pass


def get_json(url: str, timeout: int = 30) -> dict:
    request = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise SourceError(f"Request failed for {url}: {exc}") from exc


def get_bytes(url: str, timeout: int = 90) -> bytes:
    request = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, "Accept": "image/tiff,*/*"}
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read()
            if not payload:
                raise SourceError("NAIP service returned an empty image")
            return payload
    except (urllib.error.URLError, TimeoutError) as exc:
        raise SourceError(f"Imagery request failed: {exc}") from exc


def _records(payload):
    if isinstance(payload, list):
        return payload
    for key in ("courses", "results", "data", "items"):
        value = payload.get(key) if isinstance(payload, dict) else None
        if isinstance(value, list):
            return value
    return []


def _course_name(record):
    return str(record.get("name") or record.get("course_name") or "").strip()


def _coordinates(record):
    lat = record.get("latitude", record.get("lat"))
    lon = record.get("longitude", record.get("lon", record.get("lng")))
    try:
        lat, lon = float(lat), float(lon)
    except (TypeError, ValueError):
        return None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None
    return lat, lon


def _distance_m(a, b):
    lat1, lon1 = map(math.radians, a)
    lat2, lon2 = map(math.radians, b)
    dlat, dlon = lat2 - lat1, lon2 - lon1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 6371008.8 * 2 * math.asin(math.sqrt(h))


def choose_course(records, requested_name, reference=None):
    if not records:
        raise SourceError("OpenGolfAPI returned no course candidates")
    target = " ".join(requested_name.casefold().split())
    ranked = []
    for record in records:
        name = _course_name(record)
        normalized = " ".join(name.casefold().split())
        name_penalty = 0 if normalized == target else (1 if target in normalized or normalized in target else 2)
        coords = _coordinates(record)
        distance = _distance_m(reference, coords) if reference and coords else float("inf")
        ranked.append((name_penalty, distance, record))
    ranked.sort(key=lambda item: (item[0], item[1]))
    return ranked[0][2], ranked[0][1]


def collect_open_golf(course_name, state=None, reference=None):
    params = {"q": course_name, "limit": "10"}
    if state:
        params["state"] = state.upper()
    search_url = f"{OPEN_GOLF_API}/courses/search?{urllib.parse.urlencode(params)}"
    search_payload = get_json(search_url)
    candidates = _records(search_payload)
    selected, distance = choose_course(candidates, course_name, reference)
    course_id = selected.get("id") or selected.get("course_id")
    if not course_id:
        raise SourceError("OpenGolfAPI candidate did not include a course ID")

    encoded_id = urllib.parse.quote(str(course_id), safe="")
    detail = get_json(f"{OPEN_GOLF_API}/courses/{encoded_id}")
    holes = get_json(f"{OPEN_GOLF_API}/courses/{encoded_id}/holes")
    tees = get_json(f"{OPEN_GOLF_API}/courses/{encoded_id}/tees")
    return {
        "source": "OpenGolfAPI",
        "attribution": "© OpenStreetMap contributors (ODbL 1.0) via OpenGolfAPI",
        "license": "ODbL-1.0",
        "search_url": search_url,
        "candidate_count": len(candidates),
        "selected_candidate": selected,
        "distance_from_reference_meters": None if not math.isfinite(distance) else round(distance, 1),
        "course_detail": detail,
        "holes": holes,
        "tees": tees,
    }


def naip_export_url(latitude, longitude, radius_m=1200, image_size=2048):
    # Texas service is published in Web Mercator. Convert a local radius to
    # approximate EPSG:3857 metres; latitude is used only for the cosine scale.
    lat_rad = math.radians(latitude)
    x = 6378137.0 * math.radians(longitude)
    y = 6378137.0 * math.log(math.tan(math.pi / 4 + lat_rad / 2))
    projected_radius = radius_m / max(math.cos(lat_rad), 0.2)
    bbox = f"{x-projected_radius},{y-projected_radius},{x+projected_radius},{y+projected_radius}"
    params = {
        "bbox": bbox,
        "bboxSR": "3857",
        "imageSR": "3857",
        "size": f"{image_size},{image_size}",
        "format": "tiff",
        "pixelType": "U8",
        "interpolation": "RSP_Bilinear",
        "f": "image",
    }
    return f"{NAIP_IMAGE_SERVER}/exportImage?{urllib.parse.urlencode(params)}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--course", required=True)
    parser.add_argument("--state", help="Two-letter state code, e.g. TX")
    parser.add_argument("--latitude", type=float, help="Optional OSM/reference latitude")
    parser.add_argument("--longitude", type=float, help="Optional OSM/reference longitude")
    parser.add_argument("--naip", action="store_true", help="Fetch a 4-band Texas NAIP image around the selected course")
    parser.add_argument("--radius-meters", type=int, default=1200)
    parser.add_argument("--out", default="output/multi-source-course")
    args = parser.parse_args()

    if (args.latitude is None) != (args.longitude is None):
        parser.error("--latitude and --longitude must be supplied together")
    reference = (args.latitude, args.longitude) if args.latitude is not None else None
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    audit = collect_open_golf(args.course, args.state, reference)
    (out / "opengolfapi-audit.json").write_text(json.dumps(audit, indent=2), encoding="utf-8")
    print(f"OpenGolfAPI match: {_course_name(audit['selected_candidate'])}")
    print(f"Candidates: {audit['candidate_count']}")
    print(f"Attribution: {audit['attribution']}")

    coordinates = _coordinates(audit["selected_candidate"]) or _coordinates(audit["course_detail"])
    if args.naip:
        if not coordinates:
            raise SourceError("Selected OpenGolfAPI record has no usable coordinates for NAIP imagery")
        url = naip_export_url(*coordinates, radius_m=args.radius_meters)
        image = get_bytes(url)
        (out / "naip-rgbir.tif").write_bytes(image)
        metadata = {
            "source": "Texas Geographic Information Office / USDA NAIP 2022",
            "service": NAIP_IMAGE_SERVER,
            "attribution": "Texas Geographic Information Office, USDA",
            "resolution_meters": 0.6,
            "bands": ["red", "green", "blue", "infrared"],
            "center_latitude": coordinates[0],
            "center_longitude": coordinates[1],
            "radius_meters": args.radius_meters,
            "request_url": url,
            "retrieved_unix_time": int(time.time()),
            "warning": "Imagery is source evidence; segmentation candidates require independent validation before promotion to course geometry.",
        }
        (out / "naip-metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        print(f"Saved NAIP RGBIR image: {out / 'naip-rgbir.tif'}")


if __name__ == "__main__":
    try:
        main()
    except SourceError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(2)
