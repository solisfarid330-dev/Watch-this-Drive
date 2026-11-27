# Real-course Overpass test fixtures

These fixtures are trimmed copies of the `elements` arrays from the course source
responses under `research/results/*/source-response.json`. They retain the
actual OSM element IDs, tags, coordinates, way geometry, and relation member
geometry required to exercise the Android parser offline.

- `south-padre-course-overpass.json`: South Padre Island Golf Club, Laguna Vista, Texas
- `westwood-course-overpass.json`: Westwood Country Club, Houston, Texas
- `cottonwood-creek-course-overpass.json`: Cottonwood Creek Country Club, Harlingen, Texas

Source: © OpenStreetMap contributors, available under the Open Database License
(ODbL). The full retrieval metadata and Overpass query are preserved alongside
the research snapshots. These fixtures are test data only; the app still
retrieves selected-course geometry dynamically and does not hard-code these
courses into production.
