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

## What this proves

1. The public NAIP service can be queried programmatically for course-scale imagery without manually downloading or preparing a course image.
2. The OSM geometries can be projected into the image extent and overlaid at a useful visual scale.
3. South Padre's yardage is within the agreed tolerance, and several mapped feature shapes visually correspond to aerial features.
4. OSM fairway mapping is materially incomplete in both tested Hole 1 examples.
5. A simple distance corridor generates useful candidate features, but does not reliably assign features to one hole when several holes are close together.

## What it does not prove

- It does not establish sub-meter positional accuracy. NAIP is 2022 imagery and the comparison is visual.
- It does not prove every bunker/tee/green candidate belongs to the selected hole.
- It does not automatically reconstruct missing fairway boundaries.
- It does not validate every hole or every course.
- It does not demonstrate Android on-device performance.

## Engineering decision

**Do not use OSM fairway polygons as the sole fairway geometry source.** Retain OSM as a strong seed for known greens, bunkers, tees, water and some fairway extents, but treat gaps as unknown.

The next research step is to test conventional image processing against the aerial imagery: color/texture segmentation, vegetation/mown-turf region extraction, and spatial constraints from known tee/green/centerline geometry. Compare inferred polygons with visible boundaries and report confidence. Avoid heavyweight learned segmentation models and do not silently fill uncertain areas.

Hole-feature association also needs more than a fixed distance threshold. Candidate scoring should consider tee-to-green direction, whether a green lies near the centerline endpoint, fairway intersection/continuity, bunker proximity to likely landing/green zones, and ambiguity with neighboring hole corridors.
