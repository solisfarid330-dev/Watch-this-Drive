#!/usr/bin/env python3
"""Conventional RGB/NIR spectral-index experiment on Texas NAIP imagery.

This is a research diagnostic, not an automatic fairway classifier. OSM polygons
are used only as weak seed labels to measure whether spectral features differ.
"""
from __future__ import annotations
import argparse, json, math
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

R=6378137.0
CLASSES=("fairway","rough","green","bunker","water_hazard","tee")

def mercator(lon,lat):
    lat=max(-85.05112878,min(85.05112878,lat))
    return R*math.radians(lon),R*math.log(math.tan(math.pi/4+math.radians(lat)/2))

def rings(g):
    if g["type"]=="Polygon": yield g["coordinates"]
    elif g["type"]=="MultiPolygon": yield from g["coordinates"]

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--geojson",required=True); ap.add_argument("--metadata",required=True)
    ap.add_argument("--tiff",required=True); ap.add_argument("--out",required=True)
    a=ap.parse_args()
    fc=json.loads(Path(a.geojson).read_text()); meta=json.loads(Path(a.metadata).read_text())
    im=Image.open(a.tiff)
    raw=np.asarray(im)
    if raw.ndim!=3 or raw.shape[2]!=4:
        raise SystemExit(f"Expected four-band RGBIR TIFF; mode={im.mode}, shape={raw.shape}")
    h,w=raw.shape[:2]
    if [w,h]!=meta["export_size_pixels"]:
        raise SystemExit(f"TIFF dimensions {(w,h)} do not match metadata {meta['export_size_pixels']}")
    minx,miny,maxx,maxy=meta["export_bbox_web_mercator_m"]
    def px(p):
        x,y=mercator(*p)
        return ((x-minx)/(maxx-minx)*(w-1),(maxy-y)/(maxy-miny)*(h-1))
    out=Path(a.out); out.mkdir(parents=True,exist_ok=True)
    masks={}
    for cls in CLASSES:
        mask=Image.new("1",(w,h),0); draw=ImageDraw.Draw(mask)
        for f in fc["features"]:
            if f["properties"].get("golf")!=cls: continue
            for poly in rings(f["geometry"]):
                if not poly: continue
                draw.polygon([px(p) for p in poly[0]],fill=1)
                for hole in poly[1:]: draw.polygon([px(p) for p in hole],fill=0)
        masks[cls]=np.asarray(mask,dtype=bool)
    valid=(raw[:,:,0].astype(np.float32)+raw[:,:,3].astype(np.float32))>0
    red=raw[:,:,0].astype(np.float32); green=raw[:,:,1].astype(np.float32)
    blue=raw[:,:,2].astype(np.float32); nir=raw[:,:,3].astype(np.float32)
    ndvi=(nir-red)/np.maximum(nir+red,1.0)
    exg=(2*green-red-blue)/255.0
    def stats(values):
        if values.size==0: return {"pixels":0}
        q=np.quantile(values,[.1,.25,.5,.75,.9])
        return {"pixels":int(values.size),"p10":round(float(q[0]),4),"p25":round(float(q[1]),4),"median":round(float(q[2]),4),"p75":round(float(q[3]),4),"p90":round(float(q[4]),4)}
    class_stats={}
    for cls,mask in masks.items():
        use=mask&valid
        class_stats[cls]={
            "ndvi":stats(ndvi[use]),
            "excess_green":stats(exg[use]),
        }
    fair=ndvi[masks["fairway"]&valid]; rough=ndvi[masks["rough"]&valid]
    if fair.size and rough.size:
        pooled=math.sqrt((float(fair.var())+float(rough.var()))/2)
        effect=(float(fair.mean())-float(rough.mean()))/pooled if pooled>1e-9 else 0.0
        separability={"fairway_minus_rough_ndvi_mean":round(float(fair.mean()-rough.mean()),4),"cohens_d":round(effect,4),"absolute_cohens_d":round(abs(effect),4)}
    else:
        separability={"status":"insufficient mapped fairway or rough seed pixels"}
    # Grayscale NDVI preview; values outside the display range are clipped.
    preview=np.clip((ndvi+0.2)/1.0*255,0,255).astype(np.uint8)
    preview[~valid]=0
    Image.fromarray(preview,mode="L").save(out/"ndvi-preview.png")
    report={
        "course_hole_osm_id":meta["hole_osm_id"],
        "source":"Texas NAIP 2022 RGBIR 4-band orthophotography",
        "band_order_assumption":["red","green","blue","near_infrared"],
        "tiff_mode":im.mode,"tiff_dimensions":[w,h],
        "ndvi_formula":"(NIR - Red) / (NIR + Red)",
        "weak_seed_label_warning":"OSM polygons may be incomplete, generalized, misclassified, or offset. Pixel samples are spatially correlated and are not independent ground truth.",
        "class_samples":class_stats,
        "fairway_vs_rough_ndvi_separability":separability,
        "interpretation":"Spectral separability is exploratory only. NDVI alone does not identify fairway boundaries; compare seed distributions and the preview with the aerial image.",
        "files":["ndvi-preview.png","spectral-summary.json"]
    }
    (out/"spectral-summary.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report,indent=2))
if __name__=="__main__": main()
