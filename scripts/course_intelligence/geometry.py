"""Course-independent geographic helpers for course intelligence."""
from __future__ import annotations

from pyproj import CRS, Transformer
from shapely.ops import transform as transform_geometry


def local_metric_transformer(geometries):
    """Build a local azimuthal-equidistant projection centered on input geometry.

    Input geometries must be WGS84 longitude/latitude. A local AEQD projection
    avoids hardcoding a UTM zone and works for courses in either hemisphere.
    """
    geometries = list(geometries)
    if not geometries:
        raise ValueError("At least one geometry is required")
    from shapely.ops import unary_union

    bounds = unary_union(geometries).bounds
    min_x, min_y, max_x, max_y = bounds
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
    return Transformer.from_crs("EPSG:4326", local_crs, always_xy=True).transform


def project_wgs84(geometry, transformer):
    """Project a Shapely geometry from WGS84 using a supplied transformer."""
    return transform_geometry(transformer, geometry)
