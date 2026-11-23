# Generic course-hole artwork pipeline

## Product requirement

Every course selected in Watch this Drive must be eligible for automatic hole-artwork generation. Course names, OSM IDs, coordinates, geometry, and feature availability are runtime inputs. No course-specific constants, prebuilt course atlas, or APK rebuild may be required when a golfer selects a different course.

The same generated artwork package is consumed by **Illustrated Holes** and **Playing Caddy**. Caddy retains its GPS-aware reconstructed-map fallback whenever artwork is unavailable.

## Current implementation status

- Android course acquisition now starts from the selected OSM boundary, retrieves mapped course/landscape features within its bounds, and spatially filters against stitched outer boundary rings when available. If no usable boundary exists, it falls back to a bounded coordinate search; that fallback has weaker isolation guarantees.
- Hole reconstruction prefers explicit hole numbers, uses mapped OSM hole centerlines to retain dogleg routes, and follows connected fairway sections where available. If no mapped route/fairway exists, it draws a clearly identified estimated corridor.
- Bunkers and water are associated with mapped fairways or centerlines first, then with a direct tee-to-green fallback when mapped route data is absent. This remains a spatial approximation, not survey-grade routing.
- Reconstruction tests cover explicit tee/green matching, cross-hole mismatch rejection, mapped fairway preservation, and estimated-corridor fallback.
- Android includes a detailed on-device renderer that creates tee-bottom/green-top PNG illustrations and caches them in app-private storage. It fits the full tee/green/fairway/hazard/centerline geometry to the artwork viewport, rather than scaling around the tee alone. It adds layered turf, mowing bands, contoured greens, textured bunkers and water, mapped woodland/tree symbols, rough and paths. Decorative texture is visual styling only; mapped geometry remains the source of feature positions. The renderer version and source geometry participate in the cache key.
- Illustrated Holes and Playing Caddy share the same renderer, matched NAIP RGB/NDVI products, imagery cache token, and local artwork cache. NDVI refinement is asynchronous after the RGB first pass; the last good image stays visible while refinement runs. Packaged pilot images remain supported as a backward-compatible first choice; Caddy retains its reconstructed GPS-aware map as fallback.
- Course geometry cache schema v2 preserves feature source, confidence, and model-prediction status. Older cache entries without provenance are ignored so predicted geometry cannot silently return as confirmed mapped geometry.
- A valid course outline is retained even when no tees, greens, or hole lines are mapped. The app can still show the course boundary and satellite imagery while clearly reporting that hole artwork cannot yet be reconstructed.
- The regular APK workflow no longer runs or packages the Lake Arlington-specific artwork pilot. Lake Arlington remains only a potential test fixture.
- The artwork viewport uses the full portrait image area in Illustrated Holes and Caddy. Surrounding landscape is clipped to the fitted hole bounds so large woodland polygons do not shrink the playable hole.
- Python geometry/rendering scripts are prototypes/reference tools, not a production runtime dependency. The Android renderer is functional but still needs visual review across courses with sparse and dense OSM mapping, plus device-level performance and layout checks.

## Evidence-fusion implementation notes

The current Android implementation now follows a layered evidence strategy:

1. **Course identity and scorecard:** OpenGolfAPI supplies course discovery metadata and per-hole scorecard facts where available. These records are not treated as hole geometry.
2. **Mapped geometry:** OSM remains the direct mapped source. Zomma GeoJSON is queried as a supplemental source using state plus course/city slug candidates, and its returned geometry is rejected if its geographic footprint is implausibly far from the selected course.
3. **Source priority:** Direct OSM features replace coincident Zomma features marked as model-predicted. Zomma-only features remain available. Zomma source/confidence/prediction fields are retained when present.
4. **Imagery selection:** The USGS NAIP ImageServer is queried for intersecting four-band source rasters. The selection prefers the newest acquisition and finest available ground sampling distance within that vintage. Older intersecting rasters remain ordered behind the preferred rasters as a pixel-level coverage fallback.
5. **Matched spectral evidence:** Natural-colour RGB and a scaled 8-bit NDVI product are exported from the same locked NAIP raster set and the same WGS84 hole bounds. The imagery export is capped at the service's 4,000-pixel dimension. The renderer downsamples imagery for memory safety and applies NDVI as a restrained vegetation tint over the aerial underlay.
6. **Illustration honesty:** Mapped vectors remain the geometry source. Predicted geometry is rendered with lower-opacity fills and dashed outlines, and SatlasPretrain attribution is shown beside the illustration when predicted features are present. NDVI is explicitly a visual evidence layer, not a classifier or proof that a pixel is fairway, green, bunker, or water.
7. **NDVI polygon evidence:** `NdviBoundaryAnalyzer` samples a 28×28 grid over each polygon's bounds plus an 18 m surrounding margin, then samples paired pixels approximately 3 m inside and outside each polygon edge at roughly 8 m intervals. It reports inside/outside vegetation fractions, broad contrast, edge contrast/support, sample counts and a coarse strong/mixed/weak evidence label. The panel reports this evidence to the golfer. The score is an uncalibrated review signal: it does not edit polygons, certify a feature, or infer a new boundary. Insufficient samples produce no score.

This is an incremental evidence-fusion pipeline, not yet a fully automated image-segmentation system. NDVI evidence is currently a quality-assessment signal only; it has not been calibrated against surveyed golf-feature boundaries. Before any boundary movement is enabled, test multiple courses and imagery vintages, assess false positives, and require independent geometric and spectral evidence. Do not silently promote spectral candidates to confirmed golf features.

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

Tests must verify route orientation, hole association, ambiguity handling, valid PNG dimensions, manifest provenance, cache invalidation, and graceful partial results. Run Python tests, Android reconstruction unit tests, and Android builds. Device testing must confirm the same generated hole appears in Illustrated Holes and Caddy, with Caddy GPS behavior still available when no image is ready.

**Completion criterion:** selecting a previously unseen course generates or retrieves its artwork without code changes, per-course constants, manual asset packaging, or an APK rebuild. Lake Arlington is one test fixture only.
