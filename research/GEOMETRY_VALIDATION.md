# Aerial geometry validation — Hole 1 at two courses

**Status:** First visual validation pass completed. This is a diagnostic, not a reconstruction-accuracy certification.

## Method

- Selected Hole 1 only where the OSM golf=hole object explicitly carries ref=1.
- Retrieved Texas NAIP 2022 aerial orthophotography from the public Texas Geographic Information Office ImageServer.
- Source resolution: 0.6 m (2 ft) pixels; four-band RGB + near-infrared imagery.
- Exported a 65 m buffer around each hole line at 1600 px width.
- Overlaid OSM tee, fairway, green, bunker, rough and water geometries.
- The updated renderer limits the display to features within a 45 m corridor of the selected hole centerline. It excludes all other hole centerlines.
- Every nearby feature is labelled a **proximity candidate only**. Distance is not proof of hole membership.

## Results

| Diagnostic | South Padre Island GC | Westwood CC |
|---|---:|---:|
| OSM Hole 1 length | 389.9 yd | 530.5 yd |
| Mapped fairway centerline coverage | 55.3% | 53.0% |
| Tee candidates within 45 m | 3 | 6 |
| Fairway candidates within 45 m | 1 | 3 |
| Green candidates within 45 m | 1 | 2 |
| Bunker candidates within 45 m | 4 | 5 |
| Water-hazard candidates within 45 m | 0 | 1 |

Coverage is the sampled proportion of the OSM hole centerline that falls inside any mapped OSM fairway polygon. It is a **coverage diagnostic**, not a measurement of actual fairway area or a statement that the hole line must lie entirely inside the fairway.

### South Padre Island Golf Club

- The OSM centerline measures 389.9 yd. The club's published Hole 1 yardage is 396 yd, a 1.54% difference. This is inside the project's under-5% yardage tolerance.
- The aerial overlay shows the centerline running through the expected tee-to-green corridor.
- The mapped green and four nearby bunker polygons appear to align with visible green/sand features in the 2022 aerial image.
- The mapped fairway polygon covers only 55.3% of the OSM centerline. The aerial image shows a broader mown corridor than the mapped polygon captures. OSM alone is therefore not a complete fairway source at this hole.
- Three tee polygons are nearby candidates. Their presence is useful, but proximity alone does not prove they are all Hole 1 tee boxes.

### Westwood Country Club

- The OSM centerline measures 530.5 yd. No independently verified published Hole 1 reference length has been used here.
- The overlay shows mapped fairway, green and bunker geometries in the same general course corridor as the aerial features.
- There are multiple nearby fairway, green and tee candidates within 45 m. Several are likely associated with adjacent holes. This is a concrete example of why nearest-feature assignment alone is unsafe.
- Mapped fairway polygons cover 53.0% of the Hole 1 centerline. This indicates incomplete or non-centerline-aligned OSM fairway coverage; the aerial image is needed to identify the missing extent.
- One nearby water-hazard polygon is a candidate, not a confirmed Hole 1 hazard.

## Conventional image-processing experiments

The 4-band TIFF export preserves the source RGB + near-infrared bands. Two simple spectral indices were sampled inside OSM feature polygons as weak labels:

