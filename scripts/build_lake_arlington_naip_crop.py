#!/usr/bin/env python3
"""Build a georeferenced Lake Arlington course crop from the newest NAIP COG tiles."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import rasterio
from PIL import Image, ImageDraw
from rasterio.features import geometry_mask
from rasterio.merge import merge
from rasterio.warp import transform_geom
from shapely.geometry import shape, mapping

ROOT = Path("artifacts")
CATALOG = ROOT / "lake_arlington_naip_candidates.json"
OUT_TIF = ROOT / "lake_arlington_naip_2022_course_clip.tif"
OUT_PNG = ROOT / "lake_arlington_naip_2022_course_preview.png"
MANIFEST = ROOT / "lake_arlington_naip_2022_course_manifest.json"


def main() -> None:
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    if data.get("status") != "MATCHES_FOUND":
        raise RuntimeError("NAIP discovery did not return matching imagery")
    years = sorted(
        {str(item.get("naip_year")) for item in data["candidates"] if item.get("naip_year")},
        reverse=True,
    )
    if not years:
        raise RuntimeError("No NAIP vintage metadata found")
    year = years[0]
    selected = [
        item for item in data["candidates"]
        if str(item.get("naip_year")) == year and item.get("assets", {}).get("image", {}).get("href")
    ]
    if not selected:
        raise RuntimeError(f"No image assets found for newest vintage {year}")

    course_wgs84 = shape(data["boundary_geojson"])
    sources = []
    try:
        with rasterio.Env(
            GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR",
            CPL_VSIL_CURL_ALLOWED_EXTENSIONS=".tif",
            GDAL_HTTP_MERGE_CONSECUTIVE_RANGES="YES",
            CPL_VSIL_CURL_CACHE_SIZE="20000000",
        ):
            for item in selected:
                href = item["assets"]["image"]["href"]
                src = rasterio.open("/vsicurl/" + href)
                sources.append(src)

            target_crs = sources[0].crs
            if any(src.crs != target_crs for src in sources):
                raise RuntimeError("Newest-vintage tiles do not share a common CRS")
            course_projected = shape(
                transform_geom("EPSG:4326", target_crs, mapping(course_wgs84), precision=3)
            )
            bounds = course_projected.bounds

            mosaic, out_transform = merge(
                sources,
                bounds=bounds,
                nodata=0,
                dtype="uint8",
            )
            if mosaic.shape[0] < 3:
                raise RuntimeError(f"Expected at least RGB bands, got {mosaic.shape[0]}")
            inside = geometry_mask(
                [mapping(course_projected)],
                out_shape=(mosaic.shape[1], mosaic.shape[2]),
                transform=out_transform,
                invert=True,
            )
            mosaic[:, ~inside] = 0

            profile = sources[0].profile.copy()
            profile.update(
                driver="GTiff",
                height=mosaic.shape[1],
                width=mosaic.shape[2],
                transform=out_transform,
                crs=target_crs,
                count=mosaic.shape[0],
                dtype="uint8",
                nodata=0,
                compress="deflate",
                tiled=True,
            )
            with rasterio.open(OUT_TIF, "w", **profile) as dst:
                dst.write(mosaic)

            rgb = np.moveaxis(mosaic[:3], 0, -1)
            image = Image.fromarray(rgb, mode="RGB")
            image.thumbnail((1800, 1800), Image.Resampling.LANCZOS)
            draw = ImageDraw.Draw(image)
            sx = image.width / mosaic.shape[2]
            sy = image.height / mosaic.shape[1]
            outline = []
            for lon, lat in course_wgs84.exterior.coords:
                from rasterio.warp import transform
                xx, yy = transform("EPSG:4326", target_crs, [lon], [lat])
                row, col = rasterio.transform.rowcol(out_transform, xx[0], yy[0])
                outline.append((col * sx, row * sy))
            if len(outline) >= 3:
                draw.line(outline + [outline[0]], fill=(255, 220, 0), width=3)
            image.save(OUT_PNG, optimize=True)

            manifest = {
                "course": data["course"],
                "boundary_source": data["boundary_source"],
                "imagery_collection": "USDA NAIP",
                "vintage": year,
                "acquisition_dates": sorted({item.get("datetime") for item in selected if item.get("datetime")}),
                "source_item_ids": [item["id"] for item in selected],
                "source_asset_urls": [item["assets"]["image"]["href"] for item in selected],
                "source_gsd_meters_per_pixel": sorted({
                    item.get("gsd_meters_per_pixel") for item in selected
                    if item.get("gsd_meters_per_pixel") is not None
                }),
                "source_band_metadata": [
                    item["assets"]["image"].get("eo:bands") for item in selected
                ],
                "output_crs": str(target_crs),
                "output_dimensions_pixels": [int(mosaic.shape[2]), int(mosaic.shape[1])],
                "output_band_count": int(mosaic.shape[0]),
                "output_transform": list(out_transform)[:6],
                "course_boundary_outline": "yellow line in PNG preview",
                "attribution": "USDA Farm Production and Conservation Business Center, Geospatial Enterprise Operations (FPAC-BC-GEO); USDA Farm Service Agency NAIP",
                "rights_note": (
                    "Official USDA Data.gov NAIP record identifies the dataset as public domain. "
                    "The Microsoft Planetary Computer collection metadata has a conflicting "
                    "license label; retain that discrepancy in provenance."
                ),
                "quality_review": "Full-resolution crop generated; visual QA and source metadata review still required.",
            }
            MANIFEST.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
            print(f"Created {OUT_TIF}")
            print(f"Created {OUT_PNG}")
            print(f"Course crop: {mosaic.shape[2]} x {mosaic.shape[1]} pixels, {mosaic.shape[0]} bands")
            print(f"CRS: {target_crs}; NAIP vintage: {year}")
    finally:
        for src in sources:
            src.close()


if __name__ == "__main__":
    main()
