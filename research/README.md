# Generic course-data research pipeline

This folder contains an isolated research prototype. It is deliberately separate
from the Android app and does not alter the existing application.

## Goal

Given a golf course name and location, discover its OpenStreetMap golf-course
object, retrieve golf-tagged elements inside its mapped boundary, and produce
both a raw source snapshot and a transparent inventory report.

No course IDs, coordinates, hole numbers, bunker locations, or per-course
exceptions are embedded in the extractor.

## Run

The course-data extractor requires Python 3.10+ and the standard library only.
The optional aerial-image research scripts use Pillow, NumPy, and scikit-image.

\`\`\`bash
python3 research/course_data_audit.py \
  --course "South Padre Island Golf Club" \
  --location "Laguna Vista, Texas, USA" \
  --out output/south-padre
\`\`\`

Repeat with another course simply by changing the two input arguments.

## Output

- \`source-response.json\`: raw Overpass response, exact query, discovered OSM
  candidate, source endpoint, retrieval time, and attribution.
- \`inventory-report.json\`: counts by \`golf=*\` tag and OSM element type,
  explicit hole-reference counts, and geometry-presence diagnostics.

## Current scope and known limitations

This is a **data-discovery and geometry-research prototype**, not the final
reconstruction engine. It assembles tested OSM multipolygon relations into
GeoJSON, including inner rings (for example, greens cut out of fairway polygons).
It can fetch Texas NAIP aerial imagery and create OSM overlays, but it does not
yet reliably infer hole membership for unnumbered features or reconstruct
missing fairway boundaries. Visual overlays are diagnostics, not ground truth.

The prototype intentionally refuses to treat an arbitrary geocoder result or
an OSM node as a usable course boundary. It reports uncertainty instead of
inventing geometry.

OSM element counts are not counts of physical objects. Relations can represent
multipolygons or grouped features; geometry must be assembled from member ways
before area/perimeter calculations are meaningful. Hole assignment must use
explicit source references or a later spatial inference stage with confidence
and ambiguity reporting.

## Data and service notes

- Geocoding: OpenStreetMap Nominatim. Follow its usage policy and rate limits;
  do not send bulk or autocomplete traffic.
- Feature retrieval: public Overpass API instances. They are community
  infrastructure, rate-limited, and not guaranteed to be continuously available.
  The app must handle timeouts, throttling, and endpoint failure gracefully.
- OSM data: © OpenStreetMap contributors, available under the Open Database
  License (ODbL). Preserve attribution and comply with ODbL obligations. The GeoJSON preview
  carries explicit OpenStreetMap attribution and an `ODbL-1.0` license marker.
- This research prototype makes network requests from the machine running it.
  Python is a research tool here, not a production dependency. The intended
  production implementation remains on-device Android/Kotlin.

## Promotion criteria

The current research branch has live retrieval snapshots for three unrelated
courses discovered by name and location, with no course IDs or coordinates
hardcoded in the extractor. It also has first-pass aerial validation for South Padre Holes 1 and 2,
Westwood Hole 1, and Cottonwood Creek Hole 1. The aerial workflow uses the Texas NAIP 2022
four-band ImageServer and commits source imagery, RGBIR TIFFs, OSM overlays,
and diagnostic reports to this branch. The source service identifies its bands
as Red, Green, Blue, and Infrared and reports 0.6 m source pixels.

| Course | OSM golf elements | Mapped features |
|---|---:|---|
| South Padre Island Golf Club | 183 | 18 holes, 18 greens, 49 tees, 42 bunker elements, 17 fairway elements, 14 rough elements, 2 water hazards, 23 cart paths |
| Cottonwood Creek Country Club | 18 | 9 hole lines and 9 pin nodes |
| Westwood Country Club | 201 | 18 holes, 18 greens, 56 tees, 62 bunker elements, 19 fairway elements, 9 water hazards, 18 pin nodes |

These are OSM element counts, not verified counts of physical objects. The
Cottonwood result demonstrates sparse mapping: the extractor reports its nine
mapped holes rather than pretending it has a complete 18-hole layout.

GeoJSON serialization produced 183 features for South Padre (142 polygons and
41 lines) and 201 for Westwood (165 polygons, 18 lines, 18 points). It assembled
all 20 South Padre multipolygon relations and all 15 Westwood multipolygon
relations with zero omissions. A structural validation pass found no open
polygon rings, no uncontained inner rings, and no incorrect ring winding in
those two outputs.

Twenty offline unit tests cover boundary query construction, node rejection,
inventory counting, multipolygon outer/inner rings, segmented-ring stitching,
open hole lines, GeoJSON polygon winding, and conservative endpoint association.
The GitHub Actions research pipeline checks have passed.

The Android-side Kotlin geometry parser now has its own 12-test local JVM test
suite (no emulator required). It includes a captured South Padre Island Overpass
fixture covering a real green polygon, Hole 1 centerline, and a fairway
multipolygon with an inner ring, plus synthetic regression cases for closed
cart paths, route-style hole relations, disconnected/incomplete rings,
multipolygon components, and invalid self-intersecting areas, crossing inner rings, overlapping outer components, and nested inner rings. The Android
parser deliberately rejects ambiguous line/ring assembly and marks partially
invalid relations as unusable rather than presenting them as trusted geometry.

### Aerial validation milestone

The branch now contains repeatable NAIP 2022 RGB and four-band RGBIR exports,
OSM overlays, spectral previews, and fairway-candidate experiments for South
Padre Holes 1 and 2 and Westwood Hole 1. Cottonwood Creek Hole 1 adds a sparse
third-course imagery test with a hole line and pin candidates but no mapped
fairway or hazard polygons. See
[GEOMETRY_VALIDATION.md](GEOMETRY_VALIDATION.md) for the method and findings.

Key findings:
- South Padre Hole 1 OSM length is 389.9 yd versus the club's published 396 yd
  (1.54% difference, inside the project's 5% yardage tolerance).
- A full-course yardage audit now compares all 18 South Padre OSM hole
  centerlines with the back-tee scorecard: 18/18 are within 5%, with the
  largest difference 4.30% (Hole 5). The report records the online Hole 18
  scorecard discrepancy instead of silently treating the club page's
  inconsistent 541-yard entry as correct; the 410-yard value matches the
  6,931-yard total and two independent scorecard sources.
- OSM fairway polygons cover only 55.3% of the South Padre Hole 1 centerline
  and 53.0% at Westwood. The aerials show that OSM alone is incomplete.
- Four nearby South Padre bunker polygons and the green appear visually
  consistent with visible aerial features; assignments remain proximity
  candidates, not confirmed per-hole relationships.
- A South Padre-trained spectral classifier performs poorly when transferred
  to Westwood (7.5% of mapped fairway seed pixels recognized) and degrades on
  South Padre Hole 2 (59.5% fairway-seed recognition, 10.2% rough false positives).
- Per-course unsupervised clustering captures some mapped fairway seed pixels,
  but its Hole 2 F1 falls to 0.574 from 0.734 on Hole 1, and corridor-width
  changes trade recall for precision. The resulting boundaries remain broad/noisy
  and cannot be promoted as accepted geometry.
- A SLIC superpixel experiment with a fairway-seeded connected-component
  filter produces candidate GeoJSON for South Padre Holes 1 and 2. It retains
  83.7% and 87.4% of mapped fairway seed pixels while retaining 0.0% and 1.1%
  of mapped rough seed pixels, respectively. These are OSM-seed agreement
  diagnostics, not independent accuracy scores. The vector outputs are marked
  `unverified_candidate`, are Shapely-validated as valid GeoJSON polygons, and
  are not accepted production geometry.
- Cottonwood demonstrates sparse source coverage: its nine hole lines and nine
  pins are mapped, but no fairway/tee/green/bunker polygons are present. The
  extractor reports that absence rather than inventing those features.
- Endpoint-based association diagnostics infer line direction and identify
  tee/green/pin candidates on 17/18, 18/18, and 0/18 South Padre holes;
  18/18, 18/18, and 18/18 Westwood holes; and 0/9, 0/9, and 9/9 Cottonwood
  holes, respectively. These remain candidates, not confirmed assignments.
- Course-wide exclusivity passes now compare each tee/green/pin candidate
  against every hole endpoint and each fairway/bunker/rough/water/cartpath
  candidate against every hole centerline before reporting a unique-nearest-hole
  candidate. In Westwood, four tee polygons had previously been claimed by
  two nearby holes; the global pass retained each only for the clearly closer
  hole. It reports 31 unique tee candidates, 18 green candidates, and 18 pin
  candidates without duplicate feature claims. South Padre yields 24 tee and
  18 green candidates; Cottonwood yields 9 pin candidates. These are still
  proximity-based candidates, not ground-truth assignments, and near-ties are
  kept explicitly ambiguous. The corridor pass reports unique-nearest
  candidates by feature type: South Padre has 42 bunkers, 17 fairways, 14 rough
  areas, 2 water hazards, and 14 cartpaths; Westwood has 61 bunkers, 19 fairways,
  and 6 water hazards. Westwood also has 7 shared water-hazard candidates that
  remain ambiguous. Cottonwood has no mapped corridor polygons. Shared
  fairway/hazard geometry near multiple holes remains ambiguous rather than
  being forced onto one hole. Corridor distance measures full line segments
  against polygon/line geometry, so crossings between sparse centerline vertices
  are not missed; dedicated regression tests cover polygon crossings, edge
  distance, and cross-hole ambiguity.

Do not port the reconstruction engine to Android yet. The research prototype
proves generic discovery, relation serialization, imagery retrieval, and a
repeatable visual/spectral experiment. It does **not** yet prove generic
fairway reconstruction, reliable hole-feature assignment, accuracy across
many holes, or on-device performance. The next research stage is to validate these candidate polygons against
independently traced reference boundaries and test a seedless fallback for
courses with no mapped fairway polygons before making a go/no-go decision.


## Seed-independent fairway experiment (candidate generation only)

The first seed-independent method selected one SLIC superpixel cluster and
failed to produce meaningful connected candidates on three of four test holes.
That failure is retained in the commit history and reports; it was not hidden.

The next iteration replaced single-cluster selection with a pixel-wise robust
RGBIR score using NDVI, excess green, multi-scale texture, and distance to the
hole centreline. It smooths the score, thresholds within the hole corridor,
and keeps substantial connected components near the line. Fairway and rough
polygons do not influence selection. The four-hole experiment now produces
candidate masks on South Padre Holes 1 and 2, Westwood Hole 1, and sparse
Cottonwood Hole 1.

These are still **not accepted fairway boundaries**. Candidate masks cover
roughly 38–40% of the analysis corridor. Agreement diagnostics against
incomplete OSM labels show substantial mapped-rough overlap on South Padre
(about 45–49%), demonstrating that the candidate is too broad to distinguish
fairway from rough reliably. A threshold sweep confirms the trade-off is poor:
on South Padre Hole 1, raising the threshold from the 58th to the 85th
percentile reduces mapped rough overlap from 49.2% to 24.3%, but mapped fairway
overlap also falls from 85.7% to 33.8%. On Hole 2, the same change reduces rough
overlap from 45.5% to 28.3%, while fairway overlap falls from 84.0% to 20.4%.
Westwood has no mapped rough seed polygons, so its rough false-positive rate
cannot be measured. Cottonwood has no mapped fairway or rough labels, so its
result cannot be scored from OSM. These weak labels remain evaluation-only and
are not independent ground truth.

No candidate is promoted to app geometry. The method must still be checked
against independently traced fairway boundaries and should be rejected when
its confidence or specificity is inadequate.


## Multi-scale texture classifier experiment (2026-11-24)

A new research-only ExtraTrees classifier was tested using 17 RGBIR and local
texture descriptors: four raw bands, NDVI, excess-green, vegetation ratios,
multi-scale RGB/NIR/NDVI local standard deviation, structure-tensor coherence,
and edge magnitude. Weak fairway and rough training masks were eroded by 2 m
to reduce boundary mixing. Training/holdout pixels were separated by spatial
blocks rather than random pixel sampling. OSM target labels were consulted only
after prediction.

The workflow completed successfully on four targets. Its within-hole South
Padre Hole 1 spatial holdout was extremely strong (99.55% fairway recall and
0% rough false-positive rate at a 0.5 threshold). The same model also reported
99.9% mapped-fairway recall and 0% mapped-rough overlap on Hole 1, and 98.92%
mapped-fairway recall with 2.79% mapped-rough overlap on Hole 2.

**These impressive numbers do not mean the method is solved.** The rendered
overlays expose a serious generalization problem: the South Padre Hole 1
prediction spreads across large areas of surrounding turf; the Hole 2 output
also includes broad and fragmented regions. Transfer to Westwood recognizes
only 29.73% of mapped fairway pixels, with no mapped rough polygons available
to estimate false positives. Cottonwood has no mapped fairway/rough labels,
and its prediction includes scattered areas that cannot be independently
scored. Feature importance is dominated by NIR, green, blue and red reflectance;
texture and directional descriptors contribute much less than hoped.

This is a useful negative result: excellent spatial holdout against one hole's
OSM-derived labels can coexist with visually poor boundaries and poor
cross-course transfer. OSM weak labels are incomplete and potentially
systematically biased; the spatial holdout is not independent field truth.
The ExtraTrees/sklearn experiment is strictly an offline diagnostic and is
not an Android dependency or production algorithm.

Next work must establish independent reference boundaries (georeferenced
official hole plans or carefully traced aerial reference polygons), quantify
boundary IoU/precision/recall and width/shape errors, and test whether a
course-adaptive model can transfer without relying on fairway polygons. Until
then, no image-derived fairway candidate is accepted for app geometry.


## Multi-hole, buffered fairway classifier experiment (2026-11-24)

The ExtraTrees diagnostic was expanded to train from South Padre Holes 1 and 2
together. Spatial validation now assigns entire 64-pixel blocks to holdout and
removes a one-block, eight-neighbour halo from training. This is stricter than
random pixel splitting and reduces local spatial leakage. Five regression tests
cover the image-statistic helpers, perfect-separation metrics, whole-block
assignment, buffered holdout separation, and target feature-distribution checks.

The two-hole model was evaluated on both South Padre holes, Westwood Hole 1,
and sparsely mapped Cottonwood Hole 1. It learned a 20 m half-width corridor
from the training fairway seed geometry. South Padre mapped-fairway recall was
97.55% / 96.87% with 0% mapped-rough overlap, but those remain weak-label
agreement scores. The actual masks are still too broad/irregular to accept as
fairway geometry. Westwood mapped-fairway recall fell to 12.05%, and Cottonwood
produced small, unrelated patches around non-fairway facilities. These outputs
show that adding a second training hole and narrowing the corridor do not solve
cross-course generalization.

The buffered within-hole holdout produced aggregate F1 0.9674, fairway recall
93.69%, and rough false-positive rate 0%. Per-hole holdout F1 was 0.9716 on
Hole 1 and 0.9651 on Hole 2. These are still scores against eroded OSM-derived
weak labels, not independent ground truth; they must not be interpreted as
real-world boundary accuracy. The experiment also adds a target-distribution
shift diagnostic that compares target imagery features with the training
feature range. This diagnostic is for detecting when the model is being asked
to extrapolate, not a way to certify predictions.

**Decision: not accepted.** No classifier-generated fairway polygon is
promoted into course geometry or Android. Next work is to quantify target
domain shift and investigate more reliable cues or an abstaining hybrid. Any
solution must be evaluated against independent reference boundaries, including
a course with no mapped fairway/rough seeds. The on-device implementation
constraint remains unchanged; scikit-learn is research-only.

## Additional public data sources: OpenGolfAPI and Ariad

The research branch now has a generic multi-source intake script:

```bash
python3 research/multi_source_course_audit.py \
  --course "Howling Trails Golf Course" --state TX \
  --latitude 26.215 --longitude -98.18 \
  --naip --out output/howling-trails
