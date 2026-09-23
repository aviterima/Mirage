import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Google Maps Platform key: read from local.properties (MAPS_API_KEY=...) or env.
// The app builds without it; the map/routing/search come alive once it is set.
val mapsApiKey: String = run {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    props.getProperty("MAPS_API_KEY") ?: System.getenv("MAPS_API_KEY") ?: ""
}

android {
    namespace = "com.mirage.spike"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mirage.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 29
        versionName = "0.11.0"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        // Optional: Mirage's own API gateway (holds the Google key server-side, meters credits).
        buildConfigField("String", "MIRAGE_API_BASE", "\"${System.getenv("MIRAGE_API_BASE") ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Location + maps
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.maps.android:maps-compose:6.1.0")

    // Networking for Directions / Places REST
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JVM unit tests: engine models and the ViewModel state machine (run in CI before release)
    testImplementation("junit:junit:4.13.2")
    // Unit tests run against a stub android.jar whose org.json returns nothing; use the real one.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// Versioned upstream model, packaged into the APK. No first-run model download.
val voiceAssets = layout.buildDirectory.dir("generated/voiceAssets")
val prepareVoiceModel by tasks.registering {
    outputs.dir(voiceAssets)
    inputs.property("modelVersion", "vosk-model-small-en-us-0.15")
    doLast {
        val root = voiceAssets.get().asFile
        val model = root.resolve("model-en-us")
        if (model.resolve("am/final.mdl").exists() && model.resolve("uuid").exists()) return@doLast
        val archive = layout.buildDirectory.file("vosk-model-small-en-us-0.15.zip").get().asFile
        archive.parentFile.mkdirs()
        val connection = java.net.URI("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").toURL().openConnection()
        connection.connectTimeout = 30000
        connection.readTimeout = 120000
        connection.getInputStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        model.deleteRecursively()
        model.mkdirs()
        java.util.zip.ZipInputStream(archive.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.removePrefix("vosk-model-small-en-us-0.15/")
                val target = model.resolve(name).canonicalFile
                require(target.toPath().startsWith(model.canonicalFile.toPath())) { "Invalid model archive entry" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        require(model.resolve("am/final.mdl").exists()) { "Voice model archive is incomplete" }
        model.resolve("uuid").writeText("mirage-vosk-en-us-0.15-v1")
    }
}
android.sourceSets.getByName("main").assets.srcDir(voiceAssets)
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(prepareVoiceModel)
}
