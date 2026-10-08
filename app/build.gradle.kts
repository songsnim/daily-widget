plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.daily.widget"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.daily.widget"
        minSdk = 31
        targetSdk = 35
        // CI passes these from the release tag so Obtainium sees each release as newer.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.glance:glance-appwidget:1.1.0")
    testImplementation("junit:junit:4.13.2")
}
