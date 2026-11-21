#!/usr/bin/env python3
"""Lake Arlington fixture runner for the reusable course-intelligence engine.

This file contains pilot input/output paths only. Geometry projection and
feature-to-hole association live in scripts/course_intelligence.
"""
from __future__ import annotations

import json
from pathlib import Path

from shapely.geometry import shape

from scripts.course_intelligence.association import associate_features_to_holes

ROOT = Path("artifacts")
INPUT = ROOT / "lake_arlington_osm_golf_features.geojson"
OUTPUT = ROOT / "lake_arlington_hole_reconstruction_draft.geojson"


def main() -> None:
    source = json.loads(INPUT.read_text(encoding="utf-8"))
    features = []
    for feature in source.get("features", []):
        if not feature.get("geometry"):
            continue
        item = dict(feature)
        item["_shape"] = shape(feature["geometry"])
        features.append(item)

    holes, summary = associate_features_to_holes(features)
    output = {
        "type": "FeatureCollection",
        "name": "Lake Arlington provisional hole reconstruction draft",
        "course": "Lake Arlington Golf Course",
        "crs": "EPSG:4326",
        "status": "draft_requires_visual_review",
        "method": {
            "description": "Course-independent nearest-centerline association with ambiguity reporting.",
            "projection": "Local azimuthal-equidistant metric projection derived from input geometry bounds.",
            "imagery_used_for_geometry": False,
            "missing_geometry_inferred": False,
        },
        "summary": summary,
        "features": holes,
    }
    OUTPUT.write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
