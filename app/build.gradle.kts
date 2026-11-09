plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ua.olex.sonyanc"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.olex.sonyanc"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // CI run numbers only grow, so every CI build installs over the previous one.
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()?.plus(1000) ?: 15
        versionName = "1.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("screenshots", providers.gradleProperty("screenshots").orNull ?: "")
            it.systemProperty("screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
            // Where Robolectric downloads android-all (for sandboxes where Maven Central throttles).
            providers.gradleProperty("robolectricRepo").orNull?.let { repo -> it.systemProperty("robolectric.dependency.repo.url", repo) }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // JVM screenshots of real composables and RemoteViews (opt-in: -Pscreenshots).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Older Espresso injects input through a hidden API that newer Android removed.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.material3:material3:1.4.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
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
