import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

/**
 * Which engine and code the app is built with. "play" is the store build. "sideload" adds
 * Spotify, which plays through librespot against Spotify's terms and so never goes to a store:
 * build it with -Ptracks.sideload=true after Engine/build-android.sh --sideload.
 */
val sideload = providers.gradleProperty("tracks.sideload").map { it.toBoolean() }.getOrElse(false)
val variant = if (sideload) "sideload" else "play"

/**
 * Apple's MusicKit for Android SDK, for Apple Music sign-in and playback. Apple ships it only as
 * AARs behind a developer sign-in (developer.apple.com/download/all, "Android MusicKit"), so it's
 * never committed: put them in app/libs/apple-musickit/. Built with them when they're there;
 * -Ptracks.appleMusicSdk=false leaves them out. Without them Apple Music stays out of Sources.
 * Checked against MediaPlayback 1.1.4 and MusicKitAuth 1.1.5.
 */
val appleMusicAars = fileTree("libs/apple-musickit") { include("*.aar") }
val appleMusicSdk = providers.gradleProperty("tracks.appleMusicSdk").map { it.toBoolean() }.getOrElse(!appleMusicAars.isEmpty)
if (appleMusicSdk && appleMusicAars.isEmpty) {
    throw GradleException("Tracks: -Ptracks.appleMusicSdk=true needs Apple's MusicKit AARs in app/libs/apple-musickit/.")
}

val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use(::load)
}

/**
 * The Apple Music developer token for a personal build: a JWT you sign with your own MusicKit key
 * (appleMusic.developerToken in secrets.properties or TRACKS_APPLE_MUSIC_DEVELOPER_TOKEN). Apple
 * allows at most 6 months; the app stops using it when it expires. The .p8 key never goes in.
 */
