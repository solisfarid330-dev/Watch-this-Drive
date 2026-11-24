#!/usr/bin/env python3
"""Conservative endpoint-based golf-hole feature association diagnostics.

This does not permanently assign features. It reports strong endpoint candidates
for tee/green/pin markers and leaves fairway, bunker, rough, and water features
as corridor candidates requiring further evidence.
"""
from __future__ import annotations
import argparse, json, math
from pathlib import Path

R=6371008.8
MARKERS=("tee","green","pin")
CORRIDOR_CLASSES=("fairway","bunker","rough","water_hazard","cartpath")

def project(point, lat0):
    lon,lat=point
    k=math.pi/180.0
    return (R*lon*k*math.cos(lat0*k), R*lat*k)

def point_segment_distance(p,a,b):
    dx,dy=b[0]-a[0],b[1]-a[1]
    denom=dx*dx+dy*dy
    t=0.0 if denom==0 else max(0.0,min(1.0,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/denom))
    return math.hypot(p[0]-a[0]-t*dx,p[1]-a[1]-t*dy)

def point_in_ring(p,ring):
    inside=False
    for i in range(len(ring)):
        x1,y1=ring[i];x2,y2=ring[i-1]
        if (y1>p[1])!=(y2>p[1]) and p[0] < (x2-x1)*(p[1]-y1)/(y2-y1)+x1:
            inside=not inside
    return inside

def geometry_distance(point,geometry,lat0):
    p=project(point,lat0); kind=geometry["type"]; coords=geometry["coordinates"]
    if kind=="Point":
        q=project(coords,lat0);return math.hypot(p[0]-q[0],p[1]-q[1])
    if kind in ("Polygon","MultiPolygon"):
        polygons=[coords] if kind=="Polygon" else coords
        best=float("inf")
        for poly in polygons:
            rings=[[project(q,lat0) for q in ring] for ring in poly]
            if rings and point_in_ring(p,rings[0]) and not any(point_in_ring(p,r) for r in rings[1:]):
                return 0.0
            for ring in rings:
                for a,b in zip(ring,ring[1:]):
                    best=min(best,point_segment_distance(p,a,b))
        return best
    if kind=="LineString":
        line=[project(q,lat0) for q in coords]
        return min((point_segment_distance(p,a,b) for a,b in zip(line,line[1:])),default=float("inf"))
    if kind=="MultiLineString":
        return min((geometry_distance(point,{"type":"LineString","coordinates":line},lat0) for line in coords),default=float("inf"))
    return float("inf")

def line_distance_to_geometry(line,geometry,lat0):
    if geometry.get("type")=="Point":
        p=project(geometry["coordinates"],lat0)
        projected=[project(q,lat0) for q in line]
        return min((point_segment_distance(p,a,b) for a,b in zip(projected,projected[1:])),default=float("inf"))
    return min(geometry_distance(point,geometry,lat0) for point in line)

def choose_line_direction(features,line,lat0):
    original_start,original_end=line[0],line[-1]
    nearest={}
    for cls in MARKERS:
        distances=[]
        for feature in features:
            if feature.get("properties",{}).get("golf")==cls:
                distances.append((geometry_distance(original_start,feature["geometry"],lat0),
                                  geometry_distance(original_end,feature["geometry"],lat0)))
        if distances:
            nearest[cls]=(min(x[0] for x in distances),min(x[1] for x in distances))
    if "tee" in nearest and "green" in nearest:
        forward=nearest["tee"][0]+nearest["green"][1]
        reverse=nearest["tee"][1]+nearest["green"][0]
        basis="nearest tee plus nearest green endpoints"
    elif "pin" in nearest:
        forward=nearest["pin"][1]
        reverse=nearest["pin"][0]
        basis="nearest pin endpoint"
    elif "green" in nearest:
        forward=nearest["green"][1]
        reverse=nearest["green"][0]
        basis="nearest green endpoint"
    elif "tee" in nearest:
        forward=nearest["tee"][0]
        reverse=nearest["tee"][1]
        basis="nearest tee endpoint"
    else:
        return original_start,original_end,{"selected":"ambiguous","forward_score_m":None,"reverse_score_m":None,"basis":"no tee, green, or pin markers"}
    if abs(forward-reverse)<10:
        selected="ambiguous"
        start,end=original_start,original_end
    elif forward<reverse:
        selected="forward"
        start,end=original_start,original_end
    else:
        selected="reversed"
        start,end=original_end,original_start
    return start,end,{"selected":selected,"forward_score_m":round(forward,1),"reverse_score_m":round(reverse,1),"basis":basis}

