#!/usr/bin/env python3
"""SLIC superpixel fairway-candidate experiment using weak OSM seed labels.

Classical image processing only: no neural network or pretrained model.
OSM polygons are weak labels, and every output remains a candidate mask.
"""
from __future__ import annotations
import argparse,json,math
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
from skimage.segmentation import slic

R=6378137.0
CLASSES=("fairway","rough","green","bunker","water_hazard","tee")

def mercator(lon,lat):
    lat=max(-85.05112878,min(85.05112878,lat))
    return R*math.radians(lon),R*math.log(math.tan(math.pi/4+math.radians(lat)/2))
def rings(g):
    if g["type"]=="Polygon":yield g["coordinates"]
    elif g["type"]=="MultiPolygon":yield from g["coordinates"]
def local_std(gray,radius=4):
    p=np.pad(gray,((radius,radius),(radius,radius)),mode="reflect");p2=p*p
    s=np.pad(p,((1,0),(1,0))).cumsum(0).cumsum(1)
    s2=np.pad(p2,((1,0),(1,0))).cumsum(0).cumsum(1);n=(2*radius+1)**2
    total=s[2*radius+1:,2*radius+1:]-s[:-2*radius-1,2*radius+1:]-s[2*radius+1:,:-2*radius-1]+s[:-2*radius-1,:-2*radius-1]
    total2=s2[2*radius+1:,2*radius+1:]-s2[:-2*radius-1,2*radius+1:]-s2[2*radius+1:,:-2*radius-1]+s2[:-2*radius-1,:-2*radius-1]
    m=total/n;return np.sqrt(np.maximum(total2/n-m*m,0))
def build_masks(fc,px,w,h):
    masks={}
    for cls in CLASSES:
        im=Image.new("1",(w,h),0);d=ImageDraw.Draw(im)
        for f in fc["features"]:
            if f["properties"].get("golf")!=cls:continue
            for poly in rings(f["geometry"]):
                if not poly:continue
                d.polygon([px(p) for p in poly[0]],fill=1)
                for inner in poly[1:]:d.polygon([px(p) for p in inner],fill=0)
        masks[cls]=np.asarray(im,dtype=bool)
    return masks
def lda_fit(x,y):
    p=x[y==1];n=x[y==0]
    if len(p)<5 or len(n)<5:raise SystemExit(f"Insufficient superpixel seed labels: fairway={len(p)}, rough={len(n)}")
    mean=x.mean(0);scale=x.std(0);scale[scale<1e-6]=1
    pz=(p-mean)/scale;nz=(n-mean)/scale
    mp=pz.mean(0);mn=nz.mean(0)
    cp=np.cov(pz,rowvar=False);cn=np.cov(nz,rowvar=False)
    cov=((len(pz)-1)*cp+(len(nz)-1)*cn)/max(len(pz)+len(nz)-2,1)
    w=np.linalg.pinv(cov)@(mp-mn);b=-.5*float((mp+mn)@w)+math.log(len(pz)/len(nz))
    return mean,scale,w,b
