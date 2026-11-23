#!/usr/bin/env python3
"""Lightweight RGBIR + texture fairway-candidate experiment.

A linear discriminant is fitted to weak OSM fairway/rough seed pixels from the
South Padre test hole, then applied unchanged to both test images. This is a
feasibility experiment only; OSM labels are not ground truth.
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

def local_std(gray, radius=4):
    # Integral-image box variance, O(width*height), no OpenCV or ML runtime.
    p=np.pad(gray,((radius,radius),(radius,radius)),mode="reflect")
    p2=p*p
    s=np.pad(p,((1,0),(1,0))).cumsum(0).cumsum(1)
    s2=np.pad(p2,((1,0),(1,0))).cumsum(0).cumsum(1)
    n=(2*radius+1)**2
    total=s[2*radius+1:,2*radius+1:]-s[:-2*radius-1,2*radius+1:]-s[2*radius+1:,:-2*radius-1]+s[:-2*radius-1,:-2*radius-1]
    total2=s2[2*radius+1:,2*radius+1:]-s2[:-2*radius-1,2*radius+1:]-s2[2*radius+1:,:-2*radius-1]+s2[:-2*radius-1,:-2*radius-1]
    mean=total/n
    return np.sqrt(np.maximum(total2/n-mean*mean,0))

def build_masks(fc,px,w,h):
    masks={}
    for cls in CLASSES:
        mask=Image.new("1",(w,h),0); draw=ImageDraw.Draw(mask)
        for f in fc["features"]:
            if f["properties"].get("golf")!=cls: continue
            for poly in rings(f["geometry"]):
                if not poly: continue
                draw.polygon([px(p) for p in poly[0]],fill=1)
                for inner in poly[1:]: draw.polygon([px(p) for p in inner],fill=0)
        masks[cls]=np.asarray(mask,dtype=bool)
    return masks

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--train-geojson",required=True); ap.add_argument("--train-metadata",required=True); ap.add_argument("--train-tiff",required=True)
    ap.add_argument("--geojson",required=True); ap.add_argument("--metadata",required=True); ap.add_argument("--tiff",required=True)
    ap.add_argument("--out",required=True); ap.add_argument("--corridor-m",type=float,default=55)
    a=ap.parse_args()
    def load(geojson,metadata,tiff):
        fc=json.loads(Path(geojson).read_text()); meta=json.loads(Path(metadata).read_text())
        im=Image.open(tiff); raw=np.asarray(im).astype(np.float32)
        if raw.ndim!=3 or raw.shape[2]!=4: raise SystemExit(f"Expected 4-band RGBIR TIFF, got {raw.shape}")
        h,w=raw.shape[:2]; minx,miny,maxx,maxy=meta["export_bbox_web_mercator_m"]
        def px(p):
            x,y=mercator(*p); return ((x-minx)/(maxx-minx)*(w-1),(maxy-y)/(maxy-miny)*(h-1))
        return fc,meta,raw,px,w,h,(minx,miny,maxx,maxy)
    train=load(a.train_geojson,a.train_metadata,a.train_tiff)
    test=load(a.geojson,a.metadata,a.tiff)
    def features(data):
        fc,meta,raw,px,w,h,bbox=data
        red,green,blue,nir=[raw[:,:,i] for i in range(4)]
        ndvi=(nir-red)/np.maximum(nir+red,1)
        exg=(2*green-red-blue)/255.0
        gray=(.299*red+.587*green+.114*blue)/255.0
        texture=local_std(gray,4)
        x=np.stack([ndvi,exg,texture],axis=-1)
        valid=(red+nir)>0
        return x,valid,(red,green,blue),ndvi,exg
    tx,tvalid,_,_,_=features(train)
    tmasks=build_masks(train[0],train[3],train[4],train[5])
    pos=tmasks["fairway"]&tvalid; neg=tmasks["rough"]&tvalid
    if pos.sum()<100 or neg.sum()<100:
        raise SystemExit(f"Insufficient South Padre seed pixels: fairway={pos.sum()}, rough={neg.sum()}")
    rng=np.random.default_rng(20261123)
    block_size=64
    def sample(mask,limit=40000):
        ys,xs=np.where(mask)
        if len(xs)>limit:
            pick=rng.choice(len(xs),limit,replace=False); ys,xs=ys[pick],xs[pick]
        return tx[ys,xs],ys,xs
    p,py,pxs=sample(pos); n,ny,nx=sample(neg)
    # Hold out whole spatial blocks, not random neighboring pixels.
    def block_ids(ys,xs):
        return ((ys//block_size)*73856093 ^ (xs//block_size)*19349663) % 5
    ptest_mask=block_ids(py,pxs)==0
    ntest_mask=block_ids(ny,nx)==0
    pfit,ptest=p[~ptest_mask],p[ptest_mask]
    nfit,ntest=n[~ntest_mask],n[ntest_mask]
    if min(len(pfit),len(ptest),len(nfit),len(ntest))<100:
        raise SystemExit("Spatial block holdout produced too few samples; inspect seed geometry.")
    allfit=np.vstack([pfit,nfit]); mean=allfit.mean(0); scale=allfit.std(0); scale[scale<1e-6]=1
    pz=(pfit-mean)/scale; nz=(nfit-mean)/scale
    mu_p=pz.mean(0); mu_n=nz.mean(0)
    cp=np.cov(pz,rowvar=False); cn=np.cov(nz,rowvar=False)
    cov=((len(pz)-1)*cp+(len(nz)-1)*cn)/max(len(pz)+len(nz)-2,1)
    weight=np.linalg.pinv(cov)@(mu_p-mu_n)
    bias=-.5*float((mu_p+mu_n)@weight)+math.log(len(pz)/len(nz))
    def score(x): return ((x-mean)/scale)@weight+bias
    ptest_score=score(ptest); ntest_score=score(ntest)
    threshold=0.0
    holdout={"method":"spatial block holdout; 64x64 pixel blocks (~38m square at 0.6m GSD) assigned wholly to train or test","fairway_true_positive_rate":round(float(np.mean(ptest_score>threshold)),4),"rough_false_positive_rate":round(float(np.mean(ntest_score>threshold)),4),"fairway_holdout_pixels":len(ptest),"rough_holdout_pixels":len(ntest),"fairway_train_pixels":len(pfit),"rough_train_pixels":len(nfit),"warning":"Spatial blocks reduce neighboring-pixel leakage, but labels are still OSM-derived and the test covers only one hole and one acquisition."}

    fc,meta,raw,px,w,h,bbox=test
    x,valid,rgb,ndvi,exg=features(test)
    scores=score(x)
    minx,miny,maxx,maxy=bbox
    hole_ref="1"
    hole=next((f for f in fc["features"] if f["properties"].get("golf")=="hole" and str(f["properties"].get("ref") or f["properties"].get("golf:hole") or "")==hole_ref),None)
    if hole is None: raise SystemExit("Target GeoJSON has no explicitly numbered Hole 1")
    line=hole["geometry"]["coordinates"]
    corridor=Image.new("1",(w,h),0); draw=ImageDraw.Draw(corridor)
    # Convert ground corridor to a pixel width using the exported local scale.
    ground_w=(maxx-minx)*math.cos(math.radians(sum(p[1] for p in line)/len(line)))
    px_per_m=w/max(ground_w,1)
    draw.line([px(p) for p in line],fill=1,width=max(1,int(2*a.corridor_m*px_per_m)))
    corridor_mask=np.asarray(corridor,dtype=bool)
    masks=build_masks(fc,px,w,h)
    exclude=masks["green"]|masks["bunker"]|masks["water_hazard"]|masks["tee"]
    candidate=(scores>threshold)&valid&corridor_mask&~exclude
    # Render transparent green pixels over the RGB orthophoto.
    base=np.stack(rgb,axis=-1).clip(0,255).astype(np.uint8)
    overlay=base.copy()
    green_tint=np.array([30,230,80],dtype=np.float32)
    overlay[candidate]=(0.42*base[candidate]+0.58*green_tint).clip(0,255).astype(np.uint8)
    out=Path(a.out); out.mkdir(parents=True,exist_ok=True)
    Image.fromarray(overlay,"RGB").save(out/"fairway-candidate-overlay.png",quality=95)
    score_preview=np.clip((scores+3)/6*255,0,255).astype(np.uint8)
    score_preview[~corridor_mask]=0
    Image.fromarray(score_preview,"L").save(out/"fairway-score-preview.png")
    def positive_rate(mask):
        use=mask&valid
        return {"pixels":int(use.sum()),"positive_fraction":round(float(candidate[use].mean()),4) if use.any() else None}
    seed_eval={cls:positive_rate(mask) for cls,mask in masks.items()}
    report={
        "target_hole_osm_id":hole["properties"].get("osm_id"),
        "training_source":"South Padre Island Golf Club Hole 1 OSM fairway and rough polygons",
        "training_samples":{"fairway":len(p),"rough":len(n)},
        "features":["NDVI","excess_green","local_RGB_texture_stddev_9x9"],
        "linear_discriminant_weights":weight.tolist(),"standardization_mean":mean.tolist(),"standardization_scale":scale.tolist(),"bias":bias,
        "south_padre_spatial_block_holdout":holdout,
        "target_seed_positive_rates":seed_eval,
        "candidate_corridor_m":a.corridor_m,
        "candidate_pixels":int(candidate.sum()),
        "corridor_pixels":int(corridor_mask.sum()),
        "candidate_fraction_of_corridor":round(float(candidate.sum()/max(corridor_mask.sum(),1)),4),
        "interpretation":"Experimental fairway-like turf candidates only. Classifier was trained from one course's OSM labels and may not transfer to other grass, lighting, season, or imagery conditions. Do not treat output as accepted fairway geometry.",
        "files":["fairway-candidate-overlay.png","fairway-score-preview.png","classifier-report.json"]
    }
    (out/"classifier-report.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report,indent=2))
if __name__=="__main__": main()
