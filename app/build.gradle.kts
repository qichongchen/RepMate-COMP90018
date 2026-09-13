plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
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
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // Bottom nav (Home/History/Leaderboard/Profile) and the exercise chip's play-triangle need
    // icons beyond the small "core" set (Home, Person, PlayArrow); the trophy and history icons
    // only live in the extended pack, so pull that in instead of hand-drawing two glyphs.
    implementation(libs.androidx.compose.material.icons.extended)
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}