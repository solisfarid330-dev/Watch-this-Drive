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

The course detail screen now includes a source-data coverage summary, an **Improve course mapping** action, and a **Report a missing feature** action.

- Diagnostics count usable OSM hole lines, tees, greens, fairways, bunkers, and water features; numbered-hole gaps are reported only when the source itself supplies enough numbering evidence.
- The improvement action performs a second, broader Overpass query for golf-tagged features and `golf:hole` tags within the selected course area. It merges newly retrieved features with the existing set by OSM element identity, retains existing usable geometry, and reconstructs the course again.
- Missing-feature reports are currently stored locally on the device for that selected OSM course boundary. They are not uploaded to a server and do not create map geometry; they are groundwork for more targeted recovery and later opt-in feedback.
- A broader query can only recover information that exists in the queried OSM data. It cannot fill genuinely unmapped fairways/greens or replace satellite-image recognition.

These controls are an initial recovery framework, not a claim that imagery recognition or full automatic repair is complete.


## Local data and settings

The Settings screen provides expandable sections for club carry distances, location permissions, and on-device data. Saved course-map inventory is read from the app's private `filesDir/mapped_courses/` cache. Completed scorecards remain in app-private local storage; Android's Files app does not normally expose these private files. Clearing app storage or uninstalling the app removes this local data; there is no cloud sync.


## Scorecard sharing, data controls, and credits

- Active and completed scorecards are stored as JSON in Android private SharedPreferences under `round_scorecards`. The active round and completed-round history are separate entries.
- Scorecards can be exported as a branded PDF through Android's share sheet. The PDF includes the course, golfer, round date, hole numbers, pars, entered scores, and totals. The export is generated in the app cache and shared through Android's FileProvider; it is not uploaded by WTD.
- Settings provides an inventory of cached course maps and saved scorecards, with individual deletion and a confirmed delete-all action. The delete-all action also clears the active round and club carry settings.
- Location controls link to Android's app-permission and device-location settings. WTD uses foreground location only; it does not request background location.
- Credits in Settings identify project roles and third-party services. OpenStreetMap data is provided under ODbL; consult the linked source credits for their respective terms.
