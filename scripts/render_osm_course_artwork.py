#!/usr/bin/env python3
"""Fetch mapped OSM golf features and render provisional vector hole cards.

This first-pass renderer intentionally needs no aerial raster. It uses mapped
OSM features where present and a clearly provisional centerline buffer otherwise.
"""
from __future__ import annotations
import argparse, json, math, time
from pathlib import Path
from urllib.request import Request, urlopen
from PIL import Image, ImageDraw
from shapely.geometry import shape, LineString, Point, Polygon, MultiPolygon, GeometryCollection
from shapely.ops import transform
from pyproj import Transformer

W, H = 760, 1640
BG = (154, 181, 119)
COLORS = {
    "fairway": (116, 173, 102), "green": (89, 157, 105),
    "tee": (104, 158, 102), "bunker": (221, 202, 161),
    "water": (86, 151, 177), "path": (214, 202, 171),
    "tree": (47, 91, 62), "rough": (133, 161, 102),
    "line": (78, 133, 83), "edge": (63, 116, 75),
}

def get_json(url, query):
    req = Request(url, data=query.encode(), headers={"Content-Type":"application/x-www-form-urlencoded", "User-Agent":"WatchThisDrive/1.0 (course illustration prototype)"})
    with urlopen(req, timeout=90) as response:
        return json.loads(response.read())

def osm_geojson(element):
    tags = element.get("tags", {})
    if element["type"] == "node":
        geom = {"type":"Point", "coordinates":[element["lon"], element["lat"]]}
    else:
        coords = [[p["lon"], p["lat"]] for p in element.get("geometry", [])]
        if len(coords) < 2: return None
        closed = coords[0] == coords[-1]
        is_area = closed and len(coords) >= 4 and (
            tags.get("golf") in {"fairway","green","tee","bunker","hole"} or
            tags.get("natural") in {"water","wood"} or tags.get("landuse") == "forest"
        )
        geom = {"type":"Polygon","coordinates":[coords]} if is_area else {"type":"LineString","coordinates":coords}
    return {"type":"Feature","id":f'{element["type"]}/{element["id"]}',"geometry":geom,"properties":{"tags":tags}}

def parts(g):
    if g.is_empty: return []
    if g.geom_type == "Polygon": return [g]
    if g.geom_type in {"MultiPolygon","GeometryCollection"}:
        return [p for child in g.geoms for p in parts(child)]
    return []

