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
    spine_image = Image.new("1", (w, h), 0)
    ImageDraw.Draw(spine_image).line(
        [pixel(p) for p in line], fill=1,
        width=max(1, round(3.0 / metres_per_pixel)))
    spine = np.asarray(spine_image, dtype=bool)
    centerline_distance_m = ndimage.distance_transform_edt(~spine) * metres_per_pixel
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
        centerline_distance_m,
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
        "centerline_distance_m": centerline_distance_m,
    }


def spatial_holdout_mask(y, x, block_px=64):
    """Assign whole spatial blocks to the holdout partition deterministically."""
    return ((y // block_px) * 73856093 ^ (x // block_px) * 19349663) % 5 == 0


def spatial_train_test_masks(y, x, block_px=64, buffer_blocks=1):
    """Split by whole blocks and exclude a one-block halo around holdout blocks."""
    by = y // block_px
    bx = x // block_px
    height = int(by.max()) + 1
    width = int(bx.max()) + 1
    gy, gx = np.mgrid[0:height, 0:width]
    heldout_grid = spatial_holdout_mask(gy, gx, block_px=1)
    buffered_grid = ndimage.binary_dilation(
        heldout_grid, iterations=buffer_blocks, border_value=0
    )
    is_test = heldout_grid[by, bx]
    is_train = ~buffered_grid[by, bx]
    return is_train, is_test


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

    p_train, p_test = spatial_train_test_masks(y_pos, x_pos, block_px)
    n_train, n_test = spatial_train_test_masks(y_neg, x_neg, block_px)
    train_y = np.concatenate([y_pos[p_train], y_neg[n_train]])
    train_x = np.concatenate([x_pos[p_train], x_neg[n_train]])
    test_y = np.concatenate([y_pos[p_test], y_neg[n_test]])
    test_x = np.concatenate([x_pos[p_test], x_neg[n_test]])
    train_labels = np.concatenate([np.ones(p_train.sum()), np.zeros(n_train.sum())]).astype(np.uint8)
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
    ap.add_argument("--additional-train", nargs=3, action="append", default=[], metavar=("METADATA", "TIFF", "HOLE_REF"), help="Additional training hole from the same course GeoJSON; repeatable.")
    ap.add_argument("--target-geojson", required=True)
    ap.add_argument("--target-metadata", required=True)
    ap.add_argument("--target-tiff", required=True)
    ap.add_argument("--target-hole-ref", default="1")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    train_sources = [(args.train_metadata, args.train_tiff, args.train_hole_ref)]
    train_sources.extend(args.additional_train)
    rng = np.random.default_rng(20261124)
    sampled = []
    train_scenes = []
    training_scene_reports = []
    for metadata_path, tiff_path, hole_ref in train_sources:
        scene = load_scene(args.train_geojson, metadata_path, tiff_path, hole_ref)
        train_scenes.append(scene)
        xtr, ytr, xte, yte = sample_scene(scene, rng)
        sampled.append((xtr, ytr, xte, yte))
        training_scene_reports.append({
            "hole_ref": str(hole_ref),
            "training_samples": {"fairway": int((ytr == 1).sum()), "rough": int((ytr == 0).sum())},
            "holdout_labels": yte,
            "holdout_features": xte,
        })
    x_train = np.concatenate([item[0] for item in sampled])
    y_train = np.concatenate([item[1] for item in sampled])
    fairway_seed_distances = np.concatenate([
        scene["centerline_distance_m"][scene["fairway"]]
        for scene in train_scenes
    ])
    learned_half_width_m = float(np.clip(np.quantile(fairway_seed_distances, 0.97) + 5.0, 20.0, 42.0))
    x_test = np.concatenate([item[2] for item in sampled])
    y_test = np.concatenate([item[3] for item in sampled])
    holdout_probabilities = []
    model = ExtraTreesClassifier(
        n_estimators=180, max_depth=18, min_samples_leaf=20,
        max_features=0.8, class_weight="balanced", n_jobs=-1,
        random_state=20261124,
    )
    model.fit(x_train, y_train)
    holdout_probability = model.predict_proba(x_test)[:, 1]
    holdout_metrics = metrics(y_test, holdout_probability)
    for report, (_, _, xte, yte) in zip(training_scene_reports, sampled):
        report["spatial_block_holdout"] = metrics(yte, model.predict_proba(xte)[:, 1])
        report.pop("holdout_labels", None)
        report.pop("holdout_features", None)

    analysis = target_scene["analysis"] & (target_scene["centerline_distance_m"] <= learned_half_width_m)
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
              "nir_std_3px","nir_std_8px","ndvi_std_5px","centerline_distance_m",
              "texture_coherence","edge_magnitude"],
             model.feature_importances_)],
        key=lambda item: item["importance"], reverse=True,
    )
    report = {
        "method": "ExtraTrees research diagnostic trained on spatially blocked and buffered OSM fairway/rough seed pixels from one or more holes, using RGBIR, multi-scale local texture, spectral ratios, centreline distance, structure-tensor coherence, and edge magnitude.",
        "training_course": Path(args.train_geojson).parent.name,
        "training_holes": [str(source[2]) for source in train_sources],
        "training_scene_count": len(train_sources),
        "learned_corridor_half_width_m": round(learned_half_width_m, 2),
        "target_hole_ref": args.target_hole_ref,
        "weak_label_boundary_erosion_m": 2.0,
        "training_samples": {"fairway": int((y_train==1).sum()), "rough": int((y_train==0).sum())},
        "spatial_block_holdout": holdout_metrics,
        "training_scene_reports": training_scene_reports,
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
