# Hybrid Course Intelligence Architecture

## Goal

Build one reusable course model that combines independently sourced geographic data with appropriately licensed aerial imagery. The model will power the satellite overlays, illustrated Hole View, GPS distances, and virtual caddy.

The system must not assume that OpenStreetMap (OSM) is complete, that imagery interpretation is always correct, or that a high feature count means a course is accurately mapped.

## First validation course

Lake Arlington Golf Course, Arlington, Texas.

The first milestone is a reviewed, geographically aligned representation of its holes. This is a test fixture, not a hardcoded production course.

## Source strategy

### OpenStreetMap

Use OSM as a discovery and geographic-reference source. It may provide:
- Course name and candidate boundary geometry.
- Hole references and hole relations, where mapped.
- Tees, greens, fairways, bunkers, and water features, where mapped.
- Stable OSM element type and ID for provenance and refresh.

OSM records are observations, not unquestioned truth. Preserve candidate boundaries and source metadata. Do not silently discard competing candidates.

### Aerial imagery

The imagery used for automated feature extraction must have explicit terms that permit the intended analysis, storage of derived geometry, and commercial product use.

The currently configured MapTiler satellite style is for map display only unless a suitable written license says otherwise. Do not use MapTiler imagery as input to automated extraction under the currently published restrictions.

Google Maps imagery must not be used to trace, digitize, train, test, or validate extracted course geometry under the currently published restrictions.

For an initial United States pilot, evaluate USDA NAIP imagery distributed through USGS/The National Map. NAIP is public-domain imagery with requested attribution; resolution, acquisition date, geographic coverage, and service access must be checked per course. Do not assume imagery is current or uniformly available. For courses outside the United States, add separately reviewed providers.

Before implementing automated retrieval, record each provider's license, attribution, permitted processing, retention rules, resolution, acquisition date, and geographic coverage.

### Licensing and provenance

Keep source observations separate and traceable. Every derived feature must record its source(s), source identifiers or imagery item identifier, imagery acquisition date where available, processing/model version, and review status.

OSM data is under the ODbL. Assess attribution and share-alike obligations before distributing a database that combines OSM data with other sources. Do not assume that storing data in one application removes those obligations. Obtain legal review before commercial distribution if the combined database structure raises uncertainty.

## Course model

Represent the course as a collection of holes and independently sourced feature observations.

Each hole should support:
- Stable internal ID and optional displayed hole number.
- Tee areas / tee points.
- Fairway polygons or centerline/landing-area geometry.
- Green polygon.
- Bunkers and water hazards.
- Optional out-of-bounds, paths, trees, and other future features.
- Geographic geometry in a consistent coordinate reference system.
- Per-feature confidence, provenance, and validation state.

Do not force every feature into a hole if its relationship is uncertain. Keep it as an unassigned course feature until evidence supports an association.

### Provenance and confidence

Each feature should retain:
- Geometry and feature class.
- Source: OSM, imagery analysis, human correction, or a combination.
- Source record / imagery identifiers.
- Confidence score and model version, if machine-generated.
- Validation status: unreviewed, accepted, corrected, rejected, or stale.
- Last analyzed / reviewed timestamps.

Confidence must not be presented as accuracy unless calibrated against reviewed examples.

## Automated reconstruction pipeline

1. Discover the course and retain plausible OSM boundary candidates.
2. Select or infer a course boundary; keep the selected source and alternatives.
3. Find eligible aerial imagery covering the boundary and record its metadata.
4. Align imagery and vector sources geographically.
5. Detect candidate golf features using a model trained and evaluated on imagery that is licensed for that purpose.
6. Associate tees, fairways, greens, and hazards with holes using OSM references plus spatial and visual relationships.
7. Run geometry and consistency checks (valid polygons, plausible sizes, course containment, hole connectivity/order, and duplicate/conflicting features).
8. Compare source observations. Disagreements and low-confidence detections require review; never silently choose one.
9. Save a versioned course geometry package for app display and downstream calculations.

## User-facing behavior

Hide raw OSM IDs, raw feature counts, and technical diagnostics from ordinary golfers.

The golfer-facing product should show useful hole layouts and distances. Incomplete or uncertain geometry must be clearly marked internally and must not drive confident hazard or club recommendations.

Developer diagnostics may show source coverage, feature counts, confidence, imagery date, and unresolved review items.

## Map and product integration

All downstream features consume the same validated course model:
- Satellite map overlays.
- Illustrated top-down Hole View.
- Front / center / back green distances and hazard carry distances.
- GPS shot position and landing-area calculations.
- Virtual caddy recommendations using the golfer's learned carry distances.

The satellite basemap renderer is separate from the imagery-analysis provider. A map provider's display license must never be assumed to grant rights to extract geometry from its imagery.

## Delivery milestones

### Milestone 1 — Data contract and source feasibility
- Define the versioned course / hole / feature data contract.
- Verify an eligible aerial imagery source and metadata for Lake Arlington.
- Establish provenance, licensing, and QA requirements.
- Preserve the existing OSM candidate workflow.

### Milestone 2 — Lake Arlington imagery pilot
- Retrieve an eligible image for the selected course boundary.
- Produce a georeferenced imagery test asset and metadata record.
- Manually label a small validation set for tees, fairways, greens, bunkers, and water.
- Compare image interpretation with OSM observations.

### Milestone 3 — Automated feature detection
- Select an appropriate segmentation / detection approach based on the pilot data.
- Measure performance against reviewed labels.
- Produce candidate geometries with confidence and provenance.

### Milestone 4 — Hole reconstruction and review
- Associate features with individual holes.
- Add geometry validation and a review/correction workflow.
- Generate the reusable course geometry package.

### Milestone 5 — App overlays
- Render validated course geometry over the satellite map.
- Generate the illustrated Hole View from the same data.
- Integrate reliable geometry with GPS and caddy calculations.

## Explicit non-goals for the first milestone

- Do not train a model on MapTiler or Google satellite imagery.
- Do not assume OSM has all holes or all features.
- Do not claim a course is fully mapped based on raw feature counts.
- Do not automatically publish or treat unreviewed detections as ground truth.
- Do not hardcode Lake Arlington into production logic.