def draw_geom(draw, geom, project, color, width=3):
    if geom is None or geom.is_empty: return
    if geom.geom_type in {"Polygon","MultiPolygon","GeometryCollection"}:
        for poly in parts(geom):
            pts=[project(x,y) for x,y in poly.exterior.coords]
            if len(pts)>2: draw.polygon(pts, fill=color, outline=COLORS["edge"])
            for ring in poly.interiors:
                draw.polygon([project(x,y) for x,y in ring.coords], fill=BG)
    elif geom.geom_type in {"LineString","MultiLineString"}:
        geoms=list(geom.geoms) if geom.geom_type=="MultiLineString" else [geom]
        for line in geoms:
            pts=[project(x,y) for x,y in line.coords]
            if len(pts)>1: draw.line(pts, fill=color, width=width, joint="curve")

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--course-element-type", default="way")
    ap.add_argument("--course-element-id", type=int, required=True)
    ap.add_argument("--course-name", required=True)
    ap.add_argument("--out-dir", default="build/course-hole-artwork")
    args=ap.parse_args()
    if args.course_element_type not in {"way","relation"}: raise ValueError("Course element must be a way or relation")
    course_query=f'[out:json][timeout:60];{args.course_element_type}({args.course_element_id});out geom;'
    base="https://overpass-api.de/api/interpreter"
    course_data=get_json(base,course_query)
    if not course_data.get("elements"): raise RuntimeError("Course OSM element was not found")
    course_el=course_data["elements"][0]
    coords=[[p["lon"],p["lat"]] for p in course_el.get("geometry",[])]
    if len(coords)<4: raise RuntimeError("Course boundary has no usable polygon geometry")
    poly=Polygon(coords)
    if not poly.is_valid: poly=poly.buffer(0)
    minx,miny,maxx,maxy=poly.bounds
    center=poly.centroid
    around_query=f"""[out:json][timeout:120];
(node(around:1200,{center.y},{center.x})[golf];
 way(around:1200,{center.y},{center.x})[golf];
 way(around:1200,{center.y},{center.x})[natural~"water|wood"];
 way(around:1200,{center.y},{center.x})[landuse=forest];);
out geom;"""
    data=get_json(base,around_query)
    features=[f for e in data.get("elements",[]) if (f:=osm_geojson(e))]
    # Build projected geometries in a local metric CRS centered on the course.
    to_m=Transformer.from_crs("EPSG:4326", f"+proj=aeqd +lat_0={center.y} +lon_0={center.x} +datum=WGS84 +units=m +no_defs", always_xy=True).transform
    projected=[]
    for f in features:
        try:
            g=transform(to_m,shape(f["geometry"]))
            tags=f["properties"]["tags"]
            projected.append((f,g,tags))
        except Exception: pass
    course_m=transform(to_m,poly)
    holes=[(f,g,t) for f,g,t in projected if t.get("golf")=="hole" and g.geom_type in {"LineString","MultiLineString"}]
    tees=[(f,g,t) for f,g,t in projected if t.get("golf") in {"tee","teeing_ground"}]
    greens=[(f,g,t) for f,g,t in projected if t.get("golf")=="green"]
    if not holes:
        # Pair nearby mapped tee/green candidates. Do not invent a complete 18-hole set.
        pairs=[]
        for tf,tg,tt in tees:
            for gf,gg,gt in greens:
                d=tg.centroid.distance(gg.centroid)
                if 35 <= d <= 650: pairs.append((d,tf,tg,gf,gg))
        pairs.sort(key=lambda p:p[0])
        used_t=set(); used_g=set(); routes=[]
        for d,tf,tg,gf,gg in pairs:
            if tf["id"] in used_t or gf["id"] in used_g: continue
            used_t.add(tf["id"]); used_g.add(gf["id"])
            routes.append((f"provisional-{len(routes)+1}",LineString([tg.centroid,gg.centroid]),tg,gg,True))
        holes=[]
        for label,line,tee,green,prov in routes:
            holes.append(({"id":label,"properties":{"tags":{"golf":"hole","source":"tee_green_pair_hypothesis"}}},line,{"golf":"hole"}))
    if not holes: raise RuntimeError("No mapped hole centerlines or usable tee/green pairs were found")
    out=Path(args.out_dir); out.mkdir(parents=True,exist_ok=True)
    manifest={"course":args.course_name,"course_element_type":args.course_element_type,"course_element_id":args.course_element_id,"renderer":"geometry-only OSM vector renderer","orientation":"tee_bottom_green_top","generated":[],"skipped":[],"warning":"OSM geometry is approximate. Hole assignments and inferred fairways are provisional; visually verify before relying on them."}
    # order stable by mapped OSM ID; provisional routes retain distance order
    holes.sort(key=lambda item:item[0]["id"])
    for n,(feature,line,tags) in enumerate(holes,1):
        if line.geom_type=="MultiLineString": line=max(line.geoms,key=lambda x:x.length)
        if line.geom_type!="LineString" or line.length<15: continue
        corridor=line.buffer(100)
        near=[(f,g,t) for f,g,t in projected if g.intersects(corridor)]
        tee_candidates=[(f,g,t) for f,g,t in near if t.get("golf") in {"tee","teeing_ground"}]
        green_candidates=[(f,g,t) for f,g,t in near if t.get("golf")=="green"]
        start,end=Point(line.coords[0]),Point(line.coords[-1])
        if tee_candidates:
            tee=min(tee_candidates,key=lambda x:x[1].distance(start) if x[1].distance(start)<x[1].distance(end) else x[1].distance(end))[1].centroid
            if tee.distance(end)<tee.distance(start): line=LineString(list(line.coords)[::-1])
        elif green_candidates:
            green=min(green_candidates,key=lambda x:min(x[1].distance(start),x[1].distance(end)))[1].centroid
            if green.distance(start)<green.distance(end): line=LineString(list(line.coords)[::-1])
        # Active hole plus nearby course context, rotated so line advances upward.
        tee_pt=Point(line.coords[0]); green_pt=Point(line.coords[-1])
        angle=math.pi/2-math.atan2(green_pt.y-tee_pt.y,green_pt.x-tee_pt.x)
        c,s=math.cos(angle),math.sin(angle)
        def rot(x,y):
            dx,dy=x-tee_pt.x,y-tee_pt.y
            return dx*c-dy*s,dx*s+dy*c
        local_line=transform(lambda x,y,z=None:rot(x,y),line)
        local_features=[]
        for f,g,t in near:
            local_features.append((f,transform(lambda x,y,z=None:rot(x,y),g),t))
        # Keep nearby features but center active hole in frame.
        minx2,miny2,maxx2,maxy2=local_line.bounds
        lo_y=min(-45,miny2-25); hi_y=max(220,maxy2+35)
        half=115
        scale=min((W-50)/(2*half),(H-70)/(hi_y-lo_y))
        left=(W-2*half*scale)/2; top=(H-(hi_y-lo_y)*scale)/2
        def px(x,y): return (left+(x+half)*scale,H-(top+(y-lo_y)*scale))
        img=Image.new("RGB",(W,H),BG); d=ImageDraw.Draw(img)
        # background context first
        for f,g,t in local_features:
            golf=t.get("golf","")
            if golf in {"bunker"}: draw_geom(d,g,px,COLORS["bunker"])
            elif golf in {"water_hazard","lateral_water_hazard"} or t.get("natural")=="water": draw_geom(d,g,px,COLORS["water"])
            elif golf in {"fairway","rough"}: draw_geom(d,g,px,COLORS["fairway"] if golf=="fairway" else COLORS["rough"])
            elif golf=="path" or t.get("highway") in {"path","footway"}: draw_geom(d,g,px,COLORS["path"],5)
            elif t.get("natural")=="wood" or t.get("landuse")=="forest": draw_geom(d,g,px,COLORS["tree"])
        # approximate fairway if not mapped
        mapped_fairways=[g for f,g,t in local_features if t.get("golf")=="fairway"]
        if not mapped_fairways:
            draw_geom(d,local_line.buffer(19,cap_style=1,join_style=1),px,COLORS["fairway"])
        # tees and greens, plus line
        for f,g,t in local_features:
            if t.get("golf")=="green": draw_geom(d,g,px,COLORS["green"])
            elif t.get("golf") in {"tee","teeing_ground"}: draw_geom(d,g,px,COLORS["tee"])
        draw_geom(d,local_line,px,COLORS["line"],7)
        label=str((feature.get("properties") or {}).get("tags",{}).get("ref") or n)
        filename=f"hole_{n:02d}_{label}.png"
        img.save(out/filename,optimize=True)
        manifest["generated"].append({"candidate_index":n,"hole_label":str(n),"image":filename,"source_osm_id":feature["id"],"geometry_source":"mapped_osm" if not str(feature["id"]).startswith("provisional") else "tee_green_pair_hypothesis","tee_count":len(tee_candidates),"green_count":len(green_candidates),"fairway_source":"mapped_osm" if mapped_fairways else "inferred_centerline_buffer"})
    manifest["generated_count"]=len(manifest["generated"]); manifest["skipped_count"]=len(manifest["skipped"])
    (out/"manifest.json").write_text(json.dumps(manifest,indent=2))
    print(json.dumps({"generated_count":manifest["generated_count"],"manifest":str(out/"manifest.json"),"course":args.course_name},indent=2))

if __name__=="__main__": main()
