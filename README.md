# Watch this Drive

An Android-first golf companion focused on selecting a real golf course, reconstructing its mapped hole geometry, and providing on-course distances and carry-based club guidance.

## Current app foundation

- Find golf-course boundaries near the device or search around a course name, city, or ZIP code.
- Load the selected course's mapped golf features from OpenStreetMap through Overpass.
- Reconstruct hole centerlines and associate tee, green, and pin features using geometry and endpoint evidence.
- Assign fairway, bunker, and water features to the most likely hole corridor; ambiguous associations are left unassigned rather than guessed.
- Use a mapped pin when available. If a mapped pin is absent but a green polygon is available, estimate a safe point inside the green and label it as an estimate.
- Display an active-hole map with hole-up orientation when direction evidence is strong, GPS position, tap-to-measure targets, score/stroke controls, and a carry-distance Caddy recommendation.

Course data is discovered and loaded at runtime; there are no per-course production geometry files or course-specific reconstruction rules. Test fixtures for South Padre Island, Westwood, and Cottonwood are validation data only.

## Data coverage and limitations

Reconstruction quality depends on the OpenStreetMap data mapped for the selected course. A course without a mapped boundary or usable golf-hole centerlines may not be reconstructable by the current pipeline. Missing greens or pins are not fabricated: the app reports uncertain or unavailable geometry, and an estimated pin is only produced from a usable mapped green polygon.

Fairway, bunker, and water overlays use available mapped geometry. Satellite-image classification is still research work, not a production source of automatically detected boundaries. Treat mapped/estimated overlays as guidance, not survey-grade measurements.

Club distances are **carry only**: yards through the air to first landing, before bounce and roll. The Caddy uses those carry values consistently.

## Shared course facts and illustrated holes

- Active Game and Scorecard Only use the same hole-par source priority: reconstructed mapped course geometry first, published OpenGolfAPI facts only to fill missing values, and golfer-entered par corrections always take precedence.
- Scorecard Only starts immediately. It reuses cached geometry when available and resolves missing course facts in the background; it does not display or require a course map.
- Hole illustrations are generated on-device from the selected course's current reconstructed geometry. The same renderer and cache are used in the course's **Illustrated holes** section and the in-game Caddy view. There are no per-course production images or course-specific constants.
- The artwork frames the full tee-to-green hole with the tee at the bottom. Mapped fairways, greens, bunkers, and water retain their source geometry. Inferred details are visually distinguished and described as approximate; if the mapped data is insufficient, the app reports that rather than inventing a complete hole.
- The illustrated view is an additional Caddy detail; the approved GPS distance and recommendation panel layout remains unchanged.

## Map imagery

The app uses MapLibre Native with MapTiler satellite tiles. Configure a GitHub Actions repository secret named MAPTILER_API_KEY to provide the client-side map key during CI builds. Restrict the key in MapTiler Cloud to the extent supported by your plan. The key is compiled into the APK and should be treated as a public client key, not as a server secret.

For local builds, set MAPTILER_API_KEY in the environment or in ~/.gradle/gradle.properties.

## Build and test

Requirements: Java 17 and Android SDK components required by Android Gradle Plugin 8.7.3.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to app/build/outputs/apk/debug/app-debug.apk.

GitHub Actions runs unit tests and builds the debug APK on pushes to main, feature/**, and migration/**, and supports manual dispatch. Test reports and the APK are uploaded as workflow artifacts.

## Technology

- Kotlin 2.0.21
- Android Gradle Plugin 8.7.3
- Gradle 8.9 (checked-in wrapper)
- Jetpack Compose
- MapLibre Native Android SDK 11.8.0
- OpenStreetMap / Overpass geometry data


## In-app mapping diagnostics and recovery

The course detail screen includes a source-data coverage summary and an **Improve course mapping** action.

- Diagnostics count usable OSM hole lines, tees, greens, fairways, bunkers, and water features; numbered-hole gaps are reported only when the source itself supplies enough numbering evidence.
- The improvement action performs a second, broader Overpass query for golf-tagged features and `golf:hole` tags within the selected course area. It merges newly retrieved features with the existing set by OSM element identity, retains existing usable geometry, and reconstructs the course again.
- A broader query can only recover information that exists in the queried OSM data. It cannot fill genuinely unmapped fairways/greens or replace satellite-image recognition.

These controls are an initial recovery framework, not a claim that imagery recognition or full automatic repair is complete.


## Local data and settings

The Settings screen provides expandable sections for club carry distances, location permissions, and on-device data. Saved course-map inventory is read from the app's private `filesDir/mapped_courses/` cache. Completed scorecards remain in app-private local storage; Android's Files app does not normally expose these private files. Clearing app storage or uninstalling the app removes the local files. WTD does not implement its own cloud sync; Android system backup and restore behavior is governed by device settings.


## Scorecard sharing, data controls, and credits

- Active and completed scorecards are stored as JSON in Android private SharedPreferences under `round_scorecards`. The active round and completed-round history are separate entries.
- Active scorecards can be given a custom title, and scorecard-only rounds can optionally select a course through name/location search without enabling GPS or displaying a course map. Hole pars are resolved in the background from the same reconstructed OSM course geometry used by Active Game, with OpenGolfAPI filling gaps; golfers can still edit pars manually, and unavailable values remain blank rather than guessed. Scorecards can be exported as a branded PDF through Android's share sheet; the PDF includes the scorecard title (when set), course, golfer, round date, hole numbers, known pars, entered scores, and totals. The golfer's display name is configurable in Settings. The export is generated in the app cache and shared through Android's FileProvider; it is not uploaded by WTD.
- Settings provides an inventory of cached course maps and saved scorecards, with individual deletion and a confirmed delete-all action. The delete-all action also clears the active round and club carry settings.
- Location controls link to Android's app-permission and device-location settings. WTD uses foreground location only; it does not request background location.
- Credits in Settings identify project roles and third-party services. OpenStreetMap data is provided under ODbL; consult the linked source credits for their respective terms.