def apply_course_wide_uniqueness(results, margin_m=5.0):
    """Keep endpoint markers from being confidently claimed by neighboring holes.

    This is still candidate generation, not a definitive membership assignment.
    A marker appearing near multiple hole ends is retained only for the clearly
    closest hole; near-ties are explicitly marked ambiguous.
    """
    for hole in results:
        for cls in MARKERS:
            hole["endpoint_marker_candidates"][cls]["course_wide_unique_candidates"] = []
            hole["endpoint_marker_candidates"][cls]["ambiguous_shared_candidates"] = []
            hole["endpoint_marker_candidates"][cls]["closer_to_another_hole"] = []

    for cls in MARKERS:
        grouped = {}
        for hole_index, hole in enumerate(results):
            for candidate in hole["endpoint_marker_candidates"][cls]["strong_endpoint_candidates"]:
                feature_id = (candidate.get("osm_type"), candidate.get("osm_id"))
                distance_key = "distance_to_start_m" if cls == "tee" else "distance_to_end_m"
                grouped.setdefault(feature_id, []).append(
                    (candidate[distance_key], hole_index, candidate)
                )

        for owners in grouped.values():
            owners.sort(key=lambda item: item[0])
            best_distance, best_hole_index, best_candidate = owners[0]
            if len(owners) == 1:
                results[best_hole_index]["endpoint_marker_candidates"][cls][
                    "course_wide_unique_candidates"
                ].append({**best_candidate, "course_wide_status": "single_hole_candidate"})
                continue

            second_distance = owners[1][0]
            if second_distance - best_distance >= margin_m:
                results[best_hole_index]["endpoint_marker_candidates"][cls][
                    "course_wide_unique_candidates"
                ].append({
                    **best_candidate,
                    "course_wide_status": "unique_nearest_hole_candidate",
                    "distance_margin_to_next_hole_m": round(second_distance - best_distance, 1),
                })
                for _, other_hole_index, other_candidate in owners[1:]:
                    results[other_hole_index]["endpoint_marker_candidates"][cls][
                        "closer_to_another_hole"
                    ].append({
                        **other_candidate,
                        "closest_hole_ref": results[best_hole_index]["hole_ref"],
                        "course_wide_status": "candidate_closer_to_another_hole",
                    })
            else:
                for distance, hole_index, candidate in owners:
                    results[hole_index]["endpoint_marker_candidates"][cls][
                        "ambiguous_shared_candidates"
                    ].append({
                        **candidate,
                        "course_wide_status": "ambiguous_between_holes",
                        "distance_to_nearest_hole_m": round(best_distance, 1),
                        "distance_margin_to_next_hole_m": round(second_distance - best_distance, 1),
                    })