- At South Padre, fairway-vs-rough NDVI separation was modest (absolute Cohen's d = 0.47); excess-green separation was stronger (d = 1.10).
- Westwood had no mapped rough seed polygon in this Hole 1 crop, so fairway-vs-rough spectral separability could not be measured there.
- Both courses showed much clearer spectral separation between mapped fairway and sand bunker samples. This is useful for hazard exclusion, but it does not solve the fairway/rough boundary.

A lightweight linear discriminant trained on South Padre fairway/rough pixels achieved 84.8% fairway true-positive rate and 30.1% rough false-positive rate on a spatial block holdout (64×64 exported pixels, approximately 21 m blocks at this crop's export scale). This is more honest than a random pixel split, but still relies on incomplete OSM labels from one hole.

The same South Padre classifier recognized only 7.5% of Westwood's mapped fairway seed pixels. **A fixed cross-course color classifier does not generalize adequately.**

A per-course k-means experiment performed better against the available weak labels:
- South Padre's selected cluster covered 78.6% of mapped fairway seed pixels, with 68.9% precision among fairway/rough seed pixels (F1 = 0.734).
- Westwood's selected cluster covered 77.8% of mapped fairway seed pixels, but there were no mapped rough seed pixels in the crop, so precision could not be measured.
- The resulting candidate masks remain broad/noisy, even after morphological smoothing. They are not accurate enough to promote as reconstructed fairway geometry.

These results support continuing with conventional image processing, but not a one-threshold or one-course-trained solution. Per-course adaptation and stronger spatial constraints are likely necessary. All of these Python experiments are research harnesses only; they are not production dependencies or cloud inference requirements.



## Additional coverage: South Padre Hole 2 and Cottonwood Creek Hole 1

The imagery workflow was extended to a second hole at South Padre and a third
course with sparse OSM coverage.

### South Padre Hole 2

- The hole has an explicit OSM centerline and mapped nearby tee, rough, fairway,
  green, and bunker features. All nearby objects remain proximity candidates.
- The fairway polygon covers approximately 64% of the sampled centerline. This
  differs from Hole 1's 55.3%, showing that the amount of mapped fairway varies
  even within one course.
- A classifier trained on Hole 1 fairway/rough labels recognized 59.5% of Hole 2
  fairway seed pixels and incorrectly marked 10.2% of its mapped rough seed
  pixels. This is materially weaker than its spatial-block validation on Hole 1.
- Per-course clustering on Hole 2 captured 63.2% of mapped fairway seed pixels,
  with 52.6% precision among fairway/rough seed pixels (F1 = 0.574). The Hole 1
  result was F1 = 0.734. The lower score confirms that a visually plausible
  cluster is not a stable fairway boundary method.

### Cottonwood Creek Country Club Hole 1

- NAIP imagery was successfully retrieved and overlaid for a third course.
- The OSM export contains nine hole lines and nine pin nodes, but no mapped
  fairway, tee, green, bunker, or water polygons for this course.
- Two pin nodes fall within 45 m of the Hole 1 centerline (0.0 m and 37.3 m).
  Neither should be assigned to the hole solely by proximity.
- The overlay therefore contains a centerline and candidate pins, not a
  reconstructed hole layout. This is an important sparse-data test: the
  pipeline must distinguish missing map data from absent real-world features.

## Corridor-width sensitivity

Per-course clustering was rerun at 25 m and 35 m on either side of the Hole 1
centerline, in addition to the original 55 m corridor.

| Course / corridor | Fairway seed recall | Precision vs. mapped rough seeds | F1 |
|---|---:|---:|---:|
| South Padre, 25 m | 45.7% | 79.3% | 0.580 |
| South Padre, 35 m | 65.1% | 75.8% | 0.700 |
| South Padre, 55 m | 78.6% | 68.9% | 0.734 |
| Westwood, 25 m | 43.6% | Not measurable | Not measurable |
| Westwood, 35 m | 75.8% | Not measurable | Not measurable |
| Westwood, 55 m | 77.8% | Not measurable | Not measurable |

Westwood has no mapped rough seed pixels in this crop, so the apparent
precision of 100% in raw reports is not a valid estimate; it is simply the
absence of negative labels. Narrowing the corridor trades fairway-seed recall
for better separation from mapped rough at South Padre. None of the resulting
candidate masks has been validated against independently traced fairway
boundaries.

## Updated engineering decision

The data-retrieval and imagery-overlay stages now work on three courses and
four hole examples (South Padre Holes 1 and 2, Westwood Hole 1, and sparse
Cottonwood Hole 1). The remaining gap is not obtaining pixels; it is
reliably turning pixels into fairway geometry.

The experiments so far do **not** justify promoting the spectral or clustering
masks as reconstructed fairways. A one-course classifier transfers poorly to
Westwood and degrades on another hole at its training course. Clustering is
sensitive to corridor width and weak OSM seed labels. The next experiment must
add stronger spatial continuity and shape constraints, then be evaluated
against independently drawn reference boundaries—not merely the same OSM
polygons used to select the clusters.

## What this proves

1. The public NAIP service can be queried programmatically for course-scale imagery without manually downloading or preparing a course image.
2. The OSM geometries can be projected into the image extent and overlaid at a useful visual scale.
3. South Padre's Hole 1 yardage is within the agreed tolerance, and several mapped feature shapes visually correspond to aerial features.
4. OSM fairway mapping is materially incomplete or partial in the tested examples.
5. A simple distance corridor generates useful candidate features, but does not reliably assign features to one hole when several holes are close together.

## What it does not prove

- It does not establish sub-meter positional accuracy. NAIP is 2022 imagery and the comparison is visual.
- It does not prove every bunker/tee/green/pin candidate belongs to the selected hole.
- It does not automatically reconstruct missing fairway boundaries.
- It does not validate every hole or every course.
- It does not demonstrate Android on-device performance.

## Engineering decision

**Do not use OSM fairway polygons as the sole fairway geometry source.** Retain OSM as a strong seed for known features, but treat gaps as unknown. The current classical CV output is only a candidate layer, not accepted geometry. Avoid heavyweight learned segmentation models and do not silently fill uncertain areas.

Hole-feature association also needs more than a fixed distance threshold. Candidate scoring should consider tee-to-green direction, whether a green lies near the centerline endpoint, fairway intersection/continuity, bunker proximity to likely landing/green zones, and ambiguity with neighboring hole corridors.
