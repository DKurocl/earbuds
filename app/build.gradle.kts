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
        versionCode = 11
        versionName = "0.8.1"
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
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.material3:material3:1.4.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}


android {
    buildTypes {
        getByName("release") {
            // Personal APK: optimized, installable, using the CI debug identity.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = providers.gradleProperty("quickApk").orNull != "true"
            isShrinkResources = providers.gradleProperty("quickApk").orNull != "true"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
