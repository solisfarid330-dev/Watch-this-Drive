# Course data integration and performance review

Reviewed branch: `feature/course-hole-artwork-generation`
Reviewed commit: `34ccf0dd87ebe6e07cb0af5fdd84c2abbb176418`

## Executive summary

The app already has a working architectural starting point: OSM course discovery, OSM boundary/feature retrieval, local geometry caching, hole reconstruction, an Android artwork renderer, a satellite map, and GPS-aware Caddy UI. The external sources discussed with the user are not yet integrated into a unified course-data layer.

The next step should be a local-first course catalog and a shared course-intelligence repository. Course directory records, mapped geometry, imagery, and inferred features must remain separate data layers with explicit provenance.

## Findings in the current code

### 1. Course discovery is OSM-only

`data/CourseDiscovery.kt`:
- `GolfCourse` stores only a numeric ID, OSM element type, name, coordinates, and distance.
- Nearby discovery queries Overpass for OSM `leisure=golf_course` nodes, ways, and relations.
- Text search geocodes the text through Nominatim and then searches a 15 km OSM radius. It is not a direct golf-course-name search.
- The model cannot represent OpenGolfAPI IDs, TeeTime.AI UUIDs, address, city/state/country, hole count, par, scorecard, source provenance, or multiple provider matches.

Recommendation: introduce a richer course record and a source-aware catalog repository. Keep provider identifiers and raw provenance rather than replacing one source's identity with another.

### 2. OpenGolfAPI dataset is not in the app

The uploaded US GeoJSON snapshot has been inspected separately. It provides useful US course points and metadata, including hole par/handicap where available, but it does not provide hole polygons or course feature geometry.

Recommendation:
- Import a versioned OpenGolfAPI snapshot into a local indexed catalog.
- Verify the published SHA-256 before importing.
- Keep source version, attribution, and record identifiers.
- Use it for course discovery and scorecard metadata, not hole-map geometry.
- Refresh in the background only when a newer dataset is available; do not issue one API request per course.

### 3. TeeTime.AI is another directory source, not a geometry replacement

Its published data endpoint offers an active-course JSON/CSV dump and a paginated API. The published API example includes course UUID, canonical name, coordinates, country, admin region, city, holes, par, type, and status. Its own page reports 42,133 active courses, 29,749 with coordinates, and 3,372 candidates under review.

Recommendation:
- Treat active records and candidate records separately.
- Preserve the per-course source list and the dataset's attribution.
- Use the dump as a refreshable catalog input, not a live network dependency on every Home search.
- Because TeeTime.AI aggregates OSM and Wikipedia sources, do not treat its rows as independent confirmation of OSM.
- Preserve ODbL and CC-BY-SA attribution and review share-alike obligations before distributing a combined derived database.

### 4. OSM geometry acquisition is useful but expensive and fragile

`data/CourseGeometry.kt`:
- Fetches a selected boundary, then retrieves features from an expanded bounding box.
- Uses three Overpass endpoints sequentially, each with long timeouts.
- Uses a coordinate-box fallback when no usable boundary is available; that fallback has weaker isolation and can include neighboring features.
- The loader starts a new raw thread for each request and has no in-flight request deduplication or cancellation contract.
- The cache is a JSON string in SharedPreferences, keyed only by numeric course ID. This can collide across OSM element types and is not a good store for large geometry documents.

Recommendation:
- Introduce a repository with bounded IO concurrency, request deduplication, cancellation, retry/backoff, and explicit cache freshness.
- Key geometry by provider + element type + element ID.
- Store large geometry as compressed files or in a spatially appropriate local database; keep only cache metadata in preferences.
- Keep boundary-less results explicitly lower confidence and do not silently claim they are course-scoped.
- Reuse one geometry request/result across Course Detail, Play, and map surfaces.

### 5. NAIP is not yet a complete Android data source

The repository contains Python imagery-candidate prototypes and tests, but the Android app does not currently acquire NAIP imagery, store its imagery metadata, or run the candidate extraction pipeline. The existing Python code deliberately labels spectral outputs as low-confidence, unreviewed candidates rather than semantic golf features.

