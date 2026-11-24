#!/usr/bin/env python3
"""Spatially validated multi-scale texture classifier for NAIP fairway research.

This is an offline diagnostic to test whether RGBIR texture contains enough
signal to distinguish mapped fairway from rough. OSM labels are weak labels,
not ground truth. The sklearn model is not an Android/runtime dependency.
"""
from __future__ import annotations
import argparse, json, math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage
from sklearn.ensemble import ExtraTreesClassifier
from sklearn.metrics import average_precision_score, confusion_matrix, roc_auc_score

R = 6378137.0
CLASSES = ("fairway", "rough", "green", "bunker", "water_hazard", "tee")


def mercator(lon, lat):
    lat = max(-85.05112878, min(85.05112878, lat))
    return R * math.radians(lon), R * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


def polygons(geometry):
    if geometry["type"] == "Polygon":
        yield geometry["coordinates"]
    elif geometry["type"] == "MultiPolygon":
        yield from geometry["coordinates"]


def local_std(values, radius):
    values = np.asarray(values, dtype=np.float32)
    size = 2 * radius + 1
    mean = ndimage.uniform_filter(values, size=size, mode="reflect")
    mean_sq = ndimage.uniform_filter(values * values, size=size, mode="reflect")
    return np.sqrt(np.maximum(mean_sq - mean * mean, 0.0))


def load_scene(geojson_path, metadata_path, tiff_path, hole_ref):
    fc = json.loads(Path(geojson_path).read_text())
    meta = json.loads(Path(metadata_path).read_text())
    raw = np.asarray(Image.open(tiff_path)).astype(np.float32)
    if raw.ndim != 3 or raw.shape[2] != 4:
        raise ValueError(f"Expected RGBIR TIFF, got {raw.shape}")
    h, w = raw.shape[:2]
    if [w, h] != meta["export_size_pixels"]:
        raise ValueError("Imagery dimensions disagree with georeferencing metadata")
    minx, miny, maxx, maxy = meta["export_bbox_web_mercator_m"]

    def pixel(point):
        x, y = mercator(*point)
        return (round((x-minx)/(maxx-minx)*(w-1)),
                round((maxy-y)/(maxy-miny)*(h-1)))

    hole = next((f for f in fc["features"]
                 if f.get("properties", {}).get("golf") == "hole"
                 and str(f["properties"].get("ref") or f["properties"].get("golf:hole") or "") == str(hole_ref)), None)
    if hole is None or hole["geometry"]["type"] != "LineString":
        raise ValueError(f"Missing numbered hole line {hole_ref}")
    line = hole["geometry"]["coordinates"]
    lat = sum(p[1] for p in line) / len(line)
    metres_per_pixel = (maxx-minx) * math.cos(math.radians(lat)) / w

    masks = {}
    for tag in CLASSES:
        image = Image.new("1", (w, h), 0)
        draw = ImageDraw.Draw(image)
        for feature in fc["features"]:
            if feature.get("properties", {}).get("golf") != tag:
                continue
            for poly in polygons(feature["geometry"]):
                if not poly:
                    continue
                draw.polygon([pixel(p) for p in poly[0]], fill=1)
                for inner in poly[1:]:
                    draw.polygon([pixel(p) for p in inner], fill=0)
        masks[tag] = np.asarray(image, dtype=bool)

    corridor_image = Image.new("1", (w, h), 0)
    ImageDraw.Draw(corridor_image).line(
        [pixel(p) for p in line], fill=1,
        width=max(1, round(2 * 55.0 / metres_per_pixel)))
    corridor = np.asarray(corridor_image, dtype=bool)
    excluded = masks["green"] | masks["bunker"] | masks["water_hazard"] | masks["tee"]
    red, green, blue, nir = [raw[:, :, i] for i in range(4)]
    valid = (red + green + nir) > 0
    analysis = corridor & valid & ~excluded

    # Multi-scale radiometric and texture descriptors. Local statistics are
    # computed at 1.8m, 3.6m and 7.8m radii at the source 0.6m GSD.
    r, g, b, n = [band / 255.0 for band in (red, green, blue, nir)]
    ndvi = (n-r) / np.maximum(n+r, 1e-4)
    exg = 2*g-r-b
    vari = (g-r) / np.maximum(g+r-b, 1e-4)
    gray = 0.299*r + 0.587*g + 0.114*b
    features = [
        r, g, b, n, ndvi, exg, vari,
        n / np.maximum(g, 1e-3),
        r / np.maximum(g, 1e-3),
        local_std(gray, 3), local_std(gray, 6), local_std(gray, 13),
        local_std(n, 3), local_std(n, 8), local_std(ndvi, 5),
    ]
    # Mowing/landscape texture anisotropy from a local structure tensor.
    gx = ndimage.sobel(gray, axis=1, mode="reflect") / 8.0
    gy = ndimage.sobel(gray, axis=0, mode="reflect") / 8.0
    jxx = ndimage.gaussian_filter(gx*gx, sigma=4)
    jyy = ndimage.gaussian_filter(gy*gy, sigma=4)
    jxy = ndimage.gaussian_filter(gx*gy, sigma=4)
    coherence = np.sqrt((jxx-jyy)**2 + 4*jxy*jxy) / np.maximum(jxx+jyy, 1e-6)
    features.extend([coherence, np.sqrt(gx*gx+gy*gy)])
    stack = np.stack(features, axis=-1).astype(np.float32)

    # Erode weak labels to reduce boundary displacement and mixed pixels.
    margin = max(1, round(2.0 / metres_per_pixel))
    fairway = ndimage.distance_transform_edt(masks["fairway"]) > margin
    rough = ndimage.distance_transform_edt(masks["rough"]) > margin
    fairway &= analysis
    rough &= analysis
    overlap = fairway & rough
    fairway &= ~overlap
    rough &= ~overlap
    return {
        "features": stack, "analysis": analysis, "fairway": fairway,
        "rough": rough, "rgb": np.stack([red, green, blue], axis=-1).clip(0,255).astype(np.uint8),
        "metadata": meta, "metres_per_pixel": metres_per_pixel,
    }


