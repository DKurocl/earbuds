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
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()?.plus(1000) ?: 13
        versionName = "0.10.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.material3:material3:1.4.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}


// Owner key from CI secrets (see README, "Stable updates"). Without it, the CI debug identity
// is used as before, and each CI run may need a reinstall.
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
            // Personal APK: optimized and installable; stable when the owner key is configured.
            signingConfig = signingConfigs.findByName("owner") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = providers.gradleProperty("quickApk").orNull != "true"
            isShrinkResources = providers.gradleProperty("quickApk").orNull != "true"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
