import java.util.Properties

plugins {
    id("com.android.application")
    // No org.jetbrains.kotlin.android: AGP 9 compiles Kotlin itself (see the
    // root build.gradle.kts). Only the Compose compiler plugin is applied.
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load release signing credentials from keystore.properties (gitignored) if present.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// ---------------------------------------------------------------------------
// A build with no Google Play Services.
//
//   ./gradlew :app:assembleDebug -Pnogms=true
//
// drops com.google.android.play:integrity (and play-services-tasks and
// -basement with it), compiles the stub PlayIntegrityAttester in
// src/nogms/java instead of the real one in src/play/java, and sets the
// leaderboard's cloud project number to 0 — which the app already reads as
// "not configured" and handles throughout (LeaderboardClient.isConfigured).
// The result is an APK with no Google code in it at all, which installs and
// runs on a device that has no Play Store and no GMS.
//
// What such a build gives up is the shared leaderboard and the buddy streak,
// and that is not a loss this toggle causes: docs/high-scores-design.md §3
// makes attestation the trust root on purpose, so a self-built or sideloaded
// copy is rejected by the server whether or not it can produce a token.
// Everything else — every training mode, every game, local personal bests,
// stats, hardware keys, account sync — is untouched.
//
// Why a property and not a product flavor: a flavor renames every output
// path (apk/play/debug/app-play-debug.apk,
// bundle/playRelease/app-play-release.aab, mapping/playRelease/), which means
// editing the Play upload workflow, three artifact paths in android-ci.yml
// and the APK search in android-smoke.sh — release machinery, edited blind.
// This toggle leaves the shipping build byte for byte as it was, and a fork
// that publishes a GMS-free APK adds `-Pnogms=true` to its own workflow and
// changes nothing else.
//
// `-Pnogms` with no value is accepted too, but Gradle's handling of a
// valueless property is version-dependent, so the configuration log below is
// what actually confirms which build you are getting. Read it.
val nogmsProp: String? = providers.gradleProperty("nogms").orNull
val noGms = nogmsProp != null && nogmsProp != "false" && nogmsProp != "0"
if (noGms) {
    logger.lifecycle(
        "AMT: building WITHOUT Google Play Services " +
            "(no Play Integrity; shared leaderboard and buddy streak are off)"
    )
}

// Added to versionCode below. Android installs an update only over a LOWER
// versionCode, so anything that publishes a series of APKs outside a store
// needs a counter — and `versionCode` itself cannot be it, because that number
// belongs to the Play uploads and moves only when the maintainer says so
// (CLAUDE.md, Versions: a build number Play has accepted can never be reused).
//
// So a side channel: `-PversionCodeExtra=7` builds versionCode 30+7. Unset it
// is 0, which is what every existing build passes, so the Play build's number
// is exactly what it was. Intended for a fork's own release workflow, which
// can pass github.run_number and never touch this file.
val versionCodeExtra = providers.gradleProperty("versionCodeExtra").orNull?.toInt() ?: 0

android {
    namespace = "app.anothermorsetrainer"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.anothermorsetrainer"
        minSdk = 24
        // Left at 36 on purpose: the toolchain upgrade that took compileSdk to
        // 37 does not need targetSdk to follow, and raising it changes the
        // app's runtime behaviour on Android 17 devices, which nothing here can
        // test (the smoke test emulator runs API 34). Move it as its own change.
        targetSdk = 36
        // 30 is the Play counter; versionCodeExtra is 0 for every build that
        // does not pass -PversionCodeExtra (see the note at the top of this file).
        versionCode = 30 + versionCodeExtra
        versionName = "2.0.0"

        // The Google Cloud project NUMBER Play Integrity decodes tokens for:
        // project `amt-leaderboard`, the numeric "Project number" on the Cloud
        // console dashboard (not the project id), linked in Play Console on
        // 2026-09-08. Read by LeaderboardClient.LEADERBOARD_CLOUD_PROJECT_NUMBER.
        //
        // 0 means "not configured": the client attests nothing, submits nothing
        // and shows nothing, so the feature behaves as though it were off. That
        // is what a -Pnogms=true build gets, because without the Play Integrity
        // library there is nothing that could produce a token.
        buildConfigField(
            "long",
            "LEADERBOARD_CLOUD_PROJECT_NUMBER",
            if (noGms) "0L" else "39877526242L"
        )

        // Whether this APK actually contains com.google.android.play:integrity,
        // which decides whether Google's notice belongs on Settings › Help &
        // About › Licenses (CLAUDE.md, Licensing: the notice goes on every port
        // that *ships* it). Deliberately not the same question as
        // LeaderboardClient.isConfigured: a Play build with the project number
        // unset still ships the SDK and still owes the notice.
        buildConfigField("boolean", "SHIPS_PLAY_INTEGRITY", if (noGms) "false" else "true")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 was off, so release builds shipped every generated icon in
            // material-icons-extended (the app references about 30 of them)
            // along with full symbol names and unreachable code. Tree-shaking
            // this is the largest APK-size lever available here.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // java.time on minSdk 24
    }
    // No kotlinOptions { jvmTarget } block: AGP 9 removed it, and under built-in
    // Kotlin jvmTarget defaults to compileOptions.targetCompatibility (17).
    buildFeatures {
        compose = true
        // Off by default since AGP 8. Turned on for the single field above,
        // which is the one thing a -Pnogms=true build has to change in code
        // the app reads at runtime.
        buildConfig = true
    }

    // AGP ships lint for free and nothing was running it. Path exclusions live
    // in lint.xml next to this file — the vendored CW decoder is kept
    // byte-identical to a firmware copy, so lint must not have opinions about it.
    // The shared timing fixture lives at the repo root, above both app trees,
    // because it is consumed by the iOS harness too (fixtures/timing.json). Put
    // it on the unit-test classpath so MorseTimingTest can read it by name
    // rather than walking relative paths out of the module.
    sourceSets {
        getByName("main") {
            // PlayIntegrityAttester exists twice — the real one against the Play
            // Integrity SDK, and a stub for the -Pnogms=true build, which has no
            // SDK to compile against. Exactly one directory is ever on the source
            // path, so the two can share a fully-qualified name.
            //
            // `kotlin`, not `java`: under AGP 9's built-in Kotlin these are
            // separate source directory sets, and a .kt file under a `java`
            // directory is not compiled. That failure is silent in the only way
            // that matters — the directory is accepted, nothing reads it, and the
            // build fails later with "Unresolved reference 'PlayIntegrityAttester'"
            // from LeaderboardClient, which reads like a missing file rather than
            // a misconfigured source set. Both directories are named `java/` to
            // match src/main/java/ next door; the directory name is not what AGP
            // keys on.
            kotlin.directories += file(if (noGms) "src/nogms/java" else "src/play/java").path
        }
        getByName("test") {
            // `directories` is AGP 9's replacement for the deprecated srcDir();
            // it takes path strings, evaluated as project.file().
            resources.directories += rootProject.file("../fixtures").path
        }
    }

    lint {
        lintConfig = file("lint.xml")
        // Fail the build on lint *errors*; warnings are reported, not fatal.
        // Raising warnings to errors is a decision to make after reading a run,
        // not before.
        abortOnError = true
        warningsAsErrors = false
        // Printed in the CI log. The HTML and XML reports the workflow uploads
        // are always written under AGP 9 (textReport/htmlReport/xmlReport are
        // deprecated no-ops now); printTextReport is what still chooses stdout
        // over the abbreviated console summary.
        printTextReport = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // 2026.08.00 pulls Compose 1.12.0, which requires AGP 9.2+ and compileSdk
    // 37 — both satisfied above. (It was held at 2026.06.01 / Compose 1.11.4
    // until the AGP 9 upgrade landed.)
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // MediaSessionCompat + the media-style notification for Listen & Learn:
    // the only way a Bluetooth head unit or the lock screen gets the current
    // item and the app's artwork (#184), as iOS's MPNowPlayingInfoCenter does.
    implementation("androidx.media:media:1.7.0")
    // WebSocket transport for the Vail repeater client (and the Short
    // Stories news-feed fetch).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // The only Google Play dependency in the app, and the only reason it needs
    // Play Services on the device at all — so a -Pnogms=true build omits both
    // of these and gets the src/nogms/java stub instead (see the toggle at the
    // top of this file).
    if (!noGms) {
        // Play Integrity, standard API: the device attestation behind every write
        // to the shared leaderboard (docs/high-scores-design.md §3; the client is
        // LeaderboardClient + PlayIntegrityAttester). Pulls play-services-tasks
        // and -basement. Its notice is on Settings › Help & About › Licenses, as the
        // Licensing section of CLAUDE.md requires. 1.6.0 was the newest release on
        // maven.google.com on 2026-09-08.
        implementation("com.google.android.play:integrity:1.6.0")
        // Not used by the app directly. play-services-basement (pulled by
        // integrity above) declares androidx.fragment:fragment:1.1.0 — still so in
        // basement 18.12.0, the newest on 2026-09-25 — and Play Console's SDK
        // Index flags 1.1.0 as outdated. Declaring it here lets Gradle resolve the
        // current stable instead. Drop this once basement's POM moves past 1.2.1.
        //
        // Inside the `if` with integrity because that is the only thing that
        // brings basement in: with no basement there is no fragment 1.1.0 to
        // outrank, and nothing else here uses fragment.
        implementation("androidx.fragment:fragment:1.9.1")
    }
    // EncryptedSharedPreferences for the accounts sign-in's access and refresh
    // tokens (AccountClient): encrypted at rest under an Android Keystore key.
    // Pulls Google Tink. Apache-2.0; its notice is on Settings › Help & About ›
    // Licenses, as the Licensing section of CLAUDE.md requires. 1.1.0 is the
    // stable line; it marks the API deprecated in favour of using Keystore
    // directly, with no replacement library, and it still works on every
    // supported API level.
    implementation("androidx.security:security-crypto:1.1.0")
    // JVM unit tests: the ported CW decoder core is held to the firmware
    // bench's synthetic-audio checks.
    testImplementation("junit:junit:4.13.2")
    // A real org.json for unit tests. The org.json in android.jar is a stub that
    // throws "Stub!" on every call, so parsing the shared fixture on the JVM
    // needs the actual implementation ahead of it on the classpath.
    testImplementation("org.json:json:20260814")
}
