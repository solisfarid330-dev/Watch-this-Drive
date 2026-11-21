"""Course-independent geographic helpers for course intelligence."""
from __future__ import annotations

from pyproj import CRS, Transformer
from shapely.ops import transform as transform_geometry, unary_union


def local_metric_transformer_pair(geometries):
    """Return forward and inverse local AEQD transforms for WGS84 geometries."""
    geometries = list(geometries)
    if not geometries:
        raise ValueError("At least one geometry is required")
    min_x, min_y, max_x, max_y = unary_union(geometries).bounds
    if not (-180 <= min_x <= 180 and -180 <= max_x <= 180):
        raise ValueError("Input longitude is outside WGS84 bounds")
    if not (-90 <= min_y <= 90 and -90 <= max_y <= 90):
        raise ValueError("Input latitude is outside WGS84 bounds")
    center_lon = (min_x + max_x) / 2
    center_lat = (min_y + max_y) / 2
    local_crs = CRS.from_proj4(
        f"+proj=aeqd +lat_0={center_lat} +lon_0={center_lon} "
        "+datum=WGS84 +units=m +no_defs"
    )
    return (
        Transformer.from_crs("EPSG:4326", local_crs, always_xy=True).transform,
        Transformer.from_crs(local_crs, "EPSG:4326", always_xy=True).transform,
    )


def local_metric_transformer(geometries):
    """Build a local azimuthal-equidistant projection centered on input geometry."""
    return local_metric_transformer_pair(geometries)[0]


def project_wgs84(geometry, transformer):
    """Project a Shapely geometry from WGS84 using a supplied transformer."""
    return transform_geometry(transformer, geometry)
