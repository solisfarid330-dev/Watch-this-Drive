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
