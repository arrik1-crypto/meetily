plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.recap.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.recap.spike.litert"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "spike"
        // arm64 only: the only ABI the question is being asked about.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        // Debug-signed on purpose. This is a throwaway measurement tool that
        // must never share an identity with the real app.
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/*")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Resolved from gradle.properties so the CI discovery step can swap in
    // whatever coordinate actually exists without editing this file.
    val coordinate = providers.gradleProperty("spike.llmCoordinate").get()
    implementation(coordinate)
}
