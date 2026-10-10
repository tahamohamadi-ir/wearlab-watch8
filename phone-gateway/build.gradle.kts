plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wearlab.watch.gateway"
    compileSdk = 35
    defaultConfig {
        // Must equal the watch app's applicationId: the Wearable Data Layer routes messages and
        // channels only between apps with the same package name and signing key.
        applicationId = "com.wearlab.watch"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.play.wearable)
    implementation(libs.coroutines.android)
}
