# Generic course-hole artwork pipeline

## Product requirement

Every course selected in Watch this Drive must be eligible for automatic hole-artwork generation. Course names, OSM IDs, coordinates, geometry, and feature availability are runtime inputs. No course-specific constants, prebuilt course atlas, or APK rebuild may be required when a golfer selects a different course.

The same generated artwork package is consumed by **Illustrated Holes** and **Playing Caddy**. Caddy retains its GPS-aware reconstructed-map fallback whenever artwork is unavailable.

## Current implementation status

- Android course acquisition now starts from the selected OSM boundary, retrieves mapped course/landscape features within its bounds, and spatially filters against stitched outer boundary rings when available. If no usable boundary exists, it falls back to a bounded coordinate search; that fallback has weaker isolation guarantees.
- Hole reconstruction now prefers explicit hole numbers when associating tee clusters and greens, uses mapped fairway polygons when available, and only draws a synthetic corridor when no mapped fairway is associated. Mapped fairways remain separate from inferred geometry.
- Bunkers and water are associated with the reconstructed tee-to-green corridor and nearby mapped fairway geometry. This is a first-pass spatial association, not a fully solved routing/graph problem.
- The reconstructed-hole UI renders mapped fairway polygons and labels the fallback corridor as estimated.
- Android Illustrated Holes still loads packaged artwork assets. It does not yet generate and cache a new course's illustrations on the device.
- The Caddy screen uses the shared artwork repository when a packaged illustration exists and otherwise falls back to its reconstructed GPS-aware map.
- The regular APK workflow no longer runs or packages the Lake Arlington-specific artwork pilot. Lake Arlington remains only a potential test fixture.
- Python geometry/rendering scripts are prototypes/reference tools, not a production runtime dependency. A universal on-device renderer and local generated-artwork cache are not yet implemented.

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

The earlier hosted-worker design below is retained only as an alternative. The agreed product architecture is to acquire data and generate artwork locally on Android, with no required backend processing. A backend may be used as an optional source/cache only if it does not become a release dependency.

If a hosted service is ever chosen, it should expose an authenticated, versioned API with these conceptual operations:

- Request generation using course element type/ID plus validated course coordinates and display name.
- Return a ready cached package immediately, or a job ID when generation is needed.
- Report job status and actionable failure reasons.
- Return a manifest and per-hole image URLs after successful generation.

The cache key must include stable course identity, a hash/version of acquired source geometry, imagery source/version where used, and renderer version. Re-selecting an unchanged course should reuse the cached package; changed source geometry or renderer versions should invalidate it. Use bounded retries, endpoint fallback, request limits, and deduplication so simultaneous users do not trigger duplicate expensive jobs.

### 4. Android integration

Implement one shared on-device generation/cache repository for both screens:
- look up locally generated packages by stable course identity and source-geometry version;
- generate artwork on-device when no current package exists;
- run CPU-heavy geometry and drawing away from the main thread;
- save generated images and manifests to app-private storage and decode off the main thread;
- reuse cached images offline without network access after initial acquisition;
- expose loading, ready, stale, partial, and failed states;
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
