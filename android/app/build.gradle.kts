import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is driven by a keystore.properties file or environment
// variables (set in the release CI workflow from repository secrets). When
// neither is present, the release build falls back to the debug key so the
// normal sideload APK build keeps working without any secrets.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

val releaseStoreFilePath = signingValue("storeFile", "MEETILY_KEYSTORE_FILE")
val hasReleaseKeystore = releaseStoreFilePath != null &&
    rootProject.file(releaseStoreFilePath).exists()

android {
    namespace = "com.meetily.mobile"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.recap.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 91
        versionName = "3.11.1-beta1"

        // No ndk.abiFilters here: AGP forbids it alongside ABI splits. The
        // splits.abi.include list below is the single source of built ABIs.
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The onnxruntime + sherpa-onnx native libs are large (~24 MB/ABI), so a
    // universal APK balloons past sideload-friendly sizes. Ship one APK per
    // ABI instead (phones = arm64-v8a, emulators = x86_64) and compress the
    // native libs inside the APK. Play releases use the AAB, which splits by
    // ABI on its own, so this only affects assembleRelease output.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = signingValue("storePassword", "MEETILY_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "MEETILY_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "MEETILY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Pack native symbol tables into the AAB so Play's Android
            // Vitals can symbolicate crashes in the whisper/llama/sherpa
            // .so files instead of showing raw addresses.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            // Use the real upload key when a keystore is configured (release
            // workflow); otherwise fall back to the debug key so the normal
            // sideload APK build stays green without any secrets.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.biometric:biometric:1.1.0")
    // Bundled on-device Latin OCR for whiteboard/photo text (no network).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests — the android.jar mockable stubs
    // throw "Stub!" for JSONObject/JSONArray otherwise.
    testImplementation("org.json:json:20240303")
}
