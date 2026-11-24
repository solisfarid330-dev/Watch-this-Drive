#!/usr/bin/env python3
"""Fetch NAIP aerial imagery and render OSM hole geometry for visual QA."""
from __future__ import annotations
import argparse, json, math, urllib.parse, urllib.request, io
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from hole_feature_association import line_distance_to_geometry

R = 6378137.0
SERVICE = "https://imagery.geographic.texas.gov/server/rest/services/NAIP/NAIP22_NCCIR_60cm/ImageServer/exportImage"
COLORS = {"hole":(255,235,0,255),"fairway":(50,210,90,100),"green":(40,255,80,170),"bunker":(235,190,105,180),"water_hazard":(40,140,255,170),"tee":(255,60,60,180),"rough":(130,180,80,70),"pin":(255,255,255,230)}
LABELS = {"hole":"Hole centerline","fairway":"Fairway","green":"Green","bunker":"Bunker","water_hazard":"Water hazard","tee":"Tee","rough":"Rough","pin":"Mapped pin"}

def mercator(lon, lat):
    lat=max(-85.05112878,min(85.05112878,lat))
    return R*math.radians(lon), R*math.log(math.tan(math.pi/4+math.radians(lat)/2))
def points(g):
    k,c=g["type"],g["coordinates"]
    if k=="Point": yield c
    elif k=="LineString": yield from c
    elif k=="MultiLineString":
        for line in c: yield from line
    elif k=="Polygon":
        for ring in c: yield from ring
    elif k=="MultiPolygon":
        for poly in c:
            for ring in poly: yield from ring
def rings(g):
    if g["type"]=="Polygon": yield g["coordinates"]
    elif g["type"]=="MultiPolygon": yield from g["coordinates"]
def lines(g):
    if g["type"]=="LineString": yield g["coordinates"]
    elif g["type"]=="MultiLineString": yield from g["coordinates"]

def point_in_ring(point, ring):
    x,y=point; inside=False
    for i in range(len(ring)):
        x1,y1=ring[i]; x2,y2=ring[i-1]
        if (y1>y)!=(y2>y) and x < (x2-x1)*(y-y1)/(y2-y1)+x1:
            inside=not inside
    return inside

def distance_to_hole_m(point, hole_points, lat0):
    # Local equirectangular coordinates are sufficient for this small course-scale QA window.
    def xy(p):
        return (R*math.radians(p[0])*math.cos(lat0), R*math.radians(p[1]))
    P=xy(point); best=float("inf")
    for a,b in zip(hole_points,hole_points[1:]):
        A=xy(a); B=xy(b); dx=B[0]-A[0]; dy=B[1]-A[1]
        t=0 if dx*dx+dy*dy==0 else ((P[0]-A[0])*dx+(P[1]-A[1])*dy)/(dx*dx+dy*dy)
        t=max(0,min(1,t))
        best=min(best,math.hypot(P[0]-A[0]-t*dx,P[1]-A[1]-t*dy))
    return best

