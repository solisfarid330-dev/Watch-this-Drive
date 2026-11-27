# Independent fairway reference dataset

**Status: annotation protocol and evaluator implemented; reference masks are not yet accepted.** No model-generated boundary or incomplete OSM polygon is relabelled as ground truth.

## Separation and mask format

The reference set measures fairway-boundary reconstruction against independently annotated aerial imagery. It is evaluation-only. Reconstruction code must not read reference manifests or masks during candidate generation, feature extraction, training, threshold selection, or tuning.

Each hole has three image-aligned binary PNG masks:

- fairway: visible fairway interior only.
- valid: confidently annotatable pixels belonging to the target hole; excludes adjacent holes, roads, buildings, out-of-crop pixels, and unresolved hole membership.
- uncertain: pixels within valid scope where turf identity or the fairway edge cannot be judged reliably. Excluded from scoring, never silently assigned.

Masks must exactly match source dimensions and contain only 0/255 values. The JSON manifest records source image SHA-256, dimensions, GSD, course/hole, annotator, reviewer, timestamp, status, and provenance. Accepted annotations require a separate reviewer.

## Annotation protocol

1. Trace the native-resolution source NAIP RGB image. Do not show model candidates, heatmaps, OSM fairway/rough polygons, or prior segmentation to the annotator.
2. OSM may be consulted only after the blind trace as a discrepancy check; it must not determine or overwrite the reference edge.
3. Trace visible maintained fairway, excluding rough, collar, green, tee, bunker, path, water, and tree canopy. Mark ambiguous mowing edges uncertain.
4. Annotate only the target hole. Adjacent fairways/shared corridors are outside valid scope unless ownership is unambiguous.
5. Do not extrapolate under trees, shadows, buildings, image gaps, or beyond the crop.
6. A second reviewer independently inspects the trace. Resolve disagreements by marking disputed strips uncertain, not averaging boundaries.
7. Preserve source imagery and record SHA-256. Never resize or crop masks independently.
8. Freeze accepted masks before model comparison. Corrections create a new revision and require rerunning all methods.

## Pilot suitability

| Course / hole | Crop | Status | Issue |
|---|---:|---|---|
| South Padre Island GC 1 | 1600 × 508 | pending | Fairway visible; blind trace and independent review required |
| South Padre Island GC 2 | 1600 × 885 | pending | Native-resolution target scope inspection required |
| Westwood CC 1 | 1600 × 556 | pending / scope review | Adjacent holes and shared turf require target-only valid mask |
| Cottonwood Creek CC 1 | 639 × 1100 | unsuitable pending better imagery | Current crop is dominated by facilities/grounds and does not show a complete target fairway |

These are four holes across three courses, not four independent courses. South Padre holes 1 and 2 are within-course samples. Westwood is the only plausible held-out course here, but it cannot be used until its target-hole reference is accepted. Cottonwood currently demonstrates imagery-coverage failure, not segmentation accuracy.

## Evaluator

Run research/fairway_reference_validation.py. It validates mask dimensions, binary values, scope containment, provenance, and fairway/uncertainty non-overlap. It refuses scoring unless annotation_status is accepted and reviewed_by is present.

Metrics on valid AND NOT uncertain pixels:

- IoU, Dice, precision, recall.
- Boundary precision/recall/F1 within 1.2 m by default (about two 0.6 m NAIP pixels).
- Symmetric mean boundary distance.
- Reference/predicted area, signed area bias, false-positive area, missed-fairway area.

Install numpy, scipy, Pillow. Run:

    python research/fairway_reference_validation.py --annotation path/to/annotation.json --prediction path/to/prediction-mask.png --out comparison.json
    python -m unittest discover -s research -p "test_*.py" -v

Predictions and references must be binary PNGs with exactly matching source dimensions. Reference masks are not passed to reconstruction code.

## Acceptance gate

Do not tune/select a model on these masks until:
- Two independent annotators/reviewers have completed South Padre Hole 1 and resolved disagreement.
- South Padre Hole 2 has an accepted annotation.
- Westwood has an accepted target-only annotation for a held-out-course test.
- The target is sufficiently visible; otherwise acquire better imagery rather than extrapolating.
- Accepted mask revisions and source hashes are frozen.

If any pixels later become training data, move them into a separately versioned training set and keep held-out evaluation untouched.
