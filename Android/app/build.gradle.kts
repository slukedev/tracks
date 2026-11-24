import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use(::load)
}

/**
 * The upload key for Play, from secrets.properties or the environment (for CI):
 * upload.storeFile / TRACKS_UPLOAD_STORE_FILE, upload.storePassword / TRACKS_UPLOAD_STORE_PASSWORD,
 * upload.keyAlias / TRACKS_UPLOAD_KEY_ALIAS and upload.keyPassword / TRACKS_UPLOAD_KEY_PASSWORD.
 * Null when any is missing.
 */
class UploadKey(val store: File, val storePassword: String, val alias: String, val keyPassword: String)

val uploadKey: UploadKey? = run {
    fun value(property: String, variable: String) =
        secrets.getProperty(property)?.takeIf { it.isNotBlank() } ?: System.getenv(variable)?.takeIf { it.isNotBlank() }
    val path = value("upload.storeFile", "TRACKS_UPLOAD_STORE_FILE") ?: return@run null
    val store = rootProject.file(path)
    if (!store.exists()) {
        logger.warn("Tracks: the upload keystore $path doesn't exist; release builds use the debug key.")
        return@run null
    }
    UploadKey(
        store,
        value("upload.storePassword", "TRACKS_UPLOAD_STORE_PASSWORD") ?: return@run null,
        value("upload.keyAlias", "TRACKS_UPLOAD_KEY_ALIAS") ?: return@run null,
        value("upload.keyPassword", "TRACKS_UPLOAD_KEY_PASSWORD") ?: return@run null,
    )
}

android {
    namespace = "com.luke.tracks"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.luke.tracks"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "LASTFM_API_KEY", "\"${secrets.getProperty("lastfm.apiKey", "")}\"")
        buildConfigField("String", "LASTFM_SECRET", "\"${secrets.getProperty("lastfm.secret", "")}\"")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        uploadKey?.let { key ->
            create("upload") {
                storeFile = key.store
                storePassword = key.storePassword
                keyAlias = key.alias
                keyPassword = key.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without an upload key, a release build is for this computer only: signed with the
            // debug key, which Play refuses, and said so in the build output.
            signingConfig = if (uploadKey != null) signingConfigs.getByName("upload") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            kotlin.directories += "engine-bindings"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
            "androidx.media3.common.util.UnstableApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.compose.material.icons)
    implementation(libs.adaptive)
    implementation(libs.adaptive.layout)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.paging.compose)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.okhttp)
    implementation("${libs.jna.get()}@aar")
    implementation(libs.material.kolor)
    implementation(libs.serialization.json)
    implementation(libs.graphics.shapes)
    implementation(libs.palette)
    implementation(libs.splashscreen)
    implementation(libs.browser)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // The desktop JNA (with its native dispatcher) for running the engine in JVM tests.
    testImplementation(libs.jna)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1536m"
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("roborazzi.test.record", "true")
    // The engine built for this computer by Engine/build-android.sh.
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").let { if (it == "amd64" || it == "x86_64") "x86-64" else "aarch64" }
    val dir = (if (os.contains("mac")) "darwin-" else "linux-") + arch
    systemProperty("jna.library.path", file("src/test/resources/$dir").absolutePath)
}

// Says which key a release is signed with whenever one is built, so a debug-signed release
// never goes out by mistake.
val signingNote = if (uploadKey == null) {
    "Tracks: this release is signed with the DEBUG key and can't go to Play. Add the upload key to secrets.properties (see secrets.example.properties)."
} else {
    "Tracks: signing this release with the upload key '${uploadKey?.alias}'."
}
val signedForPlay = uploadKey != null
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    val note = signingNote
    val forPlay = signedForPlay
    doFirst { if (forPlay) logger.lifecycle(note) else logger.warn(note) }
}