Recommendation:
- Add a US-only NAIP imagery provider behind the course-intelligence repository.
- Acquire only a clipped, appropriately sized image for a selected course when OSM coverage is insufficient; do not bulk-download imagery for every directory record.
- Record imagery acquisition date, provider, resolution, attribution, and processing version.
- Use RGB/NIR and spatial context to propose broad candidates only. Do not label vegetation as fairway/green or bare ground as bunker without corroborating geometry or review.
- Keep the satellite map as a visual reference and preserve the geometry-only fallback.

### 6. Hole reconstruction is duplicated and can be costly

`data/HoleReconstruction.kt` contains meaningful work to preserve mapped centerlines and fairway shapes. It also performs spatial matching and fairway-alignment calculations. The same reconstruction is currently requested from multiple UI paths:
- MainActivity scorecard-par synchronization (two separate effects currently reconstruct the same geometry).
- CaddyScreen.
- CourseHoleArtworkPanel.

Recommendation:
- Calculate reconstructed holes once per geometry version in a shared state/repository.
- Pass the result to scorecard, Caddy, and artwork.
- Profile and optimize the reconstruction algorithm only after duplicate calls are removed.
- Keep mapped fairways distinct from estimated corridors and preserve ambiguity/provenance.

### 7. Artwork loading needs stronger failure handling

`ui/CourseHoleArtwork.kt`:
- The general artwork panel catches no rendering exception, although it resets its spinner in a finally block.
- The Play/Caddy artwork loader has no try/finally around the asset load, render, and bitmap decode sequence. An exception can leave the UI in a permanent loading state.
- The renderer is shared conceptually but the same hole may be requested by more than one composable.

Recommendation:
- Always reset loading state in finally and surface a safe fallback on failure.
- Deduplicate render work by stable course + hole + geometry hash + renderer version.
- Decode images off the main thread and bound bitmap memory.
- Retain the existing schematic/GPS-aware fallback until generated art is reliable.

### 8. Map and location behavior

`ui/GolfMap.kt` already updates the player GeoJSON source rather than rebuilding its layers on every GPS sample, and camera movement is separated from marker updates. Preserve this improvement.

Further work:
- Verify full MapView lifecycle handling against the MapLibre Android lifecycle.
- Keep GPS sampling and map redraw rates bounded; do not animate the camera for every location update.
- Keep the current honest distance target: the app calculates to a mapped green center, not a live pin position. A real pin requires a separate pin-location source or manual pin placement.
- Ensure course-feature rendering and geometry parsing stay off the UI thread for large courses.

## Proposed architecture

### Source layers

1. **Course catalog:** OpenGolfAPI + TeeTime.AI + OSM course identities. Local indexed search by name, region, and coordinates.
2. **Course geometry:** OSM boundaries, tees, greens, fairways, centerlines, bunkers, water, paths, rough, woodland.
3. **Imagery:** NAIP only where available and useful; imagery metadata and source terms are retained.
4. **Reconstruction:** one shared, versioned reconstructed-hole result with mapped/inferred provenance and confidence.
5. **Presentation:** one shared artwork package/cache consumed by Play and any course-detail hole view; GPS map remains available when artwork is absent.

### Course matching

Use stable provider IDs first. When IDs differ, match candidates with normalized names, coordinate proximity, city/region, and source evidence. Never merge solely on name. Keep ambiguous duplicates visible for correction rather than collapsing distinct facilities.

### Local-first behavior

- Course directory updates happen in the background and are cached.
- Course search should work offline from the last imported catalog.
- OSM geometry is fetched only after a course is selected, then cached.
- NAIP is acquired lazily for the selected US course, not during broad course search.
- Artwork is generated once per geometry/renderer version and reused.
- Network failure must not block opening a cached course or the Play screen.

## Implementation order

1. Fix artwork loading failures and ensure fallback behavior.
2. Remove duplicate hole reconstruction and share reconstructed results.
3. Introduce the source-aware course model and local catalog repository.
4. Import OpenGolfAPI and TeeTime.AI data with validation, deduplication, and attribution.
5. Replace the geometry SharedPreferences blob with a versioned disk cache and deduplicate Overpass requests.
6. Connect NAIP acquisition to the existing low-confidence imagery-candidate pipeline.
7. Validate hole association and fairway alignment against Lake Arlington Hole 1, then test South Padre and additional courses.
8. Run Python tests, Android unit tests, CI APK build, and on-device GPS/artwork/performance checks.

