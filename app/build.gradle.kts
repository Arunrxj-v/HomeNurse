import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.homenurse"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.homenurse"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            // Obfuscation shrinks the APK; keep kotlinx.serialization and Room entities.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(false)
        }
    }

    buildFeatures {
        compose = true
        // Server endpoints and the Google web client id are injected as
        // BuildConfig fields from environment-specific Gradle properties —
        // no server URL is hardcoded in source and no secret ships in the APK.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/*.kotlin_module"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Robolectric installs Android's Conscrypt-based security providers
            // JVM-wide; a later javax.crypto use (FileCrypto tests) then makes
            // the JDK's JarVerifier verify provider jars through Conscrypt,
            // which rejects them (DIGEST_AND_KEY_TYPE_NOT_SUPPORTED) and
            // permanently poisons javax.crypto for that fork. Forking per test
            // class keeps Robolectric and crypto tests in separate JVMs —
            // determinism costs ~20s of extra JVM starts.
            all { it.forkEvery = 1 }
        }
    }

    sourceSets {
        // Room's MigrationTestHelper loads the exported schema JSONs from the
        // *instrumentation* assets (`<fqcn>/1.json`). Wiring them into the
        // androidTest source set puts them in the test APK only — the app APK
        // itself never carries schema files.
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// ---------------------------------------------------------------------------
// Environment-specific server configuration (Development / Staging / Production).
//
// Select with:      ./gradlew assembleDebug -PserverEnv=staging
// The one backend is the Linux HomeServer, reached over the Tailscale tailnet:
// MagicDNS hostname + HTTPS, e.g. https://server.tailda589d.ts.net:8443/homenurse.
// That hostname only resolves inside the tailnet and its certificate is a
// publicly trusted Let's Encrypt one, so the app uses normal TLS validation —
// no custom trust, no cleartext, no LAN/public IP, no localhost.
//
// Port 8443 is a dedicated tailnet-only `tailscale serve` listener for this
// app. Port 443 already carries a Funnel'd (public) site on this box, and
// Tailscale 1.x marks funnel per hostname:port rather than per path — so the
// API gets its own listener instead of sharing 443, which keeps it off the
// public internet entirely.
//
// Values resolve in order: Gradle property -> environment variable -> default:
//   ./gradlew assembleDebug            (default: the HomeServer)
//   ./gradlew assembleDebug -PserverEnv=production -PPROD_API_URL=...
//   PROD_API_URL=... ./gradlew assembleDebug
// The default is a tailnet hostname, not a credential; no secrets are stored
// in this repository (server/.env and Google credentials stay off-repo).
// ---------------------------------------------------------------------------
val serverEnv: String = (findProperty("serverEnv") as String? ?: "dev").lowercase()
require(serverEnv in setOf("dev", "staging", "production")) {
    "serverEnv must be one of dev, staging, production (was '$serverEnv')"
}

fun serverValue(env: String, suffix: String, default: String): String {
    val property = findProperty("${env.uppercase()}_$suffix") as String?
    val fromEnv = System.getenv("${env.uppercase()}_$suffix")
    return (property ?: fromEnv ?: default).trimEnd('/')
}

// Auth + model distribution live behind the same HTTPS origin and path prefix.
val homeserverBaseUrl = "https://server.tailda589d.ts.net:8443/homenurse"
val apiBaseUrl = serverValue(serverEnv, "API_URL", homeserverBaseUrl)
val modelBaseUrl = serverValue(serverEnv, "MODEL_URL", apiBaseUrl)
val googleWebClientId = serverValue(serverEnv, "GOOGLE_WEB_CLIENT_ID", "")

android {
    defaultConfig {
        buildConfigField("String", "SERVER_ENV", "\"$serverEnv\"")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "MODEL_BASE_URL", "\"$modelBaseUrl\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Local database (encrypted at rest via SQLCipher, keys held in Android Keystore).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)

    // Coroutines / Flow + structured AI responses.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Document capture and import.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // On-device OCR (bundled model, fully offline).
    implementation(libs.mlkit.text.recognition)

    // Local Gemma inference runtime (Google AI Edge LiteRT-LM).
    implementation(libs.litertlm.android)

    // Medication / care reminders.
    implementation(libs.androidx.work.runtime.ktx)

    // Sign in with Google via the current Android Credential Manager API
    // (no deprecated Google Sign-In library).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.room.testing)

    // Instrumented tests: real ML Kit OCR, real SQLite migration runs, real
    // PDF rendering — all on-device, fully offline (no network permission use).
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
