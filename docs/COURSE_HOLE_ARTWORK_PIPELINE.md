# Generic course-hole artwork pipeline

## Product requirement

Every course selected in Watch this Drive must be eligible for automatic hole-artwork generation. Course names, OSM IDs, coordinates, geometry, and feature availability are runtime inputs. No course-specific constants, prebuilt course atlas, or APK rebuild may be required when a golfer selects a different course.

The same generated artwork package is consumed by **Illustrated Holes** and **Playing Caddy**. Caddy retains its GPS-aware reconstructed-map fallback whenever artwork is unavailable.

## Current implementation status

- The Python geometry and vector-rendering modules are course-independent.
- OSM association and tee-to-green reconstruction retain ambiguity and mark inferred geometry as provisional.
- Android currently loads packaged artwork assets. This is useful for validating the manifest/image contract, but it is **not** the final universal runtime-generation architecture.
- The Caddy screen now requests the selected hole from the same artwork repository used by Illustrated Holes and falls back to its existing live reconstructed map if no matching image exists.
- The APK workflow still contains a Lake Arlington pilot packaging step. It is a temporary fixture/demo path and must not be treated as proof that arbitrary selected courses are generated on demand.
- A hosted generation service and Android runtime download/cache integration are not yet implemented. Do not describe the feature as globally available until those pieces are deployed and tested.

## Runtime architecture to implement

### 1. Course identity and geometry acquisition

Use the selected `GolfCourse` identity and coordinates as input. Resolve the course boundary and collect relevant geometry with generic spatial queries. Support OSM nodes, ways, and relations where they represent a usable course identity; a node alone is not a reliable course boundary, so resolve a surrounding course polygon before reconstructing holes.

Collect available:
- hole centerlines and hole-outline polygons;
- tee/teeing-ground and green geometry;
- fairways and rough;
- bunkers and water;
- paths, trees, woodland, and useful neighboring-hole context.

Do not assume every `golf=hole` object is a polygon outline. Linear objects can be centerlines; closed polygons can be hole boundaries. Preserve geometry role and source tags.

### 2. Reconstruction and imagery

Use mapped geometry first. Associate features using explicit hole references where reliable, then spatial evidence. Where centerlines are absent, propose tee-to-green route hypotheses and preserve alternatives/ambiguity rather than silently selecting an uncertain pairing.

For imagery, use a provider with explicit access and redistribution rights. In the United States, NAIP can be evaluated where coverage, vintage, resolution, and licensing are appropriate. Other countries require a suitable local source or an honest geometry-only result. Do not extract geometry from the app's display tiles or from Google Maps imagery.

Spectral and shape processing may identify broad candidates (turf-like, bare-ground, canopy), but those candidates are not ground-truth bunker, green, tee, or fairway labels. Keep confidence and provenance in the manifest.

### 3. Generation service contract

The Python renderer depends on native geospatial/image libraries and must run in a controlled backend worker, not on Android's UI thread or as an assumed APK capability.

The service should expose an authenticated, versioned API with these conceptual operations:

- Request generation using course element type/ID plus validated course coordinates and display name.
- Return a ready cached package immediately, or a job ID when generation is needed.
- Report job status and actionable failure reasons.
- Return a manifest and per-hole image URLs after successful generation.

The cache key must include stable course identity, a hash/version of acquired source geometry, imagery source/version where used, and renderer version. Re-selecting an unchanged course should reuse the cached package; changed source geometry or renderer versions should invalidate it. Use bounded retries, endpoint fallback, request limits, and deduplication so simultaneous users do not trigger duplicate expensive jobs.

### 4. Android integration

Implement one shared repository for both screens:
- look up generated packages by stable course identity;
- request generation when no current package exists;
- poll or receive completion without blocking the main thread;
- download images to app-private storage and decode off the main thread;
- reuse cached images offline;
- expose loading, ready, stale, and failed states;
- preserve the existing schematic Caddy map and satellite/map functionality as fallback.

The UI must match artwork to the selected course and hole, never to a similarly named but ambiguous course. Do not remove the fallback until runtime generation is reliable.

### 5. Manifest contract

A package manifest should include:
- stable course identity and human-readable name;
- source geometry identifiers and source timestamps/version;
- renderer version and generation timestamp;
- hole label and image path for each generated hole;
- mapped versus inferred geometry provenance;
- confidence/ambiguity and skipped-hole reasons;
- imagery provider, acquisition date, and required attribution when imagery is used;
- orientation `tee_bottom_green_top`.

The current Android asset loader understands the `generated` list with `hole_label` and `image`. Preserve backward compatibility while extending the manifest.

## Tests and release gates

Use multiple fixtures with different mapping completeness, layout, hemisphere, and country. Include at least:
- a course with mapped centerlines and fairways;
- a course with tees/greens but no centerlines;
- a sparse course where generation must return a partial package or a useful failure, not fabricate a complete atlas.

Tests must verify route orientation, hole association, ambiguity handling, valid PNG dimensions, manifest provenance, cache invalidation, and graceful partial results. Run Python tests and Android builds. Device testing must confirm the same generated hole appears in Illustrated Holes and Caddy, with Caddy GPS behavior still available when no image is ready.

**Completion criterion:** selecting a previously unseen course generates or retrieves its artwork without code changes, per-course constants, manual asset packaging, or an APK rebuild. Lake Arlington is one test fixture only.