```

Optional reference coordinates can be supplied when an independent OSM match is already known; otherwise the selected OpenGolfAPI record supplies the imagery center. The script
searches OpenGolfAPI, retrieves the selected course record plus its hole and tee
endpoints, and preserves each response separately in
`opengolfapi-audit.json`. With `--naip`, it requests a four-band Texas NAIP
2022 image from the published 60 cm ImageServer and records the exact imagery
request and attribution. The script does not turn scorecard values into
coordinates or promote image pixels into accepted geometry.

OpenGolfAPI is a useful independent *course-record and scorecard intake* path.
Its published data is ODbL-1.0 and requires the attribution
`© OpenStreetMap contributors (ODbL 1.0) via OpenGolfAPI`; derivative database
obligations must be reviewed before shipping a combined database. OpenGolfAPI
course coordinates and hole/tee metadata should be matched against OSM using
course name plus geographic proximity, not by assuming external IDs equal OSM
IDs. Its published geometry ultimately includes OSM-derived data, so it is not
automatically an independent source of surveyed hole geometry.

Ariad's public course atlas is a useful second *coverage and scorecard
cross-check*: it publishes tee-specific scorecards and maps, and explicitly
shows that map coverage may be partial. Ariad says its map data includes
OpenStreetMap and its directory includes OpenGolfAPI; its maps are
interpretations of published geometry, not surveys. Therefore Ariad should
not be counted as independent geometry where it repeats OSM/OpenGolfAPI.
Use Ariad pages to identify missing coverage or scorecard discrepancies, and
retain the page/source attribution with any manually reviewed result. No
undocumented Ariad endpoint or model weights are assumed by the app.

NAIP is the independent *imagery evidence* source. The Texas 2022 service
provides 60 cm, four-band RGB+infrared imagery. Existing research has already
shown that spectral and texture-only classifiers can overgeneralize turf and
fail to transfer between courses. NAIP pixels must therefore feed a
validated segmentation/reconstruction stage, not be accepted as feature
boundaries merely because a model returns a mask. The new intake script only
retrieves and records imagery; it does not claim production segmentation.

## Android integration status

The Android course-detail flow now queries the current OpenGolfAPI REST contract
for the selected OSM course, accepts only an exact normalized course-name match
within 5 km (or an explicit matching OSM ID), and displays the returned course
record, scorecard, and tee summaries with ODbL attribution. Scorecard hole count
can supply the expected 9/18-hole target, and per-hole par fills only missing par
metadata on already-mapped OSM hole lines. Scorecard yardages remain validation
evidence; they never create, move, or reshape geometry.

The same flow requests a georeferenced aerial preview from the nationwide USGS
NAIP ImageServer. This is a visual evidence layer only; no classifier output is
promoted to course geometry. The Texas-only research imagery path remains useful
for reproducible four-band experiments, while the app's nationwide preview uses
the USGS/USDA public imagery service. Availability and image resolution vary by
location and acquisition vintage.

Ariad remains a reference-only audit input, not an automated Android feed. Its
published atlas does not document a public machine API, and it identifies OSM
and OpenGolfAPI among its data sources. Scraping undocumented endpoints or
counting repeated OSM-derived geometry as independent evidence would make the
pipeline less reliable, not more. Ariad is therefore used to flag coverage and
scorecard discrepancies during research/manual review, with provenance retained.

The production integrations are generic and must never introduce course-name
or course-ID exceptions. Automated segmentation from NAIP and any Ariad model
weights remain gated on independent accuracy validation, redistribution rights,
and on-device performance.