def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--geojson",required=True);ap.add_argument("--metadata",required=True);ap.add_argument("--tiff",required=True)
    ap.add_argument("--hole-ref",required=True);ap.add_argument("--out",required=True)
    ap.add_argument("--corridor-m",type=float,default=35);ap.add_argument("--segments",type=int,default=2400)
    ap.add_argument("--compactness",type=float,default=8.0)
    a=ap.parse_args()
    fc=json.loads(Path(a.geojson).read_text());meta=json.loads(Path(a.metadata).read_text())
    raw=np.asarray(Image.open(a.tiff)).astype(np.float32)
    if raw.ndim!=3 or raw.shape[2]!=4:raise SystemExit("Expected four-band RGBIR TIFF")
    h,w=raw.shape[:2]
    if [w,h]!=meta["export_size_pixels"]:raise SystemExit("TIFF dimensions do not match metadata")
    minx,miny,maxx,maxy=meta["export_bbox_web_mercator_m"]
    def px(p):
        x,y=mercator(*p);return ((x-minx)/(maxx-minx)*(w-1),(maxy-y)/(maxy-miny)*(h-1))
    hole=next((f for f in fc["features"] if f["properties"].get("golf")=="hole" and str(f["properties"].get("ref") or f["properties"].get("golf:hole") or "")==a.hole_ref),None)
    if hole is None:raise SystemExit("Hole ref not found")
    line=hole["geometry"]["coordinates"]
    corridor=Image.new("1",(w,h),0);d=ImageDraw.Draw(corridor)
    ground_w=(maxx-minx)*math.cos(math.radians(sum(p[1] for p in line)/len(line)))
    d.line([px(p) for p in line],fill=1,width=max(1,int(2*a.corridor_m*w/max(ground_w,1))))
    corridor_mask=np.asarray(corridor,dtype=bool)
    masks=build_masks(fc,px,w,h)
    red,green,blue,nir=[raw[:,:,i] for i in range(4)]
    ndvi=(nir-red)/np.maximum(nir+red,1)
    exg=(2*green-red-blue)/255.0
    gray=(.299*red+.587*green+.114*blue)/255.0
    texture=local_std(gray,4)
    rgbir=np.stack([red,green,blue,nir],axis=-1)/255.0
    labels=slic(rgbir,n_segments=a.segments,compactness=a.compactness,sigma=1.0,start_label=0,channel_axis=-1,convert2lab=False)
    count=int(labels.max())+1
    flat=labels.ravel();pixcount=np.bincount(flat,minlength=count).astype(np.float64)
    def fraction(mask):
        return np.bincount(flat,weights=mask.ravel().astype(np.float32),minlength=count)/np.maximum(pixcount,1)
    fair_frac=fraction(masks["fairway"]);rough_frac=fraction(masks["rough"])
    excluded=masks["green"]|masks["bunker"]|masks["water_hazard"]|masks["tee"]
    excluded_frac=fraction(excluded)
    features=[]
    for arr in (ndvi,exg,texture):
        features.append(np.bincount(flat,weights=arr.ravel(),minlength=count)/np.maximum(pixcount,1))
    x=np.stack(features,axis=1)
    # Only clean seed superpixels train the weak-label model.
    y=np.full(count,-1,dtype=np.int8)
    y[(fair_frac>=0.65)&(rough_frac<=0.10)&(excluded_frac<=0.05)]=1
    y[(rough_frac>=0.65)&(fair_frac<=0.10)&(excluded_frac<=0.05)]=0
    yy,xx=np.indices((h,w));cx=np.bincount(flat,weights=xx.ravel(),minlength=count)/np.maximum(pixcount,1);cy=np.bincount(flat,weights=yy.ravel(),minlength=count)/np.maximum(pixcount,1)
    # Spatial holdout by 64x64 exported-pixel blocks.
    block=((cy.astype(int)//64)*73856093 ^ (cx.astype(int)//64)*19349663)%5
    train=(y>=0)&(block!=0);test=(y>=0)&(block==0)
    if min(int((train&(y==1)).sum()),int((train&(y==0)).sum()),int((test&(y==1)).sum()),int((test&(y==0)).sum()))<5:
        raise SystemExit("Too few spatially separated labeled superpixels; inspect OSM seed coverage.")
    mean,scale,weight,bias=lda_fit(x[train],y[train])
    def score(z):return ((z-mean)/scale)@weight+bias
    test_score=score(x[test])
    test_y=y[test]
    tpr=float(np.mean(test_score[test_y==1]>0))
    fpr=float(np.mean(test_score[test_y==0]>0))
    scores=score(x)
    corridor_fraction=fraction(corridor_mask)
    valid_frac=fraction((red+nir)>0)
    selected=(scores>0)&(corridor_fraction>0.5)&(valid_frac>0.5)&(excluded_frac<0.1)
    candidate=selected[labels]
    # Smooth the superpixel result only lightly; do not grow it beyond the corridor.
    binary=Image.fromarray((candidate.astype(np.uint8)*255),"L")
    from PIL import ImageFilter
    smooth=(binary.filter(ImageFilter.MedianFilter(3)).filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MinFilter(5)))
    candidate=(np.asarray(smooth)>127)&corridor_mask&~excluded
    base=np.stack([red,green,blue],axis=-1).clip(0,255).astype(np.uint8)
    overlay=base.copy();tint=np.array([30,230,80],dtype=np.float32)
    overlay[candidate]=(0.42*base[candidate]+0.58*tint).clip(0,255).astype(np.uint8)
    out=Path(a.out);out.mkdir(parents=True,exist_ok=True)
    Image.fromarray(overlay,"RGB").save(out/f"hole-{a.hole_ref}-superpixel-fairway-candidate.png")
    Image.fromarray(overlay,"RGB").save(out/f"hole-{a.hole_ref}-superpixel-fairway-candidate.jpg",quality=92)
    def rate(mask):
        n=int(mask.sum());return {"pixels":n,"candidate_fraction":round(float(candidate[mask].mean()),4) if n else None}
    report={
        "hole_ref":a.hole_ref,"hole_osm_id":hole["properties"].get("osm_id"),
        "method":"SLIC superpixels over RGBIR imagery, weak-label LDA on clean OSM fairway/rough superpixels, spatial block holdout, and hole-corridor/exclusion masks.",
        "superpixel_count":count,"segments_requested":a.segments,"compactness":a.compactness,
        "seed_superpixels":{"fairway":int(((y==1)&train).sum()+((y==1)&test).sum()),"rough":int(((y==0)&train).sum()+((y==0)&test).sum()),"fairway_train":int((train&(y==1)).sum()),"rough_train":int((train&(y==0)).sum()),"fairway_test":int((test&(y==1)).sum()),"rough_test":int((test&(y==0)).sum())},
        "spatial_block_holdout":{"fairway_true_positive_rate":round(tpr,4),"rough_false_positive_rate":round(fpr,4),"warning":"Superpixel blocks reduce pixel leakage, but weak OSM labels are not independent ground truth."},
        "target_seed_candidate_rates":{"fairway":rate(masks["fairway"]&corridor_mask),"rough":rate(masks["rough"]&corridor_mask)},
        "candidate_pixels":int(candidate.sum()),"corridor_pixels":int(corridor_mask.sum()),"candidate_fraction_of_corridor":round(float(candidate.sum()/max(corridor_mask.sum(),1)),4),
        "limitations":["OSM polygons are weak labels and may be incomplete or offset.","SLIC segmentation is a conventional CV experiment, not a trained neural model.","Output remains a candidate mask; no independently traced fairway reference is available.","No feature is assigned to a hole by proximity alone."],
        "files":[f"hole-{a.hole_ref}-superpixel-fairway-candidate.png",f"hole-{a.hole_ref}-superpixel-fairway-candidate.jpg","superpixel-report.json"]
    }
    (out/"superpixel-report.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report,indent=2))
if __name__=="__main__":main()
