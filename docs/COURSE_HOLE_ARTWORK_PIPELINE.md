# Course-specific hole illustration generation

## Current pipeline contract

The Android app looks for assets at:

`app/src/main/assets/course_holes/<osm-element-type>-<osm-id>/manifest.json`

The manifest contains a `generated` array. Each generated item has a `hole_label`, `candidate_index`, and PNG `image` filename. The app uses the selected course's OSM element type and ID, so images cannot accidentally appear on a different course.

## Reusable renderer

- `scripts/course_intelligence/association.py` associates mapped OSM golf features with linear `golf=hole` centerlines. Polygonal `golf=hole` objects remain separate hole outlines.
- `scripts/course_intelligence/reconstruction.py` can propose tee-to-green route hypotheses when centerlines are absent. Alternatives and ambiguity remain explicit.
- `scripts/course_intelligence/vector_hole_renderer.py` creates portrait illustrations with tee at bottom and green at top, preserving curved routes and drawing course context.
- `scripts/course_intelligence/render_course_holes.py` batch-renders mapped centerlines or provisional tee-to-green routes.
- `scripts/package_course_hole_artwork.py` validates generated PNGs and packages them under the exact OSM course identity expected by Android.

## Evidence and confidence

Mapped OSM features are source geometry, not guaranteed truth. Inferred routes and fairway corridors are provisional. Imagery-derived canopy and spectral classes are context only; they must not be presented as confirmed bunkers, greens, or fairway boundaries. Ambiguous or incomplete holes remain identified as such in the manifest.

## Imagery policy

For U.S. courses, the pilot uses USDA NAIP imagery where coverage, vintage, resolution, and processing permissions are verified. Do not use the app's MapTiler display tiles or Google Maps imagery as input to geometry extraction. Store source dates and attribution with generated course packages.

## Android performance

Manifest reads and bitmap decoding run on `Dispatchers.IO`, not the Compose main thread. The existing map, Caddy, and Target Green screens remain separate from the artwork package.

## Next integration steps

1. Add course-agnostic OSM and NAIP acquisition for any selected course.
2. Run the pipeline against a real course and inspect the generated hole atlas for incorrect assignments, route direction, missing features, and visual quality.
3. Package only outputs whose OSM element type and ID match the selected course.
4. Build and test the APK on device, then repeat with a second course to confirm the pipeline is not course-specific.
