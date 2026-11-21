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

USGS exposes a NAIP imagery index with per-image fields for state, year, acquisition date, resolution, band count, and download URL. USDA also publishes state-based image-date polygons; a 2024 Texas acquisition-date index is available. These indexes make per-course freshness checks possible, but the exact exposure date and resolution covering Lake Arlington still need to be queried and confirmed before selecting a pilot image. Do not infer the course's image date from the statewide collection year alone. Modern NAIP products may be 0.6 m or 0.3 m ground-sample distance, while older or generalized USGS descriptions may state 1 m; record the actual tile's metadata rather than hardcoding one resolution.

Before implementing automated retrieval, record each provider's license, attribution, permitted processing, retention rules, resolution, acquisition date, and geographic coverage.

#

## Lake Arlington imagery pilot — source verification notes

### Preferred discovery service

Use the USGS NAIP Plus ImageServer as the first imagery catalog to query for the selected Lake Arlington boundary:

- Service: https://imagery.nationalmap.gov/arcgis/rest/services/USGSNAIPPlus/ImageServer
- Its image catalog exposes per-raster fields including state, collection year, raster name, download URL, acquisition date, resolution value/units, band count, sensor type, and projection metadata.
- Query the catalog spatially against the selected course boundary; do not select a tile based only on a statewide year label.
- Record the returned raster identifier, acquisition date, ground sample distance, band count, download URL, and spatial reference in the pilot manifest.

USDA's 2024 Texas image-date index is a useful independent date cross-check. It records image exposure polygons and attributes including acquisition date, color type, camera information, and spectral band ranges. The presence of a Texas 2024 index does not prove that the Lake Arlington course footprint was captured in 2024; the course polygon must intersect a specific exposure polygon.

### Pilot acceptance gate

Do not begin feature labeling or model work until a real raster covering the selected course boundary has been retrieved and its metadata recorded. Verify that:
- The raster footprint covers the complete course area, including all holes.
- The image is usable for visual interpretation (no major cloud, haze, shadow, or seasonal obstruction).
- The spatial resolution and bands are recorded from the actual raster metadata.
- The imagery's applicable USGS/USDA terms and attribution are retained with the asset.
- The image is georeferenced and can be aligned with the existing OSM boundary candidates.

If no suitable current NAIP raster is available, compare earlier NAIP vintages before considering another imagery provider. Do not substitute restricted Texas state imagery or map-display tiles without a separate license review.

### Current verification status

The official USGS NAIP Plus catalog and the USDA 2024 Texas acquisition-date index have been identified. The exact raster footprint, acquisition date, and resolution at Lake Arlington have **not yet been confirmed**, and no pilot image asset has been accepted. This is an explicit hold point, not a successful coverage claim.

## Licensing and provenance

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


### Alternate imagery discovery route — historical NAIP

A browser-based NAIP explorer from All Things Spatial provides an alternate way to enumerate imagery over a searched area:

- Viewer: https://naip.allthingsspatial.io/
- It searches NAIP vintages from 2010–2023 through Microsoft Planetary Computer and reports the years found, pixel size, flight dates, tile count, and downloadable GeoTIFF tiles for the searched area.
- It also describes a USGS analysis layer for pixel inspection and tile footprints, but that layer is a separate, potentially older snapshot and must not be confused with the newest available vintage.
- The viewer states that its 2010–2023 historical catalog is sourced from Microsoft Planetary Computer; verify the specific tile's metadata and applicable distribution terms before accepting it into the pilot.

This is a useful fallback if the direct USGS catalog spatial query is unavailable. It does not establish which vintage covers Lake Arlington until a search is actually run for the course footprint and the resulting tile metadata is inspected. The viewer's stated coverage stops at 2023, so it cannot by itself establish 2024 or 2025 coverage.

### Research limitation

The research interface can read the viewer's documentation but cannot operate its interactive map/search controls. Therefore no Lake Arlington-specific vintage, flight date, tile footprint, pixel size, or image download has been verified through this route yet. Keep the pilot acceptance gate closed until those course-specific results are obtained.


### Reproducible NAIP catalog query

A metadata-only discovery script and GitHub Actions workflow have been added:

- Script: `scripts/discover_lake_arlington_naip.py`
- Workflow: `.github/workflows/discover-naip.yml`
- The script retrieves the currently selected OSM boundary (way 412547642), queries the Microsoft Planetary Computer public STAC API for intersecting `naip` items, and records item dates, GSD where supplied, projection, footprint bounds, asset references, provider metadata, and collection-level license/provider metadata.
- The workflow runs on changes to the discovery script/workflow on this feature branch or by manual dispatch, then uploads `lake-arlington-naip-candidates` as a 14-day artifact.
- The STAC API is publicly accessible without an account, although Microsoft documents throttling for anonymous data access. The current task only queries catalog metadata; it does not download imagery.

Important: do not equate a catalog response with permission to use an asset. Review the returned collection and item license/provider links and the underlying USDA terms before any download, derivative production, or commercial use. If the catalog metadata is ambiguous or inconsistent, stop and resolve it rather than assuming public-domain status from the NAIP name alone.

The first automated run is the next verification gate. It must confirm that the OSM boundary is still retrievable and that the catalog returns actual intersecting items. A candidate item must then be checked for complete course coverage and image quality before it can be accepted as the pilot asset.


### Lake Arlington NAIP catalog results — 2026-11-21

The automated GitHub Actions discovery job successfully queried the Microsoft Planetary Computer STAC catalog using the current OSM boundary (way 412547642).

- 12 intersecting NAIP items were returned across six vintages: 2012, 2014, 2016, 2018, 2020, and 2022.
- The newest matching vintage is 2022-07-13.
- The 2022 vintage consists of two neighboring items: `tx_m_3209723_nw_14_060_20220713` and `tx_m_3209723_ne_14_060_20220713`.
- Both report 0.6 m ground sample distance and EPSG:26914.
- Their actual STAC item geometries, unioned together, cover 100% of the selected OSM course-boundary polygon. The same footprint check reports complete coverage for each older vintage as well.
- Low-resolution rendered previews for both 2022 tiles were downloaded to the workflow artifact. Visual inspection confirms that the course area is present in the imagery. The previews are for triage only and are not accepted as the analysis raster.

Artifact: `lake-arlington-naip-candidates` from GitHub Actions run 44939807097 (commit `2382b081064d73808e41d60e50989aa5ca94826f`). The workflow completed successfully.

#### License metadata discrepancy — unresolved gate

The STAC collection response currently reports `license: proprietary`, while its collection license link is titled `Public Domain` and points to USDA FSA policies. The official Data.gov NAIP dataset record identifies the dataset license as public domain, and USDA/USGS sources describe NAIP as public-domain imagery. This is strong supporting evidence, but the inconsistent STAC collection field must be documented and reconciled against the actual source tile's terms before accepting it for commercial processing or distribution. Do not silently overwrite the catalog's license field.

#### Current pilot status

**Coverage and imagery date/resolution are now confirmed from the STAC metadata.** The visual previews confirm that the course is represented. The full-resolution GeoTIFFs have not yet been downloaded or quality-checked, and the catalog-license discrepancy remains open. The imagery-analysis acceptance gate therefore remains closed until those two checks are complete.
