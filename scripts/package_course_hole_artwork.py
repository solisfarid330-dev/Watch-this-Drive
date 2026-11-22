#!/usr/bin/env python3
"""Validate and package generated hole PNGs as Android course assets.

Expected output:
  app/src/main/assets/course_holes/<osm-element-type>-<osm-id>/manifest.json
  app/src/main/assets/course_holes/<osm-element-type>-<osm-id>/hole_*.png
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import struct
from pathlib import Path


def safe_element_type(value: str) -> str:
    value = value.lower().strip()
    if value not in {"node", "way", "relation"}:
        raise ValueError("OSM element type must be node, way, or relation")
    return value


def validate_png(path: Path) -> tuple[int, int]:
    with path.open("rb") as stream:
        header = stream.read(24)
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError(f"Not a valid PNG image: {path.name}")
    width, height = struct.unpack(">II", header[16:24])
    if width < 300 or height < 600:
        raise ValueError(f"Image dimensions too small for hole artwork: {path.name} ({width}x{height})")
    return width, height


def package_artwork(source: Path, destination: Path, *, element_type: str,
                    element_id: int, course_name: str) -> dict:
    element_type = safe_element_type(element_type)
    if element_id <= 0:
        raise ValueError("OSM element ID must be positive")
    source_manifest = source / "manifest.json"
    if not source_manifest.is_file():
        raise FileNotFoundError(f"Missing renderer manifest: {source_manifest}")
    data = json.loads(source_manifest.read_text(encoding="utf-8"))
    generated = data.get("generated")
    if not isinstance(generated, list) or not generated:
        raise ValueError("Renderer manifest contains no generated hole images")

    key = f"{element_type}-{element_id}"
    target = destination / key
    target.mkdir(parents=True, exist_ok=True)
    packaged = []
    seen_labels = set()
    for item in generated:
        filename = Path(str(item.get("image", ""))).name
        if not filename or filename != item.get("image"):
            raise ValueError("Manifest image paths must be simple filenames")
        image = source / filename
        if not image.is_file():
            raise FileNotFoundError(f"Missing generated image: {image}")
        width, height = validate_png(image)
        label = str(item.get("hole_label") or item.get("candidate_index") or "").strip()
        if not label:
            raise ValueError(f"Missing hole label for {filename}")
        if label in seen_labels:
            raise ValueError(f"Duplicate hole label in generated manifest: {label}")
        seen_labels.add(label)
        shutil.copy2(image, target / filename)
        packaged.append({**item, "image": filename, "width": width, "height": height})

    manifest = {
        "course": course_name,
        "course_element_type": element_type,
        "course_element_id": element_id,
        "renderer": data.get("renderer", "course-independent vector_hole_renderer"),
        "orientation": "tee_bottom_green_top",
        "generated_count": len(packaged),
        "generated": packaged,
        "skipped": data.get("skipped", []),
        "warning": data.get("warning", "Hole assignments are provisional; inspect before treating as surveyed course data."),
    }
    (target / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return {"course_asset_key": key, "generated_count": len(packaged), "destination": str(target)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Directory created by render_course_holes.py")
    parser.add_argument("--destination", default=Path("app/src/main/assets/course_holes"), type=Path)
    parser.add_argument("--element-type", required=True, choices=("node", "way", "relation"))
    parser.add_argument("--element-id", required=True, type=int)
    parser.add_argument("--course-name", required=True)
    args = parser.parse_args()
    result = package_artwork(args.source, args.destination, element_type=args.element_type,
                             element_id=args.element_id, course_name=args.course_name)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
