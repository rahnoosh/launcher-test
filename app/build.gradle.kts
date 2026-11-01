import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Renders the Compose home screen to PNGs on the JVM (./gradlew recordPaparazziDebug).
    alias(libs.plugins.paparazzi)
}

// API keys come from local.properties (local builds) or environment variables (CI).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localOnly(name: String): String = localProps.getProperty(name)?.trim().orEmpty()

// Blank counts as missing: GitHub passes unset secrets as empty strings.
fun secret(name: String, default: String = ""): String =
    (localProps.getProperty(name)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() } ?: default).trim()

android {
    namespace = "com.launcher.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.launcher.tv"
        minSdk = 28
        targetSdk = 35
        // CI build number, so every published build is newer than the last (self-update compares these).
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = run ?: 1
        versionName = if (run != null) "0.$run" else "0.0-local"

        // API keys only from local.properties (local builds). CI builds are published publicly for
        // self-update, so they never carry keys; enter them on the TV / phone page instead.
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${localOnly("TRAKT_CLIENT_ID")}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${localOnly("TRAKT_CLIENT_SECRET")}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${localOnly("TMDB_API_KEY")}\"")
        buildConfigField("String", "SPORTSDB_API_KEY", "\"${secret("SPORTSDB_API_KEY", "123")}\"")
        // Where self-updates come from (owner/repo).
        buildConfigField("String", "UPDATE_REPO", "\"${secret("UPDATE_REPO", System.getenv("GITHUB_REPOSITORY") ?: "rahnoosh/launcher-test")}\"")
    }

    // Your own signing key (see README): the same key on every build is what lets HomeBase update itself.
    val releaseKeystore = secret("SIGNING_KEYSTORE_FILE").takeIf { it.isNotBlank() }?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = secret("SIGNING_STORE_PASSWORD")
                keyAlias = secret("SIGNING_KEY_ALIAS", "homebase")
                keyPassword = secret("SIGNING_KEY_PASSWORD").ifBlank { secret("SIGNING_STORE_PASSWORD") }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a signing key the APK still installs (debug key), but can't self-update.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // Don't block sideload builds on lint warnings.
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    // Experimental one-press TiviMate tuning over the box's own ADB.
    implementation(libs.dadb)
    // QR code for the phone page.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
