#!/usr/bin/env python3
"""Validate vectorized candidate GeoJSON topology with Shapely.

This is a research QA dependency only; it is not part of the Android runtime.
"""
from __future__ import annotations
import argparse,json
from pathlib import Path
from shapely.geometry import shape
from shapely.validation import explain_validity

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--geojson",nargs="+",required=True)
    a=p.parse_args()
    failed=False
    for name in a.geojson:
        path=Path(name);fc=json.loads(path.read_text())
        features=[]
        for f in fc.get("features",[]):
            geom=shape(f["geometry"])
            features.append({
                "geometry_type":geom.geom_type,
                "valid":bool(geom.is_valid),
                "validity_reason":explain_validity(geom),
                "empty":bool(geom.is_empty),
                "area_degrees_squared":float(geom.area),
                "polygon_count":len(geom.geoms) if geom.geom_type=="MultiPolygon" else (1 if geom.geom_type=="Polygon" else 0),
                "hole_count":sum(len(poly.interiors) for poly in ([geom] if geom.geom_type=="Polygon" else list(geom.geoms) if geom.geom_type=="MultiPolygon" else [])),
            })
            if not geom.is_valid or geom.is_empty:failed=True
        report={"source_geojson":str(path),"feature_count":len(features),"features":features,"status":"valid" if all(x["valid"] and not x["empty"] for x in features) else "invalid"}
        out=path.with_name(path.stem+"-topology-validation.json")
        out.write_text(json.dumps(report,indent=2)+"\n")
        print(json.dumps(report,indent=2))
    if failed:raise SystemExit("One or more candidate geometries are invalid; do not promote them.")
if __name__=="__main__":main()
