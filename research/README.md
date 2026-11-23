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

Requires Python 3.10+; standard library only:

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

This is a **data-discovery and audit prototype**, not the final reconstruction
engine. It now assembles the tested OSM multipolygon relations into GeoJSON, including
inner rings (for example, greens cut out of fairway polygons). It does not yet
infer hole membership for unnumbered features, repair incomplete boundaries,
download aerial imagery, or claim that mapped features match the real course.

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
  License (ODbL). Preserve attribution and comply with ODbL obligations.
- This research prototype makes network requests from the machine running it.
  Python is a research tool here, not a production dependency. The intended
  production implementation remains on-device Android/Kotlin.

## Promotion criteria

The current research branch has live retrieval snapshots for three unrelated
courses discovered by name and location, with no course IDs or coordinates
hardcoded in the extractor:

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

Nine offline unit tests cover boundary query construction, node rejection,
inventory counting, multipolygon outer/inner rings, segmented-ring stitching
in both directions, open hole lines, and GeoJSON polygon winding. The GitHub
Actions test workflow has passed.

Do not port this prototype to Android until a third unrelated course is tested,
relation handling is validated on more complex multipolygons, and geometry is
compared with aerial imagery. The present prototype proves data discovery and
serialization, not reconstruction accuracy or on-device performance.