def feature_distance_m(geometry, hole_points, lat0):
    # Use full line segments, not only sparse centerline vertices. This catches
    # polygons crossed between OSM vertices and shares the association audit's
    # treatment of polygon holes and line geometries.
    return line_distance_to_geometry(hole_points, geometry, lat0)

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--geojson",required=True); ap.add_argument("--hole-ref",required=True)
    ap.add_argument("--out",required=True); ap.add_argument("--buffer-m",type=float,default=65)
    ap.add_argument("--feature-corridor-m",type=float,default=45)
    ap.add_argument("--width",type=int,default=1600); ap.add_argument("--height",type=int,default=1100)
    a=ap.parse_args()
    fc=json.loads(Path(a.geojson).read_text()); fs=fc["features"]
    hs=[f for f in fs if f["properties"].get("golf")=="hole" and str(f["properties"].get("ref") or f["properties"].get("golf:hole") or "")==a.hole_ref]
    if len(hs)!=1: raise SystemExit(f"Expected one explicitly numbered hole {a.hole_ref}; found {len(hs)}")
    hole=hs[0]; hole_coords=list(points(hole["geometry"])); hp=[mercator(*p) for p in hole_coords]
    lat0=sum(p[1] for p in hole_coords)/len(hole_coords)*math.pi/180
    if len(hp)<2: raise SystemExit("Selected hole has insufficient geometry")
    minx=min(p[0] for p in hp)-a.buffer_m; maxx=max(p[0] for p in hp)+a.buffer_m
    miny=min(p[1] for p in hp)-a.buffer_m; maxy=max(p[1] for p in hp)+a.buffer_m
    aspect=(maxx-minx)/(maxy-miny); w,h=a.width,a.height
    if w/h>aspect: w=int(h*aspect)
    else: h=int(w/aspect)
    params={"bbox":f"{minx:.3f},{miny:.3f},{maxx:.3f},{maxy:.3f}","bboxSR":"3857","imageSR":"3857","size":f"{w},{h}","format":"png32","pixelType":"U8","interpolation":"RSP_Bilinear","adjustAspectRatio":"false","f":"image"}
    req=urllib.request.Request(SERVICE+"?"+urllib.parse.urlencode(params),headers={"User-Agent":"WatchThisDrive-Research/0.1","Accept":"image/png"})
    with urllib.request.urlopen(req,timeout=120) as res:
        raw=res.read(); ctype=res.headers.get("Content-Type","")
    if "image" not in ctype.lower() or not raw.startswith(b"\x89PNG"): raise RuntimeError(f"NAIP service did not return PNG (type={ctype}, bytes={len(raw)})")
    out=Path(a.out); out.mkdir(parents=True,exist_ok=True)
    rawpath=out/f"hole-{a.hole_ref}-naip-2022.png"; overlaypath=out/f"hole-{a.hole_ref}-osm-overlay.png"
    aerial=Image.open(io.BytesIO(raw)).convert("RGBA")
    if aerial.size!=(w,h): raise RuntimeError(f"Unexpected dimensions {aerial.size}, expected {(w,h)}")
    aerial.save(rawpath)
    # Preserve all four source bands (RGB + near infrared) for a separate
    # conventional spectral-index experiment. TIFF avoids PNG alpha semantics.
    spectral_params=dict(params)
    spectral_params.update({"format":"tiff","bandIds":"0,1,2,3","pixelType":"U8"})
    spectral_req=urllib.request.Request(SERVICE+"?"+urllib.parse.urlencode(spectral_params),
        headers={"User-Agent":"WatchThisDrive-Research/0.1","Accept":"image/tiff"})
    with urllib.request.urlopen(spectral_req,timeout=120) as res:
        spectral_raw=res.read()
    if not (spectral_raw.startswith(bytes((73,73,42,0))) or spectral_raw.startswith(bytes((77,77,0,42)))):
        raise RuntimeError("NAIP RGBIR export did not return a TIFF image")
    spectral_path=out/f"hole-{a.hole_ref}-naip-2022-rgbir.tif"
    spectral_path.write_bytes(spectral_raw)
    def px(p):
        x,y=mercator(*p); return ((x-minx)/(maxx-minx)*(w-1),(maxy-y)/(maxy-miny)*(h-1))
    layer=Image.new("RGBA",(w,h),(0,0,0,0)); d=ImageDraw.Draw(layer,"RGBA"); visible={}
    candidate_distances=[]
    for f in fs:
        g=f["geometry"]; kind=f["properties"].get("golf")
        if kind not in COLORS or f is hole or kind=="hole": continue
        distance=feature_distance_m(g,hole_coords,lat0)
        if distance>a.feature_corridor_m: continue
        candidate_distances.append({"osm_type":f["properties"].get("osm_type"),"osm_id":f["properties"].get("osm_id"),"golf":kind,"distance_to_hole_centerline_m":round(distance,1),"assignment":"proximity candidate only"})
        ps=[mercator(*p) for p in points(g)]
        if not ps or max(p[0] for p in ps)<minx or min(p[0] for p in ps)>maxx or max(p[1] for p in ps)<miny or min(p[1] for p in ps)>maxy: continue
        visible[kind]=visible.get(kind,0)+1; color=COLORS[kind]
        for poly in rings(g):
            if poly:
                d.polygon([px(p) for p in poly[0]],fill=color,outline=(color[0],color[1],color[2],230))
                for inner in poly[1:]: d.polygon([px(p) for p in inner],fill=(0,0,0,0))
        for line in lines(g):
            if len(line)>1: d.line([px(p) for p in line],fill=color,width=3)
        if g["type"]=="Point":
            x,y=px(g["coordinates"]); d.ellipse((x-5,y-5,x+5,y+5),fill=color,outline=(20,20,20,255))
    visible["hole"]=1
    for line in lines(hole["geometry"]):
        d.line([px(p) for p in line],fill=COLORS["hole"],width=5)
        for p in line:
            x,y=px(p); d.ellipse((x-5,y-5,x+5,y+5),fill=COLORS["hole"],outline=(20,20,20,255))
    d.rounded_rectangle((12,12,340,245),radius=10,fill=(0,0,0,190)); font=ImageFont.load_default()
    d.text((24,22),f"OSM proximity candidates — hole {a.hole_ref}",fill="white",font=font); y=46
    for k in ("hole","fairway","green","bunker","water_hazard","tee","rough","pin"):
        if k=="hole" or visible.get(k,0):
            col=COLORS[k]; d.rectangle((24,y+1,39,y+12),fill=col)
            d.text((47,y),f"{LABELS[k]} ({visible.get(k,1 if k=='hole' else 0)})",fill="white",font=font); y+=20
    d.text((24,218),"Texas NAIP 2022 · 60 cm · USDA/Texas GIO",fill="white",font=font)
    composite=Image.alpha_composite(aerial,layer).convert("RGB")
    composite.save(overlaypath,quality=95)
    preview_path=out/f"hole-{a.hole_ref}-osm-overlay-preview.jpg"
    composite.save(preview_path,format="JPEG",quality=82,optimize=True)
    meta={"hole_ref":a.hole_ref,"hole_osm_id":hole["properties"].get("osm_id"),"imagery_service":SERVICE,"imagery_acquisition":"Texas NAIP 2022 growing season (May–September 2022)","ground_sample_distance_m":0.6,"imagery_attribution":"Texas Geographic Information Office, USDA","osm_attribution":"© OpenStreetMap contributors","osm_license":"ODbL-1.0","export_bbox_web_mercator_m":[minx,miny,maxx,maxy],"export_size_pixels":[w,h],"buffer_m":a.buffer_m,"feature_corridor_m":a.feature_corridor_m,"visible_osm_feature_element_counts":visible,"candidate_feature_distances":candidate_distances,"files":[rawpath.name,spectral_path.name,overlaypath.name,preview_path.name],"source_band_order":"RGB + near infrared (band IDs 0,1,2,3; verify from image service metadata)","limitations":["Visual overlay only; not a ground-truth accuracy measurement.", "Nearby features are candidates by distance, not confirmed hole assignments.","NAIP is aerial orthophotography, not satellite imagery.","2022 imagery may differ from current course conditions.","OSM features may be incomplete or misclassified.","No image segmentation or missing-feature inference is performed."]}
    (out/f"hole-{a.hole_ref}-validation.json").write_text(json.dumps(meta,indent=2)+"\n")
    print(json.dumps(meta,indent=2))
if __name__=="__main__": main()