def sample_scene(scene, rng, max_per_class=50000, block_px=64):
    y_pos, x_pos = np.where(scene["fairway"])
    y_neg, x_neg = np.where(scene["rough"])
    if min(len(x_pos), len(x_neg)) < 500:
        raise ValueError(f"Not enough eroded weak labels: fairway={len(x_pos)}, rough={len(x_neg)}")

    def pick(y, x):
        if len(x) > max_per_class:
            ids = rng.choice(len(x), max_per_class, replace=False)
            return y[ids], x[ids]
        return y, x

    y_pos, x_pos = pick(y_pos, x_pos)
    y_neg, x_neg = pick(y_neg, x_neg)

    def block_hash(y, x):
        return ((y // block_px) * 73856093 ^ (x // block_px) * 19349663) % 5

    p_test = block_hash(y_pos, x_pos) == 0
    n_test = block_hash(y_neg, x_neg) == 0
    train_y = np.concatenate([y_pos[~p_test], y_neg[~n_test]])
    train_x = np.concatenate([x_pos[~p_test], x_neg[~n_test]])
    test_y = np.concatenate([y_pos[p_test], y_neg[n_test]])
    test_x = np.concatenate([x_pos[p_test], x_neg[n_test]])
    train_labels = np.concatenate([np.ones((~p_test).sum()), np.zeros((~n_test).sum())]).astype(np.uint8)
    test_labels = np.concatenate([np.ones(p_test.sum()), np.zeros(n_test.sum())]).astype(np.uint8)
    return (scene["features"][train_y, train_x], train_labels,
            scene["features"][test_y, test_x], test_labels)


def metrics(y_true, probability):
    prediction = probability >= 0.5
    tn, fp, fn, tp = confusion_matrix(y_true, prediction, labels=[0,1]).ravel()
    return {
        "threshold": 0.5,
        "fairway_recall": round(float(tp / max(tp+fn, 1)), 4),
        "rough_false_positive_rate": round(float(fp / max(fp+tn, 1)), 4),
        "fairway_precision": round(float(tp / max(tp+fp, 1)), 4),
        "f1": round(float(2*tp / max(2*tp+fp+fn, 1)), 4),
        "roc_auc": round(float(roc_auc_score(y_true, probability)), 4),
        "average_precision": round(float(average_precision_score(y_true, probability)), 4),
        "test_pixels": int(len(y_true)),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--train-geojson", required=True)
    ap.add_argument("--train-metadata", required=True)
    ap.add_argument("--train-tiff", required=True)
    ap.add_argument("--train-hole-ref", default="1")
    ap.add_argument("--target-geojson", required=True)
    ap.add_argument("--target-metadata", required=True)
    ap.add_argument("--target-tiff", required=True)
    ap.add_argument("--target-hole-ref", default="1")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    train_scene = load_scene(args.train_geojson, args.train_metadata, args.train_tiff, args.train_hole_ref)
    target_scene = load_scene(args.target_geojson, args.target_metadata, args.target_tiff, args.target_hole_ref)
    rng = np.random.default_rng(20261124)
    x_train, y_train, x_test, y_test = sample_scene(train_scene, rng)
    model = ExtraTreesClassifier(
        n_estimators=180, max_depth=18, min_samples_leaf=20,
        max_features=0.8, class_weight="balanced", n_jobs=-1,
        random_state=20261124,
    )
    model.fit(x_train, y_train)
    holdout_probability = model.predict_proba(x_test)[:, 1]
    holdout_metrics = metrics(y_test, holdout_probability)

    analysis = target_scene["analysis"]
    target_features = target_scene["features"]
    flat_ids = np.flatnonzero(analysis)
    probabilities = np.zeros(analysis.shape, dtype=np.float32)
    batch = 50000
    for start in range(0, len(flat_ids), batch):
        ids = flat_ids[start:start+batch]
        probabilities.ravel()[ids] = model.predict_proba(target_features.reshape(-1, target_features.shape[-1])[ids])[:, 1]
    candidate = (probabilities >= 0.5) & analysis

    # Evaluate only after prediction; these target OSM polygons never train the model.
    target_fairway = target_scene["fairway"]
    target_rough = target_scene["rough"]
    target_eval = {}
    if target_fairway.any():
        target_eval["mapped_fairway_recall"] = round(float(candidate[target_fairway].mean()), 4)
    if target_rough.any():
        target_eval["mapped_rough_false_positive_rate"] = round(float(candidate[target_rough].mean()), 4)
    target_eval["mapped_fairway_pixels"] = int(target_fairway.sum())
    target_eval["mapped_rough_pixels"] = int(target_rough.sum())

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    base = target_scene["rgb"]
    overlay = base.copy()
    tint = np.array([30, 230, 80], dtype=np.float32)
    overlay[candidate] = (0.42*base[candidate] + 0.58*tint).clip(0,255).astype(np.uint8)
    Image.fromarray(overlay, "RGB").save(out / "texture-fairway-candidate.jpg", quality=92)
    score_preview = np.clip(probabilities*255, 0, 255).astype(np.uint8)
    Image.fromarray(score_preview, "L").save(out / "texture-fairway-probability.png")
    importances = sorted(
        [{"feature": name, "importance": round(float(value), 5)}
         for name, value in zip(
             ["red","green","blue","nir","ndvi","excess_green","vari","nir_green_ratio",
              "red_green_ratio","gray_std_3px","gray_std_6px","gray_std_13px",
              "nir_std_3px","nir_std_8px","ndvi_std_5px","texture_coherence","edge_magnitude"],
             model.feature_importances_)],
        key=lambda item: item["importance"], reverse=True,
    )
    report = {
        "method": "ExtraTrees diagnostic trained on spatially held-out OSM fairway/rough seeds with RGBIR, multi-scale local texture, spectral ratios, structure-tensor coherence, and edge magnitude.",
        "training_course_hole": f"{Path(args.train_geojson).parent.name} Hole {args.train_hole_ref}",
        "target_hole_ref": args.target_hole_ref,
        "weak_label_boundary_erosion_m": 2.0,
        "training_samples": {"fairway": int((y_train==1).sum()), "rough": int((y_train==0).sum())},
        "spatial_block_holdout": holdout_metrics,
        "target_weak_label_evaluation_only": target_eval,
        "candidate_area_m2": round(float(candidate.sum()) * target_scene["metres_per_pixel"]**2, 1),
        "candidate_fraction_of_corridor": round(float(candidate.sum()/max(int(analysis.sum()),1)), 4),
        "feature_importance": importances,
        "warning": "OSM labels are incomplete weak labels, not independent ground truth. Spatial block holdout is within one hole/acquisition; cross-hole/course evaluation is diagnostic only. This sklearn model is research-only and is not an Android runtime dependency.",
    }
    (out / "texture-classifier-report.json").write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
