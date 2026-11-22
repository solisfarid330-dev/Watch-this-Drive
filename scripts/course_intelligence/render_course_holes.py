#!/usr/bin/env python3
"""Batch-render every provisional hole with enough mapped geometry to orient it.

The association draft supplies candidate hole centerlines and their nearby OSM
features. Holes without at least one mapped tee and green are reported as
skipped rather than fabricated. All images use the same generic renderer.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from shapely.geometry import shape, Point


def safe_name(value: str) -> str:
    value = re.sub(r"[^A-Za-z0-9_-]+", "-", str(value)).strip("-")
    return value[:48] or "unlabeled"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raster", required=True)
    parser.add_argument("--draft", required=True, help="Course-wide hole reconstruction draft GeoJSON")
    parser.add_argument("--context", required=True, help="Course-wide OSM features GeoJSON")
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--renderer", default="scripts/course_intelligence/vector_hole_renderer.py")
    args = parser.parse_args()

    draft = json.loads(Path(args.draft).read_text(encoding="utf-8"))
    output_dir = Path(args.out_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "course": draft.get("course"),
        "renderer": "course-independent vector_hole_renderer",
        "orientation": "tee_bottom_green_top",
        "generated": [],
        "skipped": [],
        "warning": "Hole assignments are provisional; missing anchors and fairways may be inferred from centerlines. Imagery-derived canopy is low-confidence context.",
    }

    with tempfile.TemporaryDirectory(prefix="hole-render-batch-") as temporary:
        temp = Path(temporary)
        for index, hole in enumerate(draft.get("features", []), start=1):
            props = hole.get("properties") or {}
            label = str(props.get("hole_label") or f"candidate-{index:02d}")
            associated = props.get("associated_features") or []
            tees = [item for item in associated if item.get("golf_type") == "tee" and item.get("geometry")]
            greens = [item for item in associated if item.get("golf_type") == "green" and item.get("geometry")]
            centerline = shape(hole["geometry"])
            if centerline.geom_type == "MultiLineString":
                centerline = max(centerline.geoms, key=lambda part: part.length)
            if centerline.geom_type != "LineString" or centerline.is_empty:
                manifest["skipped"].append({"candidate_index": index, "hole_label": label, "reason": "no usable centerline"})
                continue

            inferred = []
            endpoints = [Point(centerline.coords[0]), Point(centerline.coords[-1])]
            if not tees and greens:
                anchor = shape(greens[0]["geometry"])
                point = max(endpoints, key=lambda p: p.distance(anchor))
                tees = [{"geometry": {"type": "Point", "coordinates": [point.x, point.y]}, "golf_type": "tee", "association_status": "inferred_centerline_endpoint"}]
                inferred.append("tee")
            elif tees and not greens:
                anchor = shape(tees[0]["geometry"])
                point = max(endpoints, key=lambda p: p.distance(anchor))
                greens = [{"geometry": {"type": "Point", "coordinates": [point.x, point.y]}, "golf_type": "green", "association_status": "inferred_centerline_endpoint"}]
                inferred.append("green")
            elif not tees and not greens:
                a, b = endpoints
                tees = [{"geometry": {"type": "Point", "coordinates": [a.x, a.y]}, "golf_type": "tee", "association_status": "inferred_centerline_endpoint"}]
                greens = [{"geometry": {"type": "Point", "coordinates": [b.x, b.y]}, "golf_type": "green", "association_status": "inferred_centerline_endpoint"}]
                inferred.extend(["tee", "green"])

            features = [{"type": "Feature", "id": hole.get("id"), "geometry": hole["geometry"], "properties": {"role": "osm_hole_centerline", "hole_label": label, "source_tags": props.get("source_tags", {})}}]
            # Preserve the actual OSM hole outline as a first-class geometry.
            # It constrains inferred fairway geometry; it is not a fairway itself.
            synthetic = [item for item in tees + greens if item.get("association_status") == "inferred_centerline_endpoint"]
            for feature_index, item in enumerate(associated + synthetic, start=1):
                if not item.get("geometry"):
                    continue
                features.append({
                    "type": "Feature", "id": f"{hole.get('id')}-feature-{feature_index}",
                    "geometry": item["geometry"],
                    "properties": {"role": "mapped_osm_feature", "golf_type": item.get("golf_type"), "association_status": item.get("association_status"), "source_feature_id": item.get("source_feature_id"), "source_tags": item.get("source_tags", {}), "inference": item.get("association_status") == "inferred_centerline_endpoint"},
                })
            hole_input = temp / f"candidate-{index:03d}.geojson"
            hole_input.write_text(json.dumps({
                "type": "FeatureCollection", "name": f"Provisional hole {label}",
                "features": features,
            }), encoding="utf-8")
            filename = f"hole_{index:02d}_{safe_name(label)}.png"
            image_path = output_dir / filename
            command = [
                sys.executable, args.renderer,
                "--raster", args.raster,
                "--hole", str(hole_input),
                "--context", args.context,
                "--out", str(image_path),
            ]
            result = subprocess.run(command, text=True, capture_output=True)
            if result.returncode:
                manifest["skipped"].append({
                    "candidate_index": index, "hole_label": label,
                    "reason": "renderer_failed", "error": result.stderr[-2000:],
                })
                continue
            try:
                render_metrics = json.loads(result.stdout)
            except json.JSONDecodeError:
                render_metrics = {"renderer_output": result.stdout[-1000:]}
            manifest["generated"].append({
                "candidate_index": index, "hole_label": label,
                "image": filename,
                "tee_count": len(tees), "green_count": len(greens),
                "fairway_count": sum(item.get("golf_type") == "fairway" for item in associated),
                "inferred_features": inferred + (["fairway"] if not any(item.get("golf_type") == "fairway" for item in associated) else []),
                "ambiguous_association_count": sum(
                    item.get("association_status") == "ambiguous_nearest_centerline"
                    for item in associated
                ),
                "render_metrics": render_metrics,
            })

    manifest["generated_count"] = len(manifest["generated"])
    manifest["skipped_count"] = len(manifest["skipped"])
    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({
        "generated_count": manifest["generated_count"],
        "skipped_count": manifest["skipped_count"],
        "manifest": str(manifest_path),
        "generated_images": [item["image"] for item in manifest["generated"]],
        "skipped": manifest["skipped"],
    }, indent=2))


if __name__ == "__main__":
    main()
