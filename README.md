# Watch this Drive

An Android-first personal golf companion. Initial foundation includes a Compose interface, MapLibre satellite map powered by MapTiler, a GPS permission/location check, and a GitHub Actions APK workflow.

## Build configuration

- Android Gradle Plugin 8.7.3
- Gradle 8.9
- Kotlin 2.0.21
- Java 17 (GitHub Actions)
- MapLibre Native Android SDK 11.8.0

## MapTiler API key

Set a GitHub Actions repository secret named `MAPTILER_API_KEY`. The key is compiled into the app as a client-side map key; restrict it in MapTiler Cloud to the extent supported by your plan and rotate it if exposed. Do not commit keys to source control.

For local builds, use `~/.gradle/gradle.properties` with `MAPTILER_API_KEY=your_key` or set the environment variable.

## Build

`gradle assembleDebug`

The GitHub Actions workflow builds the APK on every push to `main` and on manual dispatch.
