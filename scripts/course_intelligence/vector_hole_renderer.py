#!/usr/bin/env python3
"""Render a geographically grounded portrait vector-style golf-hole illustration.

Inputs: a single-hole GeoJSON review feature collection, a georeferenced
four-band RGB+NIR raster, and course-wide OSM golf features. Mapped active-hole
geometry is emphasized; surrounding mapped features and low-confidence,
imagery-derived tree canopy provide context.

The renderer is course- and hole-number-independent. It does not claim to
recognize missing golf features as ground truth.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
import rasterio
from rasterio.features import shapes
from scipy import ndimage
from shapely.geometry import (
    GeometryCollection, LineString, MultiPolygon, Point, Polygon, box, shape,
)
from shapely.ops import transform as shapely_transform
from pyproj import Transformer
from PIL import Image, ImageDraw, ImageFilter


WIDTH, HEIGHT = 760, 1640
COLORS = {
    "base": (151, 181, 112),
    "fairway_edge": (99, 151, 92),
    "fairway": (128, 184, 112),
    "green_edge": (47, 117, 77),
    "green": (112, 181, 116),
    "tee_edge": (49, 112, 75),
    "tee": (133, 190, 130),
    "tree": (31, 83, 57),
    "water": (76, 153, 174),
    "sand": (218, 197, 151),
    "path": (220, 205, 164),
    "ink": (40, 89, 61),
}


def polygon_parts(geometry):
    if geometry is None or geometry.is_empty:
        return []
    if isinstance(geometry, Polygon):
        return [geometry]
    if isinstance(geometry, (MultiPolygon, GeometryCollection)):
        result = []
        for child in geometry.geoms:
            result.extend(polygon_parts(child))
        return result
    return []


def line_parts(geometry):
    if geometry is None or geometry.is_empty:
        return []
    if isinstance(geometry, LineString):
        return [geometry]
    if isinstance(geometry, GeometryCollection):
        return [child for child in geometry.geoms if isinstance(child, LineString)]
    return []


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raster", required=True, help="Georeferenced four-band RGB+NIR raster")
    parser.add_argument("--hole", required=True, help="Single-hole GeoJSON review feature collection")
    parser.add_argument("--context", required=True, help="Course-wide OSM golf-feature GeoJSON")
    parser.add_argument("--out", required=True, help="Output PNG path")
    parser.add_argument("--hole-number", help="Optional hole label to select from the review file")
    args = parser.parse_args()

    review = json.loads(Path(args.hole).read_text(encoding="utf-8"))
    features = review.get("features", [])
    centerline_features = [
        feature for feature in features
        if feature.get("properties", {}).get("role") == "osm_hole_centerline"
    ]
    if args.hole_number:
        centerline_features = [
            feature for feature in centerline_features
            if str(feature.get("properties", {}).get("hole_label")) == str(args.hole_number)
        ]
    if len(centerline_features) != 1:
        raise ValueError("Hole review must identify exactly one centerline (use --hole-number if needed)")
    centerline = centerline_features[0]
    hole_label = centerline.get("properties", {}).get("hole_label", "—")
    tees = [f for f in features if f.get("properties", {}).get("golf_type") == "tee"]
    greens = [f for f in features if f.get("properties", {}).get("golf_type") == "green"]
    fairways = [f for f in features if f.get("properties", {}).get("golf_type") == "fairway"]
    if not tees or not greens:
        raise ValueError("Selected hole requires mapped tee and green geometries")

    with rasterio.open(args.raster) as dataset:
        if dataset.count < 4:
            raise ValueError("Expected four-band RGB+NIR imagery")
        source_crs = dataset.crs
        to_source = Transformer.from_crs("EPSG:4326", source_crs, always_xy=True).transform

        centerline_geometry = shapely_transform(to_source, shape(centerline["geometry"]))
        tee_geometries = [
            shapely_transform(to_source, shape(feature["geometry"])) for feature in tees
        ]
        green_geometries = [
            shapely_transform(to_source, shape(feature["geometry"])) for feature in greens
        ]
        tee_point = Point(
            np.mean([geometry.centroid.x for geometry in tee_geometries]),
            np.mean([geometry.centroid.y for geometry in tee_geometries]),
        )
        green_point = Point(
            np.mean([geometry.centroid.x for geometry in green_geometries]),
            np.mean([geometry.centroid.y for geometry in green_geometries]),
        )

        # Rotate geographic coordinates so tee-to-green always points upward.
        direction = math.atan2(
            green_point.y - tee_point.y, green_point.x - tee_point.x
        )
        rotation = math.pi / 2 - direction
        cosine, sine = math.cos(rotation), math.sin(rotation)
        origin_x, origin_y = tee_point.x, tee_point.y

        def rotate_xy(x, y):
            x, y = x - origin_x, y - origin_y
            return x * cosine - y * sine, x * sine + y * cosine

        def local_geometry(geometry):
            return shapely_transform(
                lambda x, y, z=None: rotate_xy(np.asarray(x), np.asarray(y)),
                geometry,
            )

        local_centerline = local_geometry(centerline_geometry)
        local_tees = [local_geometry(geometry) for geometry in tee_geometries]
        local_greens = [local_geometry(geometry) for geometry in green_geometries]
        local_fairways = [
            local_geometry(shapely_transform(to_source, shape(feature["geometry"])))
            for feature in fairways
        ]
        inferred_fairway = not local_fairways
        if inferred_fairway:
            local_fairways = [local_centerline.buffer(17.0, cap_style=1, join_style=1)]

        # Portrait crop: enough lateral context to show neighboring landscape.
        _, line_min_y, _, line_max_y = local_centerline.bounds
        min_y = min(-38.0, min(g.bounds[1] for g in local_tees) - 18.0)
        max_y = max(
            line_max_y + 42.0,
            max(g.bounds[3] for g in local_greens) + 20.0,
        )
        half_width = 125.0
        min_x, max_x = -half_width, half_width
        scale = min((WIDTH - 56) / (max_x - min_x), (HEIGHT - 64) / (max_y - min_y))
        used_width, used_height = (max_x - min_x) * scale, (max_y - min_y) * scale
        left, top = (WIDTH - used_width) / 2, (HEIGHT - used_height) / 2

        def pixel(point):
            x, y = point
            return (
                left + (x - min_x) * scale,
                HEIGHT - (top + (y - min_y) * scale),
            )

        def draw_polygon(draw, geometry, fill, outline=None, outline_width=1):
            for polygon in polygon_parts(geometry):
                points = [pixel(point) for point in polygon.exterior.coords]
                if len(points) >= 3:
                    draw.polygon(points, fill=fill)
                    if outline:
                        draw.line(
                            points + [points[0]], fill=outline,
                            width=outline_width, joint="curve",
                        )
                for interior in polygon.interiors:
                    draw.polygon([pixel(point) for point in interior.coords], fill=COLORS["base"])

        image = Image.new("RGB", (WIDTH, HEIGHT), COLORS["base"])
        draw = ImageDraw.Draw(image)

        # Approximate tree-canopy footprints from dark, vegetated NAIP regions.
        # These are deliberately contextual, low-confidence features.
        bands = dataset.read([1, 2, 3, 4]).astype(np.float32)
        red, green, blue, nir = bands
        ndvi = np.divide(
            nir - red, nir + red,
            out=np.zeros_like(red), where=(nir + red) > 2,
        )
        brightness = (red + green + blue) / 3
        valid = (red + green + blue) > 18
        canopy = (
            valid & (ndvi > 0.28) & (green > red * 1.06)
            & (green > blue * 1.03) & (brightness < 137)
        )
        canopy = ndimage.binary_closing(canopy, iterations=2)
        canopy = ndimage.binary_opening(canopy, iterations=1)
        labels, component_count = ndimage.label(canopy)
        sizes = np.bincount(labels.ravel())
        retained = np.zeros_like(canopy)
        for component in range(1, component_count + 1):
            if 45 <= sizes[component] <= 180000:
                retained[labels == component] = True

        for geometry_mapping, _ in shapes(
            retained.astype(np.uint8), mask=retained,
            transform=dataset.transform, connectivity=8,
        ):
            try:
                canopy_geometry = local_geometry(shape(geometry_mapping))
                if canopy_geometry.intersects(box(min_x, min_y, max_x, max_y)):
                    draw_polygon(draw, canopy_geometry, COLORS["tree"])
            except Exception:
                continue

        # Course-wide context: neighboring fairways/greens, water, bunkers and paths.
        context = json.loads(Path(args.context).read_text(encoding="utf-8"))
        context_count = 0
        crop = box(min_x, min_y, max_x, max_y)
        for feature in context.get("features", []):
            properties = feature.get("properties", {})
            tags = properties.get("tags", {})
            kind = properties.get("golf") or tags.get("golf")
            if kind not in {
                "fairway", "green", "bunker", "water_hazard",
                "lateral_water_hazard", "path", "cartpath", "tee",
            }:
                continue
            try:
                geometry = local_geometry(
                    shapely_transform(to_source, shape(feature["geometry"]))
                )
            except Exception:
                continue
            if not geometry.intersects(crop):
                continue
            context_count += 1
            if kind in {"water_hazard", "lateral_water_hazard"}:
                draw_polygon(draw, geometry, COLORS["water"], (54, 126, 153), 2)
            elif kind == "bunker":
                draw_polygon(draw, geometry, COLORS["sand"], (194, 174, 131), 2)
            elif kind == "path":
                for line in line_parts(geometry):
                    draw.line(
                        [pixel(point) for point in line.coords],
                        fill=COLORS["path"], width=max(3, int(scale * 2.4)),
                        joint="curve",
                    )
            elif kind == "fairway":
                draw_polygon(draw, geometry, (145, 181, 111))
            elif kind == "green":
                draw_polygon(draw, geometry, (115, 174, 112))

        # Emphasize the active hole's actual mapped fairway polygon(s).
        for geometry in local_fairways:
            edge_color = (117, 143, 91) if inferred_fairway else COLORS["fairway_edge"]
            fill_color = (151, 180, 111) if inferred_fairway else COLORS["fairway"]
            draw_polygon(draw, geometry, edge_color)
            inset = geometry.buffer(-2.4)
            if not inset.is_empty:
                draw_polygon(draw, inset, fill_color)

        # Green at top, tee boxes at bottom, irrespective of compass direction.
        for geometry in local_greens:
            if geometry.geom_type == "Point":
                gx, gy = pixel((geometry.x, geometry.y))
                draw.ellipse((gx - 23, gy - 18, gx + 23, gy + 18), fill=(111, 169, 105), outline=COLORS["green_edge"], width=3)
            else:
                draw_polygon(draw, geometry, COLORS["green_edge"])
                inset = geometry.buffer(-1.2)
                if not inset.is_empty:
                    draw_polygon(draw, inset, COLORS["green"])
            flag_point = geometry.representative_point()
            flag_x, flag_y = pixel((flag_point.x, flag_point.y))
            radius = 9
            draw.ellipse(
                (flag_x - radius, flag_y - radius, flag_x + radius, flag_y + radius),
                fill=(247, 239, 205), outline=COLORS["ink"], width=3,
            )
            draw.line((flag_x, flag_y - 10, flag_x, flag_y - 55), fill=(247, 247, 225), width=5)
            draw.polygon(
                [(flag_x, flag_y - 55), (flag_x + 33, flag_y - 46),
                 (flag_x, flag_y - 37)],
                fill=(218, 75, 61),
            )

        for geometry in local_tees:
            draw_polygon(draw, geometry, COLORS["tee_edge"])
            inset = geometry.buffer(-0.7)
            if not inset.is_empty:
                draw_polygon(draw, inset, COLORS["tee"])
            tee_point = geometry.representative_point()
            tee_x, tee_y = pixel((tee_point.x, tee_point.y))
            radius = 8
            draw.ellipse(
                (tee_x - radius, tee_y - radius, tee_x + radius, tee_y + radius),
                fill=(249, 242, 209), outline=COLORS["ink"], width=3,
            )

        image = image.filter(ImageFilter.GaussianBlur(0.12))
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        image.save(args.out)
        print(json.dumps({
            "output": args.out,
            "hole": hole_label,
            "orientation": "tee_bottom_green_top",
            "canvas": [WIDTH, HEIGHT],
            "mapped_tees": len(tees),
            "mapped_greens": len(greens),
            "mapped_fairway_features": len(fairways),
            "fairway_geometry": "mapped" if not inferred_fairway else "inferred_centerline_buffer_17m",
            "imagery_derived_canopy": "low_confidence_context_only",
            "course_context_features_in_crop": context_count,
            "limitations": [
                "Tree-canopy segmentation is approximate and imagery-vintage dependent.",
                "Only mapped active-hole geometry is emphasized as the selected hole.",
                "Other course features are contextual and may include neighboring holes.",
                "Missing golf features are not promoted to verified geometry.",
            ],
        }, indent=2))


if __name__ == "__main__":
    main()
