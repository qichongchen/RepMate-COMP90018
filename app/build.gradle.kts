import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

// Secrets that must never be hardcoded (Golden Rule 6): read from local.properties, which is
// gitignored, with a placeholder fallback so a fresh checkout without that file still builds.
val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

android {
    namespace = "com.example.repmate"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.repmate"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Google Sign-In's Credential Manager flow needs the Firebase project's OAuth Web
        // Client ID (Firebase console > Authentication > Sign-in method > Google > Web SDK
        // configuration). Real teams add GOOGLE_WEB_CLIENT_ID=<value> to their own
        // local.properties (gitignored); this placeholder just keeps the build green for
        // anyone who hasn't set that up yet -- Google Sign-In itself won't work until it's real.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"${localProperties.getProperty("GOOGLE_WEB_CLIENT_ID", "REPLACE_WITH_FIREBASE_WEB_CLIENT_ID")}\"",
        )

        buildConfigField(
            "String",
            "TEST_ACCOUNT_A_EMAIL",
            "\"${localProperties.getProperty("TEST_ACCOUNT_A_EMAIL", "")}\"",
        )

        buildConfigField(
            "String",
            "TEST_ACCOUNT_A_PASSWORD",
            "\"${localProperties.getProperty("TEST_ACCOUNT_A_PASSWORD", "")}\"",
        )

        buildConfigField(
            "String",
            "TEST_ACCOUNT_B_EMAIL",
            "\"${localProperties.getProperty("TEST_ACCOUNT_B_EMAIL", "")}\"",
        )

        buildConfigField(
            "String",
            "TEST_ACCOUNT_B_PASSWORD",
            "\"${localProperties.getProperty("TEST_ACCOUNT_B_PASSWORD", "")}\"",
        )

        buildConfigField(
            "String",
            "TEST_ACCOUNT_B_UID",
            "\"${localProperties.getProperty("TEST_ACCOUNT_B_UID", "")}\"",
        )
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // Needed for BuildConfig.DEBUG, which gates the debug-only "open sensor probe" button on
        // the home placeholder (see RepMateDestinations.HOME in NavGraph.kt). AGP 8+ makes
        // BuildConfig generation opt-in, so this must be explicit.
        buildConfig = true
    }

}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // Bottom nav (Home/History/Leaderboard/Profile) and the exercise chip's play-triangle need
    // icons beyond the small "core" set (Home, Person, PlayArrow); the trophy and history icons
    // only live in the extended pack, so pull that in instead of hand-drawing two glyphs.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Coroutines + Flow: the engine consumes a Flow<MotionFrame> and DeviceSensorSource
    // builds one with callbackFlow. The -android artifact adds Dispatchers.Main on top of
    // coroutines-core, which is what lets a ViewModel collect frames on the main dispatcher.
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // kotlin.test for the engine's replay tests. This must be kotlin-test-JUNIT, not
    // plain kotlin-test: on the JVM kotlin.test.Test is an expect annotation whose actual
    // is a typealias for org.junit.Test, and it lives in the framework-specific artifact.
    // The Kotlin Gradle plugin picks that variant automatically; AGP's built-in Kotlin
    // support does not, so it is named explicitly here.
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Lets a @Composable pull a @HiltViewModel via hiltViewModel() (SignUpScreen's AuthViewModel).
    // Not androidx.hilt:hilt-navigation-compose: as of Hilt 1.3.0, that artifact's hiltViewModel()
    // is deprecated in favor of this one, which drops the transitive androidx.navigation
    // dependency -- nothing here needs nav-graph-scoped ViewModel sharing, so no reason to keep it.
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Firebase Auth + Firestore
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.auth)
        implementation(libs.firebase.firestore)

    // Turns FirebaseAuth's Task<T> results into suspend calls (.await()) instead of listeners.
        implementation(libs.kotlinx.coroutines.play.services)

    // Google Sign-In via Android's Credential Manager.
        implementation(libs.androidx.credentials)
        implementation(libs.androidx.credentials.play.services.auth)
        implementation(libs.googleid)

    // Per-UID onboarding-shown flag (OnboardingPreferences) -- Preferences DataStore, not
    // Firestore, since this is purely local/on-device state.
    implementation(libs.androidx.datastore.preferences)

    // Push-up camera workout: CameraX preview/analysis pipeline, plus ML Kit's on-device pose
    // model to find the shoulder/elbow/wrist landmarks com.repmate.pose turns into an angle.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // The base (fast) on-device model, bundled into the APK: no network, no API key.
    implementation(libs.mlkit.pose.detection)
}