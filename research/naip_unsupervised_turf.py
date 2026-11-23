#!/usr/bin/env python3
"""Per-course unsupervised RGBIR/texture clustering for fairway candidates."""
from __future__ import annotations
import argparse,json,math
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFilter
R=6378137.0
CLASSES=("fairway","rough","green","bunker","water_hazard","tee")

def mercator(lon,lat):
    lat=max(-85.05112878,min(85.05112878,lat))
    return R*math.radians(lon),R*math.log(math.tan(math.pi/4+math.radians(lat)/2))
def rings(g):
    if g["type"]=="Polygon": yield g["coordinates"]
    elif g["type"]=="MultiPolygon": yield from g["coordinates"]
def local_std(gray,radius=4):
    p=np.pad(gray,((radius,radius),(radius,radius)),mode="reflect"); p2=p*p
    s=np.pad(p,((1,0),(1,0))).cumsum(0).cumsum(1)
    s2=np.pad(p2,((1,0),(1,0))).cumsum(0).cumsum(1); n=(2*radius+1)**2
    total=s[2*radius+1:,2*radius+1:]-s[:-2*radius-1,2*radius+1:]-s[2*radius+1:,:-2*radius-1]+s[:-2*radius-1,:-2*radius-1]
    total2=s2[2*radius+1:,2*radius+1:]-s2[:-2*radius-1,2*radius+1:]-s2[2*radius+1:,:-2*radius-1]+s2[:-2*radius-1,:-2*radius-1]
    m=total/n; return np.sqrt(np.maximum(total2/n-m*m,0))
def masks_for(fc,px,w,h):
    out={}
    for cls in CLASSES:
        im=Image.new("1",(w,h),0); d=ImageDraw.Draw(im)
        for f in fc["features"]:
            if f["properties"].get("golf")!=cls: continue
            for poly in rings(f["geometry"]):
                if not poly: continue
                d.polygon([px(p) for p in poly[0]],fill=1)
                for inner in poly[1:]: d.polygon([px(p) for p in inner],fill=0)
        out[cls]=np.asarray(im,dtype=bool)
    return out