def analyze(collection, endpoint_limit_m=30.0, pin_limit_m=20.0, corridor_limit_m=45.0):
    features=collection.get("features",[])
    holes=[f for f in features if f.get("properties",{}).get("golf")=="hole" and f.get("geometry",{}).get("type") in ("LineString","MultiLineString")]
    results=[]
    for hole in holes:
        lines=[hole["geometry"]["coordinates"]] if hole["geometry"]["type"]=="LineString" else hole["geometry"]["coordinates"]
        line=max(lines,key=len)
        if len(line)<2: continue
        lat0=sum(p[1] for p in line)/len(line)
        start,end,direction=choose_line_direction(features,line,lat0)
        direction_known=direction["selected"]!="ambiguous"
        marker_candidates={}
        for cls in MARKERS:
            candidates=[]
            for feature in features:
                props=feature.get("properties",{})
                if props.get("golf")!=cls: continue
                ds=geometry_distance(start,feature["geometry"],lat0)
                de=geometry_distance(end,feature["geometry"],lat0)
                candidates.append({
                    "osm_type":props.get("osm_type"),"osm_id":props.get("osm_id"),
                    "distance_to_start_m":round(ds,1),"distance_to_end_m":round(de,1),
                    "tags":{k:v for k,v in props.items() if k in ("ref","name","golf:hole")}
                })
            candidates.sort(key=lambda x:min(x["distance_to_start_m"],x["distance_to_end_m"]))
            if cls=="pin":
                accepted=[x for x in candidates if direction_known and x["distance_to_end_m"]<=pin_limit_m and x["distance_to_start_m"]-x["distance_to_end_m"]>=10]
                role="green_end_pin_candidate"
            elif cls=="tee":
                accepted=[x for x in candidates if direction_known and x["distance_to_start_m"]<=endpoint_limit_m and x["distance_to_end_m"]-x["distance_to_start_m"]>=10]
                role="tee_end_candidate"
            else:
                accepted=[x for x in candidates if direction_known and x["distance_to_end_m"]<=endpoint_limit_m and x["distance_to_start_m"]-x["distance_to_end_m"]>=10]
                role="green_end_candidate"
            marker_candidates[cls]={
                "role_test":role,
                "strong_endpoint_candidates":accepted,
                "nearest_candidates":candidates[:5],
                "status":"candidate_only" if accepted else ("ambiguous_line_direction" if not direction_known else "not_found_with_endpoint_rule")
            }
        corridor=[]
        for feature in features:
            props=feature.get("properties",{});cls=props.get("golf")
            if cls not in CORRIDOR_CLASSES: continue
            d=line_distance_to_geometry(line,feature["geometry"],lat0)
            if d<=corridor_limit_m:
                corridor.append({"golf":cls,"osm_type":props.get("osm_type"),"osm_id":props.get("osm_id"),"distance_to_centerline_m":round(d,1),"assignment":"corridor_candidate_only"})
        corridor.sort(key=lambda x:x["distance_to_centerline_m"])
        results.append({
            "hole_ref":hole.get("properties",{}).get("ref") or hole.get("properties",{}).get("golf:hole"),
            "hole_osm_id":hole.get("properties",{}).get("osm_id"),
            "line_start":start,"line_end":end,
            "line_direction":direction,
            "endpoint_marker_candidates":marker_candidates,
            "corridor_feature_candidates":corridor,
            "interpretation":"Endpoint proximity is stronger evidence for tee/green/pin association than distance to any part of the hole line, but every result remains a candidate until checked against imagery or trusted course references."
        })
    apply_course_wide_uniqueness(results)
    return {
        "type":"WatchThisDriveHoleAssociationAudit",
        "method":"Infer hole-line direction from endpoint markers, then compare each marker candidate across all hole endpoints; retain only clearly nearest-hole candidates and flag near-ties. This remains diagnostic, not definitive membership.",
        "thresholds_m":{"tee_green_endpoint":endpoint_limit_m,"pin_green_endpoint":pin_limit_m,"corridor":corridor_limit_m,"course_wide_uniqueness_margin":5.0},
        "hole_count":len(results),
        "holes":results,
        "limitations":[
            "Line orientation is inferred from nearby tee/green markers or pins; when evidence is ambiguous, no strong endpoint candidates are emitted.",
            "Endpoint proximity does not prove feature membership.",
            "Course-wide nearest-endpoint comparison reduces duplicate claims, but a closest marker can still belong to another nearby hole.",
            "Near-tied marker candidates remain explicitly ambiguous rather than being forced onto a hole.",
            "Corridor features remain unassigned without stronger topology or imagery evidence."
        ]
    }

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--geojson",required=True);p.add_argument("--out",required=True)
    p.add_argument("--endpoint-limit-m",type=float,default=30)
    p.add_argument("--pin-limit-m",type=float,default=20)
    p.add_argument("--corridor-limit-m",type=float,default=45)
    a=p.parse_args()
    data=json.loads(Path(a.geojson).read_text())
    result=analyze(data,a.endpoint_limit_m,a.pin_limit_m,a.corridor_limit_m)
    out=Path(a.out);out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps({"hole_count":result["hole_count"],"output":str(out),"thresholds_m":result["thresholds_m"]},indent=2))
if __name__=="__main__":main()
