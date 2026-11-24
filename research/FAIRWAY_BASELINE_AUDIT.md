# Fairway reconstruction baseline audit

**Date:** 2026-11-24  
**Branch:** `research/generic-course-pipeline`  
**Audited branch head at start of audit:** `f6b9278e4cefabc51800433a12e25d069b023ce5`  
**Scope:** Reproduce and inspect the currently committed fairway-reconstruction evidence. This is an audit of the available baseline, not a claim that the segmentation problem is solved.

## Executive finding

The current pipeline reliably retrieves NAIP imagery and creates diagnostic candidate masks, but the available evidence does **not** establish accurate fairway-boundary reconstruction. The central failure is semantic: the seed-independent RGBIR score responds to green, relatively smooth turf and proximity to the hole line, but cannot consistently distinguish fairway from rough. The classifier experiments also have weak cross-course transfer, and the latest normalized-reflectance experiment has no successful completed run/results in the audited branch state.

**Decision: do not promote any current candidate polygon as trusted fairway geometry and do not port this segmentation method into the Android app.**

## What was actually tested

The branch contains imagery and OSM overlays for four hole examples across three courses:

- South Padre Island Golf Club: Holes 1 and 2
- Westwood Country Club: Hole 1
- Cottonwood Creek Country Club: Hole 1

This is four holes, not four independent courses. Cottonwood is a sparse-data case: its OSM snapshot has hole lines and pins but no mapped fairway/rough/tee/green/bunker polygons. It is useful for checking that missing labels remain missing, but cannot provide a fairway accuracy score.

All aerial examples use Texas NAIP 2022 RGB+NIR imagery. The source resolution is approximately 0.6 m, with exported crop scales varying by hole.

## Reproduced seed-independent baseline

The committed `naip_seedless_fairway.py` method scores per-pixel NDVI, excess green, multi-scale texture, and distance from the hole centerline; smooths the score; thresholds within a hole corridor; and retains connected components near the line. Fairway and rough polygons are not used to select the candidate. Mapped greens, bunkers, water, and tees are used as exclusions.

Committed reports were inspected for all three course examples:

| Hole | Candidate share of corridor | Mapped fairway overlap | Mapped rough overlap | Interpretation |
|---|---:|---:|---:|---|
| South Padre 1 | 38.6% | 86.3% | 49.1% | Too much mapped rough included |
| Westwood 1 | 37.6% | 63.9% | Not measurable | No mapped rough negatives; precision cannot be inferred |
| Cottonwood 1 | 40.1% | Not measurable | Not measurable | No mapped fairway or rough labels; candidate is unscored |

The South Padre Hole 1 threshold sweep exposes the fundamental trade-off. At the 58th score percentile, candidate overlap is 85.7% with mapped fairway but also 49.2% with mapped rough. At the 85th percentile, mapped rough overlap falls to 24.3%, but mapped fairway overlap falls to 33.8%. Tightening the threshold does not isolate the fairway; it discards much of the mapped fairway too.

These overlaps are weak-label agreement diagnostics against incomplete OSM polygons. They are not independent precision/recall against actual fairway boundaries.

## Earlier experiments and transfer evidence

The geometry validation notes and reports show:

- A South Padre Hole 1 spectral classifier had a 30.1% rough false-positive rate in spatial-block holdout, despite 84.8% fairway true-positive rate.
- The same fixed classifier recognized only 7.5% of Westwood mapped fairway seed pixels.
- A per-course clustering experiment on South Padre fell from F1 0.734 on Hole 1 to 0.574 on Hole 2.
- The fairway-seeded SLIC experiment retained 83.7% and 87.4% of mapped fairway seeds on South Padre Holes 1 and 2, with 0.0% and 1.1% mapped-rough seed overlap. However, it explicitly requires mapped fairway seed geometry, so it cannot reconstruct a course like Cottonwood with no fairway seed. Its scores also measure agreement with the seed labels, not independent boundary accuracy.

The seed-independent method avoids the fairway-seed dependency but returns to high rough overlap. The seed-dependent method reduces rough overlap but fails the sparse-map requirement. Neither currently meets the generic requirement.

## Workflow reproduction and CI status

At audit time, the latest branch history showed:

- Research pipeline checks run 140: **success** on commit `a29fae431cbe0abe2981ba76b9320a11e9d5bfdf`.
- Aerial geometry validation run 69: **success** on the same commit.
- Texture classifier validation run 20: **failure** on commit `f4a3f18986134ec8a5eb0bc37fc5dda59f1e1137`.
- Texture classifier validation run 19: **cancelled**.
- Re-running failed jobs for run 20 produced attempt 2, which failed again within seconds. GitHub returned no step records or usable job logs for that attempt, so the exact failure cause is not established from the available connector evidence.

The workflow was recently expanded to include scene-normalized radiometry runs for South Padre Holes 1 and 2, Westwood, and Cottonwood. Those results are not present as completed output directories in the audited branch snapshot. Therefore, normalized-radiometry transfer is **not yet evaluated**; it must not be described as successful or unsuccessful based on this run.

## Root-cause assessment

The evidence points to three separate limitations:

1. **Fairway/rough are not separable by the current features alone.** NDVI and excess-green capture vegetation condition and colour; texture helps, but mowing patterns, turf species, season, shade, irrigation, and image radiometry vary. Fairway and rough are both maintained grass and can overlap spectrally.
2. **Centerline proximity is a weak prior, not a boundary model.** It can keep candidates near the hole, but cannot infer the lateral fairway edge or varying widths. A fixed corridor-local percentile forces a chosen area fraction even when the score distribution does not contain a meaningful fairway/rough split.
3. **Evaluation data are inadequate for acceptance.** OSM polygons are incomplete and are used as weak labels; Westwood lacks mapped rough negatives; Cottonwood has no fairway labels. There are no independently traced reference boundaries or trustworthy boundary-level metrics in the current evidence.

## Audit conclusion and acceptance gate

The baseline reproduction is complete from committed artifacts and reports. The next algorithm iteration should not begin by tuning another percentile. It needs independent fairway/rough reference masks on representative holes, including a sparse-OSM hole, and must report boundary metrics (for example IoU/Dice plus boundary-distance or tolerance-based F-score), area bias, and false-positive area outside the fairway. Evaluation splits must be spatially separated and include a held-out course.

Until those references exist, the system can be evaluated only as candidate generation and data-quality reporting. It must label uncertain geometry as unverified and must not invent or silently fill fairway boundaries.

No Android production code was changed as part of this audit.
