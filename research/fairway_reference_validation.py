#!/usr/bin/env python3
"""Validate independent, image-aligned fairway reference masks."""
from __future__ import annotations
import argparse, json
from pathlib import Path
import numpy as np
from PIL import Image
from scipy import ndimage

def load_mask(path, expected_size=None):
    image = Image.open(path)
    if image.mode not in ("1", "L", "I", "I;16", "P"):
        image = image.convert("L")
    array = np.asarray(image)
    if array.ndim != 2:
        raise ValueError(f"Mask must be single-channel: {path}")
    values = set(np.unique(array).tolist())
    if not values.issubset({0, 1, 255}):
        raise ValueError(f"Mask must be binary (0/1/255): {path}")
    if expected_size and (array.shape[1], array.shape[0]) != expected_size:
        raise ValueError(f"Mask size {(array.shape[1], array.shape[0])} != source image size {expected_size}: {path}")
    return array > 0

def validate_annotation(manifest_path):
    path = Path(manifest_path)
    data = json.loads(path.read_text())
    required = ("schema_version", "course", "hole_ref", "source_image", "source_image_sha256",
                "image_width", "image_height", "ground_sample_distance_m", "annotation_status",
                "annotator", "created_utc", "masks", "provenance")
    missing = [key for key in required if key not in data]
    if missing:
        raise ValueError(f"Missing manifest fields: {', '.join(missing)}")
    if data["schema_version"] != 1:
        raise ValueError("Unsupported annotation schema_version")
    if data["annotation_status"] not in ("pending", "draft", "needs_review", "accepted", "rejected"):
        raise ValueError("Invalid annotation_status")
    if data["annotation_status"] == "accepted" and not data.get("reviewed_by"):
        raise ValueError("Accepted annotations require reviewed_by")
    if not data["provenance"].get("independent_of_model", False):
        raise ValueError("Reference provenance must explicitly state independence from model output")
    if data["provenance"].get("used_as_model_input", True):
        raise ValueError("Reference masks must never be model inputs")
    gsd = data["ground_sample_distance_m"]
    if not isinstance(gsd, (int, float)) or gsd <= 0:
        raise ValueError("ground_sample_distance_m must be positive")
    size = (int(data["image_width"]), int(data["image_height"]))
    loaded = {}
    for role in ("fairway", "valid", "uncertain"):
        filename = data["masks"].get(role)
        if not filename:
            raise ValueError(f"Missing {role} mask path")
        loaded[role] = load_mask(path.parent / filename, size)
    if np.any(loaded["fairway"] & ~loaded["valid"]):
        raise ValueError("Fairway mask extends outside valid target-hole scope")
    if np.any(loaded["uncertain"] & ~loaded["valid"]):
        raise ValueError("Uncertainty mask extends outside valid target-hole scope")
    if np.any(loaded["fairway"] & loaded["uncertain"]):
        raise ValueError("Fairway and uncertain masks overlap")
    return {"manifest": data, "masks": loaded}

def score_masks(reference, prediction, valid, uncertain=None,
                boundary_tolerance_m=1.2, ground_sample_distance_m=0.6):
    if reference.shape != prediction.shape or reference.shape != valid.shape:
        raise ValueError("Reference, prediction and valid masks must have identical shapes")
    if uncertain is None:
        uncertain = np.zeros_like(valid, dtype=bool)
    if uncertain.shape != valid.shape:
        raise ValueError("Uncertain mask must match other mask dimensions")
    if boundary_tolerance_m <= 0 or ground_sample_distance_m <= 0:
        raise ValueError("Boundary tolerance and ground sample distance must be positive")
    scored = valid & ~uncertain
    ref, pred = reference & scored, prediction & scored
    tp = int(np.count_nonzero(ref & pred))
    fp = int(np.count_nonzero(~ref & pred & scored))
    fn = int(np.count_nonzero(ref & ~pred & scored))
    union = tp + fp + fn
    precision = tp / (tp + fp) if tp + fp else (1.0 if tp + fn == 0 else 0.0)
    recall = tp / (tp + fn) if tp + fn else (1.0 if tp + fp == 0 else 0.0)
    dice = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    ref_edge = ref ^ ndimage.binary_erosion(ref, structure=np.ones((3, 3)), border_value=0)
    pred_edge = pred ^ ndimage.binary_erosion(pred, structure=np.ones((3, 3)), border_value=0)
    tolerance_px = boundary_tolerance_m / ground_sample_distance_m
    if ref_edge.any() and pred_edge.any():
        dist_ref = ndimage.distance_transform_edt(~ref_edge)
        dist_pred = ndimage.distance_transform_edt(~pred_edge)
        bp = float((pred_edge & (dist_ref <= tolerance_px)).sum() / pred_edge.sum())
        br = float((ref_edge & (dist_pred <= tolerance_px)).sum() / ref_edge.sum())
        bf = 2 * bp * br / (bp + br) if bp + br else 0.0
        boundary_distance = float((dist_ref[pred_edge].mean() + dist_pred[ref_edge].mean()) / 2 * ground_sample_distance_m)
    else:
        bp = br = bf = 0.0
        boundary_distance = None
    pixel_area = ground_sample_distance_m ** 2
    ref_area, pred_area = int(ref.sum()) * pixel_area, int(pred.sum()) * pixel_area
    return {
        "scored_pixels": int(scored.sum()), "excluded_uncertain_pixels": int((valid & uncertain).sum()),
        "true_positive_pixels": tp, "false_positive_pixels": fp, "false_negative_pixels": fn,
        "iou": tp / union if union else 1.0, "dice": dice, "precision": precision, "recall": recall,
        "boundary_tolerance_m": boundary_tolerance_m, "boundary_precision": bp,
        "boundary_recall": br, "boundary_f1": bf,
        "symmetric_mean_boundary_distance_m": boundary_distance,
        "reference_area_m2": ref_area, "predicted_area_m2": pred_area,
        "area_bias_m2": pred_area - ref_area, "false_positive_area_m2": fp * pixel_area,
        "missed_fairway_area_m2": fn * pixel_area, "ground_sample_distance_m": ground_sample_distance_m
    }

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotation", required=True)
    parser.add_argument("--prediction", required=True)
    parser.add_argument("--out")
    parser.add_argument("--boundary-tolerance-m", type=float, default=1.2)
    args = parser.parse_args()
    item = validate_annotation(args.annotation)
    manifest, masks = item["manifest"], item["masks"]
    if manifest["annotation_status"] != "accepted":
        raise SystemExit("Refusing to score: annotation is not accepted by an independent reviewer")
    prediction = load_mask(args.prediction, (manifest["image_width"], manifest["image_height"]))
    report = score_masks(masks["fairway"], prediction, masks["valid"], masks["uncertain"],
                         args.boundary_tolerance_m, manifest["ground_sample_distance_m"])
    report.update({"course": manifest["course"], "hole_ref": manifest["hole_ref"],
                   "annotation_status": manifest["annotation_status"], "source_image": manifest["source_image"],
                   "source_image_sha256": manifest["source_image_sha256"]})
    encoded = json.dumps(report, indent=2) + "\n"
    if args.out:
        Path(args.out).write_text(encoded)
    print(encoded, end="")

if __name__ == "__main__":
    main()