val appleMusicDeveloperToken: String = run {
    val token = secrets.getProperty("appleMusic.developerToken")?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("TRACKS_APPLE_MUSIC_DEVELOPER_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
        ?: return@run ""
    val claims = runCatching {
        String(Base64.getUrlDecoder().decode(token.split('.')[1].trimEnd('=')))
    }.getOrNull()
    val exp = claims?.let { Regex("\"exp\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
    when {
        token.count { it == '.' } != 2 || !token.all { it.isLetterOrDigit() || it in "-_." } -> {
            logger.warn("Tracks: appleMusic.developerToken isn't a JWT; Apple Music is left out.")
            ""
        }
        exp == null || exp * 1000 <= System.currentTimeMillis() -> {
            logger.warn("Tracks: the Apple Music developer token has expired; make a new one. Apple Music is left out.")
            ""
        }
        else -> token
    }
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
        logger.warn("Tracks: the upload keystore $path doesn't exist; release builds need it, or -Ptracks.allowDebugSigning=true.")
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
    // Compose 1.13 (BOM 2026.11.01) needs 37.1.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.luke.tracks"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "LASTFM_API_KEY", "\"${secrets.getProperty("lastfm.apiKey", "")}\"")
        buildConfigField("String", "LASTFM_SECRET", "\"${secrets.getProperty("lastfm.secret", "")}\"")
        buildConfigField("boolean", "SPOTIFY", sideload.toString())
        buildConfigField("String", "APPLE_MUSIC_DEVELOPER_TOKEN", "\"$appleMusicDeveloperToken\"")
        buildConfigField("boolean", "APPLE_MUSIC_SDK", appleMusicSdk.toString())
        if (sideload) versionNameSuffix = "-sideload"
        // armeabi-v7a for Google TV boxes that run 32-bit, which many do even on 64-bit chips.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
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
            if (appleMusicSdk) proguardFiles("proguard-apple-musickit.pro")
            // Without an upload key a release build fails, unless -Ptracks.allowDebugSigning=true
            // says it's for this computer only: then it's signed with the debug key, which Play
            // refuses and which can't be trusted to sign updates.
            signingConfig = if (uploadKey != null) signingConfigs.getByName("upload") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            kotlin.directories += "engine-bindings/$variant/kotlin"
            kotlin.directories += "src/$variant/kotlin"
            jniLibs.directories += "engine-bindings/$variant/jniLibs"
            res.directories += "src/$variant/res"
            kotlin.directories += if (appleMusicSdk) "src/appleMusicSdk/kotlin" else "src/appleMusicNoSdk/kotlin"
            if (appleMusicSdk) res.directories += "src/appleMusicSdk/res"
        }
        getByName("test") {
            kotlin.directories += "src/test${variant.replaceFirstChar(Char::uppercase)}/kotlin"
            if (appleMusicSdk) kotlin.directories += "src/testAppleMusicSdk/kotlin"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        // With MusicKit, extracted as Apple's sample builds it: its JavaCPP loader isn't proven with libraries left in the APK.
        jniLibs { useLegacyPackaging = appleMusicSdk }
    }
}

androidComponents {
    onVariants { variant ->
        // MusicKit's sign-in screen needs an AppCompat theme.
        if (appleMusicSdk) variant.sources.manifests.addStaticManifestFile("src/appleMusicSdk/AndroidManifest.xml")
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
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.compose.material.icons)
    implementation(libs.tv.material)
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
    implementation(libs.zxing.core)
    implementation(libs.okhttp)
    implementation("${libs.jna.get()}@aar")
    implementation(libs.material.kolor)
    implementation(libs.serialization.json)
    implementation(libs.graphics.shapes)
    implementation(libs.palette)
    implementation(libs.splashscreen)
    implementation(libs.browser)
    implementation(libs.code.scanner)
    if (appleMusicSdk) {
        implementation(appleMusicAars)
        // What the AARs need and don't declare: AppCompat for the sign-in screens, Gson for MediaPlayback.
        implementation(libs.appcompat)
        implementation(libs.gson)
    }
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // The desktop JNA (with its native dispatcher) for running the engine in JVM tests.
    testImplementation(libs.jna)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
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
    systemProperty("jna.library.path", file("engine-bindings/$variant/host/$dir").absolutePath)
}

// Says how to build the engine when this variant's is missing, rather than failing on
// unresolved references.
val engineBindings = file("engine-bindings/$variant/kotlin")
val engineCommand = if (sideload) "Engine/build-android.sh --sideload" else "Engine/build-android.sh"
tasks.named("preBuild").configure {
    val bindings = engineBindings
    val command = engineCommand
    doFirst {
        if (!bindings.exists()) throw GradleException("Tracks: the engine for this variant isn't built. Run $command first.")
    }
}

// Says which key a release is signed with whenever one is built. Without the upload key it
// stops, so a debug-signed release never goes out by mistake, unless you opt in.
val allowDebugSigning = providers.gradleProperty("tracks.allowDebugSigning").map { it.toBoolean() }.getOrElse(false)
val signingNote = if (uploadKey == null) {
    "Tracks: this release is signed with the DEBUG key, for this computer only. It can't go to Play or update an installed release."
} else {
    "Tracks: signing this release with the upload key '${uploadKey?.alias}'."
}
val signedForPlay = uploadKey != null
val debugSigningAllowed = allowDebugSigning
val noUploadKey = "Tracks: no upload key, so this release would be signed with the debug key. Add the upload key to secrets.properties " +
    "(see secrets.example.properties), or pass -Ptracks.allowDebugSigning=true for a build that stays on this computer."
// Asked for by name: stop before anything is built.
if (!signedForPlay && !debugSigningAllowed && gradle.startParameter.taskNames.any { Regex("""(^|:)(assemble|bundle|package|install)\w*Release$""").containsMatchIn(it) }) {
    throw GradleException(noUploadKey)
}
tasks.matching { it.name.startsWith("assemble") && it.name.endsWith("Release") || it.name.startsWith("bundle") && it.name.endsWith("Release") || it.name.startsWith("package") && it.name.endsWith("Release") }.configureEach {
    val note = signingNote
    val forPlay = signedForPlay
    val allowed = debugSigningAllowed
    val refusal = noUploadKey
    doFirst {
        when {
            forPlay -> logger.lifecycle(note)
            allowed -> logger.warn(note)
            else -> throw GradleException(refusal)
        }
    }
}
