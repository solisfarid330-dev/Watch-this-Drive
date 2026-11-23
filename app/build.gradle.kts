import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
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

// The OpenGolfAPI snapshot is bundled so US course discovery works offline.
val generatedCourseAssets = File(layout.buildDirectory.get().asFile, "generated/courseAssets")
android.sourceSets.getByName("main").assets.srcDir(generatedCourseAssets)

val ensureOpenGolfOfflineCatalog = tasks.register("ensureOpenGolfOfflineCatalog") {
    val catalogFile = File(generatedCourseAssets, "opengolfapi-us.geojson.gz")
    outputs.file(catalogFile)
    outputs.upToDateWhen { false }
    doLast {
        val expectedCompressed = "c3b3bd4b1b3894fe5b9a6f0e84df43472f55aaad2602faeb58ef56a709db1a39"
        val expectedGeoJson = "fb4aa24aa8bce2fe338812bc316a2067720c6ffb7874429f5c037b31578300db"

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

        catalogFile.parentFile.mkdirs()
        if (!catalogFile.exists() || sha256(catalogFile.inputStream()) != expectedCompressed) {
            val temporary = File(catalogFile.parentFile, catalogFile.name + ".download")
            val connection: URLConnection = URL(
                "https://github.com/opengolfapi/data/releases/download/v2.1.0/opengolfapi-us.geojson.gz"
            ).openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 30_000
            }
            connection.getInputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            val downloadedHash = sha256(temporary.inputStream())
            if (downloadedHash != expectedCompressed) {
                temporary.delete()
                throw GradleException("OpenGolfAPI course catalog failed its compressed SHA-256 check")
            }
            if (!temporary.renameTo(catalogFile)) {
                temporary.copyTo(catalogFile, overwrite = true)
                temporary.delete()
            }
        }

        if (sha256(catalogFile.inputStream()) != expectedCompressed) {
            throw GradleException("OpenGolfAPI course catalog is corrupt")
        }
        val contentHash = sha256(GZIPInputStream(catalogFile.inputStream()))
        if (contentHash != expectedGeoJson) {
            throw GradleException("OpenGolfAPI GeoJSON content failed its SHA-256 check")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(ensureOpenGolfOfflineCatalog)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(ensureOpenGolfOfflineCatalog)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
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
