plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    // Generates the App Functions service and its schema (EarbudsFunctions.kt) for Gemini.
    id("com.google.devtools.ksp")
}

// -Pscreenshots=true adds src/screenshotTest (Robolectric renders of every page and widget).
val screenshots = providers.gradleProperty("screenshots").orNull == "true"

android {
    namespace = "ua.olex.sonyanc"
    compileSdk = 37

    defaultConfig {
        applicationId = "ua.olex.sonyanc"
        // The owner's Pixel 10 Pro XL runs Android 17; 35 keeps the CI emulator image working.
        minSdk = 35
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // CI run numbers only grow, so every CI build installs over the previous one.
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()?.plus(1000) ?: 15
        versionName = "3.3.5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Style-only suggestion (KTX helpers); behaviour is identical.
    lint { disable += "UseKtx" }
    // AGP 9 compiles Kotlin itself; the extra sources are declared as Kotlin.
    if (screenshots) sourceSets["test"].kotlin.directories.add("src/screenshotTest/java")
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("screenshots", providers.gradleProperty("screenshots").orNull ?: "")
            if (screenshots) it.systemProperty("user.timezone", "UTC")
            it.systemProperty("screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
            // Renders are compared with the committed goldens; -PrecordGoldens=true rewrites them.
            it.systemProperty("goldenDir", file("src/screenshotTest/goldens").absolutePath)
            it.systemProperty("recordGoldens", providers.gradleProperty("recordGoldens").orNull ?: "")
            // Where Robolectric downloads android-all (for sandboxes where Maven Central throttles).
            providers.gradleProperty("robolectricRepo").orNull?.let { repo -> it.systemProperty("robolectric.dependency.repo.url", repo) }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // JVM screenshots (Robolectric) only for an explicit -Pscreenshots=true run: its native
    // runtime is ~160 MB, and pulling it on every CI build hit Maven Central's rate limit.
    if (screenshots) {
        testImplementation("org.robolectric:robolectric:4.14.1")
        testImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
        testImplementation("androidx.compose.ui:ui-test-junit4")
    }
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Google's Accessibility Test Framework checks on every page (AccessibilityUiTest).
    androidTestImplementation("androidx.compose.ui:ui-test-accessibility")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Older Espresso injects input through a hidden API that newer Android removed.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.appfunctions:appfunctions:1.0.0-rc01")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-rc01")
}


// Owner key from CI secrets (README, "Stable updates"). Without it the CI debug identity is
// used as before, and an update may need a reinstall.
val ownerKeystore = providers.environmentVariable("HUSH_KEYSTORE").orNull?.let(::file)?.takeIf { it.isFile }

android {
    signingConfigs {
        if (ownerKeystore != null) create("owner") {
            storeFile = ownerKeystore
            storePassword = providers.environmentVariable("HUSH_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("HUSH_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("HUSH_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        getByName("release") {
            // Personal APK: optimized and installable; stable across builds once the owner key is set.
            signingConfig = signingConfigs.findByName("owner") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = providers.gradleProperty("quickApk").orNull != "true"
            isShrinkResources = providers.gradleProperty("quickApk").orNull != "true"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
