import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.watchthisdrive.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.watchthisdrive.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "MAPTILER_API_KEY", "\"${providers.gradleProperty("MAPTILER_API_KEY").orElse(providers.environmentVariable("MAPTILER_API_KEY")).getOrElse("")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// The release workflow downloads the catalog before Gradle configures Android assets.
// Local builds can run scripts/download_opengolf_catalog.sh once before building.
val verifyOpenGolfOfflineCatalog = tasks.register("verifyOpenGolfOfflineCatalog") {
    val catalogFile = project.file("src/main/assets/opengolfapi-us.geojson.gz")
    inputs.file(catalogFile)
    outputs.upToDateWhen { false }
    doLast {
        if (!catalogFile.exists()) {
            throw GradleException(
                "OpenGolfAPI catalog is missing. Run scripts/download_opengolf_catalog.sh before building."
            )
        }
        fun sha256(stream: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            stream.use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        val compressedHash = sha256(catalogFile.inputStream())
        if (compressedHash != "c3b3bd4b1b3894fe5b9a6f0e84df43472f55aaad2602faeb58ef56a709db1a39") {
            throw GradleException("OpenGolfAPI compressed catalog SHA-256 mismatch")
        }
        val contentHash = sha256(GZIPInputStream(catalogFile.inputStream()))
        if (contentHash != "fb4aa24aa8bce2fe338812bc316a2067720c6ffb7874429f5c037b31578300db") {
            throw GradleException("OpenGolfAPI GeoJSON content SHA-256 mismatch")
        }
        logger.lifecycle("Verified OpenGolfAPI catalog: ${catalogFile.absolutePath} (${catalogFile.length()} bytes)")
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyOpenGolfOfflineCatalog)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(verifyOpenGolfOfflineCatalog)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
