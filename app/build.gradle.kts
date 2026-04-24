plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.preview)
    alias(libs.plugins.metro)
}

android {
    namespace = "ee.schimke.terrazzo"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "ee.schimke.terrazzo"
        // Widget playback via RemoteViews.DrawInstructions needs API 35+
        // (VANILLA_ICE_CREAM). minSdk 36 so the D8 dex compiler accepts
        // the build (alpha compilers warn on 37+); we still compile / target
        // the newest available SDK via `compileSdk` / `targetSdk`.
        minSdk = 36
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // Exposed to AndroidManifest via placeholders so the IndieAuth
        // redirect scheme is declared in one place.
        manifestPlaceholders["appAuthRedirectScheme"] = "rcha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }
    kotlin { jvmToolchain(libs.versions.java.get().toInt()) }
}

composePreview {
    variant.set("debug")
    sdkVersion.set(35)
    enabled.set(true)
}

dependencies {
    implementation(project(":ha-model"))
    implementation(project(":ha-client"))
    implementation(project(":rc-converter"))
    implementation(project(":rc-components"))
    implementation(project(":terrazzo-core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.materialkolor)

    implementation(libs.remote.creation.compose)
    implementation(libs.remote.creation.core)
    implementation(libs.remote.player.compose)
    implementation(libs.remote.tooling.preview)

    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.material3.adaptive.navigation.suite)
    implementation(libs.appauth)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // ui-test-manifest bundles a ComponentActivity into the debug APK so
    // `createComposeRule()` can host @Composable content without an
    // Activity of our own.
    debugImplementation(libs.compose.ui.test.manifest)
}