def kmeans(data,k=6,iterations=12,seed=42):
    rng=np.random.default_rng(seed); n=len(data)
    if n<k*20: raise SystemExit(f"Too few pixels for k-means: {n}")
    sample=data if n<=30000 else data[rng.choice(n,30000,replace=False)]
    centers=[sample[rng.integers(len(sample))]]
    for _ in range(1,k):
        d2=np.min(np.stack([np.sum((sample-c)**2,axis=1) for c in centers]),axis=0)
        probs=d2/max(float(d2.sum()),1e-12)
        centers.append(sample[rng.choice(len(sample),p=probs)])
    centers=np.asarray(centers)
    for _ in range(iterations):
        dist=np.sum((data[:,None,:]-centers[None,:,:])**2,axis=2)
        labels=dist.argmin(axis=1)
        new=centers.copy()
        for j in range(k):
            m=data[labels==j]
            if len(m): new[j]=m.mean(axis=0)
        if np.allclose(new,centers,atol=1e-4): break
        centers=new
    return labels,centers

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--geojson",required=True);ap.add_argument("--metadata",required=True);ap.add_argument("--tiff",required=True)
    ap.add_argument("--out",required=True);ap.add_argument("--corridor-m",type=float,default=55);ap.add_argument("--clusters",type=int,default=6)
    a=ap.parse_args()
    fc=json.loads(Path(a.geojson).read_text());meta=json.loads(Path(a.metadata).read_text())
    im=Image.open(a.tiff);raw=np.asarray(im).astype(np.float32);h,w=raw.shape[:2]
    minx,miny,maxx,maxy=meta["export_bbox_web_mercator_m"]
    def px(p):
        x,y=mercator(*p);return ((x-minx)/(maxx-minx)*(w-1),(maxy-y)/(maxy-miny)*(h-1))
    hole=next((f for f in fc["features"] if f["properties"].get("golf")=="hole" and str(f["properties"].get("ref") or f["properties"].get("golf:hole") or "")=="1"),None)
    if hole is None:raise SystemExit("Missing explicitly numbered Hole 1")
    line=hole["geometry"]["coordinates"]
    corridor=Image.new("1",(w,h),0);d=ImageDraw.Draw(corridor)
    ground_w=(maxx-minx)*math.cos(math.radians(sum(p[1] for p in line)/len(line)))
    d.line([px(p) for p in line],fill=1,width=max(1,int(2*a.corridor_m*w/max(ground_w,1))))
    corridor=np.asarray(corridor,dtype=bool)
    masks=masks_for(fc,px,w,h)
    red,green,blue,nir=[raw[:,:,i] for i in range(4)]
    ndvi=(nir-red)/np.maximum(nir+red,1);exg=(2*green-red-blue)/255
    gray=(.299*red+.587*green+.114*blue)/255
    texture=local_std(gray,4)
    x=np.stack([ndvi,exg,texture],axis=-1)
    valid=(red+nir)>0
    excluded=masks["green"]|masks["bunker"]|masks["water_hazard"]|masks["tee"]
    analysis=corridor&valid&~excluded
    ys,xs=np.where(analysis)
    if len(xs)<1000:raise SystemExit("Insufficient unlabelled corridor pixels")
    values=x[ys,xs]
    mean=values.mean(0);scale=values.std(0);scale[scale<1e-6]=1
    z=(values-mean)/scale
    labels,centers=kmeans(z,a.clusters)
    label_image=np.full((h,w),-1,dtype=np.int16);label_image[ys,xs]=labels
    fair=masks["fairway"]&analysis;rough=masks["rough"]&analysis
    stats=[]
    best=-1;best_score=-1
    for j in range(a.clusters):
        cluster=(label_image==j)
        f=int((cluster&fair).sum());r=int((cluster&rough).sum());size=int(cluster.sum())
        recall=f/max(int(fair.sum()),1)
        precision=f/max(f+r,1)
        f1=2*precision*recall/max(precision+recall,1e-9)
        stats.append({"cluster":j,"pixels":size,"fairway_seed_pixels":f,"rough_seed_pixels":r,"fairway_seed_recall":round(recall,4),"fairway_vs_rough_seed_precision":round(precision,4),"fairway_vs_rough_seed_f1":round(f1,4)})
        score=f1 if rough.sum()>0 else recall
        if score>best_score:best_score=score;best=j
    candidate=label_image==best
    base=np.stack([red,green,blue],axis=-1).clip(0,255).astype(np.uint8)
    overlay=base.copy(); tint=np.array([30,230,80],dtype=np.float32)
    overlay[candidate]=(0.42*base[candidate]+0.58*tint).clip(0,255).astype(np.uint8)
    out=Path(a.out);out.mkdir(parents=True,exist_ok=True)
    Image.fromarray(overlay,"RGB").save(out/"unsupervised-fairway-candidate.png",quality=95)
    # Smooth pixel noise into more coherent regions, then reapply hard exclusions.
    binary=Image.fromarray((candidate.astype(np.uint8)*255),"L")
    smooth=(binary.filter(ImageFilter.MedianFilter(5))
                  .filter(ImageFilter.MaxFilter(7))
                  .filter(ImageFilter.MinFilter(7))
                  .filter(ImageFilter.MinFilter(3))
                  .filter(ImageFilter.MaxFilter(3)))
    smooth_mask=(np.asarray(smooth)>127)&analysis
    smooth_overlay=base.copy()
    smooth_overlay[smooth_mask]=(0.42*base[smooth_mask]+0.58*tint).clip(0,255).astype(np.uint8)
    Image.fromarray(smooth_overlay,"RGB").save(out/"unsupervised-fairway-smoothed.png",quality=95)
    color=np.zeros((h,w,3),dtype=np.uint8)
    palette=np.array([[230,80,80],[80,180,240],[230,210,60],[180,100,220],[60,210,150],[230,150,70]],dtype=np.uint8)
    for j in range(a.clusters):color[label_image==j]=palette[j%len(palette)]
    Image.fromarray(color,"RGB").save(out/"cluster-label-preview.png")
    report={"hole_osm_id":hole["properties"].get("osm_id"),"method":"Per-course k-means on NDVI, excess-green, and local RGB texture; known green/bunker/water/tee polygons excluded from cluster fitting.","clusters":a.clusters,"corridor_m":a.corridor_m,"analysis_pixels":len(xs),"fairway_seed_pixels":int(fair.sum()),"rough_seed_pixels":int(rough.sum()),"selected_candidate_cluster":best,"selected_cluster_score":round(best_score,4),"raw_candidate_pixels":int(candidate.sum()),"smoothed_candidate_pixels":int(smooth_mask.sum()),"cluster_statistics":stats,"features_standardization":{"mean":mean.tolist(),"scale":scale.tolist()},"warning":"Cluster selection is guided by incomplete OSM seed labels; unsupervised cluster membership is not ground truth. If no rough seed exists, selection uses fairway-seed recall only and cannot estimate precision.","files":["unsupervised-fairway-candidate.png","unsupervised-fairway-smoothed.png","cluster-label-preview.png","cluster-report.json"]}
    (out/"cluster-report.json").write_text(json.dumps(report,indent=2)+"\n");print(json.dumps(report,indent=2))
if __name__=="__main__":main()
