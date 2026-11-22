# Lake Arlington NAIP Pilot — Source Notes

## Current finding

USDA NAIP distributed through USGS/The National Map remains the preferred first imagery source to evaluate for the Lake Arlington, Arlington, Texas pilot. It is a public-domain source, and USGS requests attribution:

> Map services and data available from U.S. Geological Survey, National Geospatial Program.

References:
- USGS orthoimagery update and acquisition-date FAQ: https://www.usgs.gov/faqs/how-often-orthoimagery-national-map-updated-and-what-are-acquisition-dates
- USGS National Map service/data use and licensing FAQ: https://www.usgs.gov/faqs/what-are-terms-uselicensing-map-services-and-data-national-map
- USGS NAIP ImageServer: https://imagery.nationalmap.gov/arcgis/rest/services/USGSNAIPImagery/ImageServer
- USGS NAIP imagery index layer: https://index.nationalmap.gov/arcgis/rest/services/USGSNAIPImageryIndex/MapServer/0

## What is verified

- The National Map's orthoimagery is sourced from USDA NAIP. USGS describes the general NAIP product as 1-meter imagery and says coverage is generally refreshed on an approximately three-year cycle, with roughly one third of the continental U.S. flown each year.
- Acquisition month/year can be obtained from the imagery product metadata; the USGS imagery index exposes acquisition date, year, resolution, band count, and download URL fields.
- USDA publishes year-specific image-date indexes, including 2024 and 2025. The 2024 Texas index describes image acquisition date (IDATE), start/end collection times, color type, camera/sensor details, and spectral band ranges. The 2025 index also exposes IDATE and related tile metadata.
- The USGS NAIP ImageServer is a live imagery service and is a candidate retrieval endpoint for the pilot.

## What is not yet verified for Lake Arlington

The exact NAIP acquisition date, tile footprint, pixel resolution, and available bands covering the selected Lake Arlington course boundary have **not** yet been confirmed by a successful point/polygon metadata query. A Texas-wide 2024 index existing does not prove that the golf course itself is covered by a 2024 image. Do not label the pilot imagery as 2024 or 2025 until the spatial query returns the matching tile record.

## Required source-validation step

Before downloading or labeling imagery:

1. Query the USGS NAIP imagery index against the selected OSM boundary geometry (not just a city-center point).
2. Record every intersecting tile's name, acquisition date, year, ground sample distance/resolution, band count, sensor, and download URL.
3. Check for gaps, multiple dates, seams, cloud/shadow, and whether the imagery is seasonally suitable for distinguishing course features.
4. Retrieve the imagery and preserve the original metadata and source attribution with the test asset.
5. Confirm that the chosen distribution endpoint and any downloaded product permit the intended processing and storage of derived geometries before using it in a commercial workflow.

If the index query fails or the imagery is unsuitable, evaluate another public-domain NAIP delivery path or an independently licensed aerial source. Do not silently substitute MapTiler or Google display imagery.

## Pilot acceptance criteria

- The selected boundary is covered by a documented, retrievable imagery product.
- Acquisition date and native resolution are recorded, not inferred from the dataset year alone.
- The image is georeferenced and visually suitable for labeling tees, fairways, greens, bunkers, and water.
- The original imagery, metadata, attribution, and derived-label provenance remain linked.
- Any imagery gaps or uncertain visual features are explicitly marked for review.