## Important constraints

- All work remains on `feature/course-hole-artwork-generation`; do not modify `main`.
- No per-course hardcoding and no required backend processing.
- No scraping Google Maps tiles.
- Keep carry-only club distances and the simple distance-based Caddy.
- Never present inferred imagery candidates as surveyed course geometry.
- Preserve source attribution and applicable dataset licenses.
- A successful CI build is not proof that GPS, MapLibre rendering, or generated artwork works correctly on the user's phone.


## Implementation progress (November 2026)

The following items have now been implemented on `feature/course-hole-artwork-generation`:

- A source-aware `GolfCourse` model now carries provider IDs, location metadata, hole count, par, scorecard holes, contact details, and source names.
- Nearby course discovery combines OSM with OpenGolfAPI and TeeTime.AI catalog records. Provider calls run concurrently with OSM discovery.
- Direct course-name search queries OpenGolfAPI and TeeTime.AI, while geocoded OSM/region search runs alongside it.
- TeeTime.AI results are limited to active records; candidate records are not promoted into the playable course list.
- Provider results are deduplicated by normalized name and proximity, and source names are retained when records are merged.
- The verified OpenGolfAPI v2.1.0 US GeoJSON is downloaded during APK builds, checked by compressed and uncompressed SHA-256, and bundled as an app asset.
- The bundled US catalog is imported on demand into an indexed SQLite database using a streaming JSON reader. Nearby US course searches can therefore work from the local catalog without a network request.
- Available catalog par and handicap values are used as scorecard defaults when the user has not already saved a value.
- Course geometry cache storage has moved from large SharedPreferences JSON strings to compressed, type-specific files under app storage. Legacy preference cache data remains readable for migration.
- Concurrent geometry requests for the same provider element are deduplicated, and geometry downloads are bounded to two concurrent requests.
- Catalog course points now resolve to OSM boundaries by preferring exact course-name matches and only using a close-distance fallback when names do not match.
- A cached NAIP aerial-reference image can be fetched lazily for selected CONUS courses and displayed on Course Detail. It is clearly labeled as imagery, not surveyed golf geometry.
- Attribution for OpenGolfAPI, TeeTime.AI, OSM and NAIP is surfaced in Settings.
- Artwork asset loading and rendering paths now reset loading state on failure and preserve the fallback view.

### Still outstanding

- Verify the latest full build and add runtime/device validation for first-run SQLite import, nearby discovery, direct name search, compressed geometry cache migration, and NAIP image export.
- The TeeTime.AI MCP name-search response parser is defensive but needs a live-device/API response test; the regional REST catalog remains the primary TeeTime path for nearby search.
- NAIP currently provides an RGB aerial reference preview. It is not yet used for automatic semantic fairway/green/bunker extraction. The existing Python spectral candidate pipeline remains low-confidence and is not wired into Android.
- Improve course-to-hole geometry association and fairway alignment using a broader test set; exact OSM coverage and data quality remain variable.
- Profile renderer, geometry parsing and map layer updates on the user's Android device.
- The app still measures distance to the mapped green center, not a live pin, unless a pin position is separately supplied.

A passing CI build validates compilation and unit tests only. It does not validate network provider behavior, Android SQLite import timing, GPS accuracy, MapLibre lifecycle, or on-device artwork quality.


Additional performance and data-quality refinements:
- Hole reconstruction now runs on `Dispatchers.Default` in Play and Course Detail; Caddy requires and reuses the shared result rather than reconstructing during recomposition.
- Live GPS updates are throttled to a 3-second target interval with a 2.5-metre movement threshold to reduce main-thread and map churn. The explicit fresh-location action remains available.
- Course records use collision-resistant stable provider IDs, and saved round/scorecard preferences use a canonical provider identity so switching between a catalog point and its matched OSM boundary does not split a golfer's scores.
- Total course yardage is retained and displayed when a provider supplies it; hole yardage is shown only when the provider actually provides a per-hole value.
- The offline catalog is generated as a build asset and verified before Android asset merging; packaging is being checked inside the APK, not inferred from a successful build.


Local build prerequisite: run `bash scripts/download_opengolf_catalog.sh` before invoking Gradle. The script verifies both the compressed release asset and the uncompressed GeoJSON content; CI runs it before Gradle configures Android assets.
