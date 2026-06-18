import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`) is fully wired: LiteRTEngine drives the
// native Engine / Conversation API for `.litertlm` models, including Gemma 3n / Gemma 4 multimodal
// (vision + audio) bundles routed via ModelRepositoryImpl.loadWithLiteRTEngine().

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingProp(key: String): String? =
    keystoreProperties.getProperty(key)?.trim()?.removeSurrounding("\"")

val requiredSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasCompleteSigningConfig = requiredSigningKeys.all {
    !signingProp(it).isNullOrBlank()
}
val releaseStoreFilePath = signingProp("storeFile")
val releaseStoreFile = releaseStoreFilePath?.let { file(it) }
val useReleaseSigning = hasCompleteSigningConfig
val supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
val requestedTaskNames = gradle.startParameter.taskNames.map { it.lowercase() }
val isBundleTaskRequested = requestedTaskNames.any { it.contains("bundle") }
val enableAbiSplits = !isBundleTaskRequested

android {
    namespace = "com.quantlm.yaser"
    compileSdk = 35

    signingConfigs {
        create("release") {
            if (useReleaseSigning && releaseStoreFile != null) {
                storeFile = releaseStoreFile!!
                storePassword = signingProp("storePassword")
                keyAlias = signingProp("keyAlias")
                keyPassword = signingProp("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.quantlm.yaser"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // NDK configuration
        ndk {
            if (!enableAbiSplits) {
                abiFilters += supportedAbis
            }
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-29",
                    "-DUSE_VULKAN=ON",
                    // Cap ninja parallelism: the arm64 variant build compiles the
                    // ggml CPU backend seven times, and unbounded -O3 clang jobs
                    // OOM-kill the Gradle daemon on 8 GB build hosts.
                    "-DCMAKE_JOB_POOLS:STRING=compile=4;link=2",
                    "-DCMAKE_JOB_POOL_COMPILE:STRING=compile",
                    "-DCMAKE_JOB_POOL_LINK:STRING=link"
                )
                // Build the JNI library and the dlopen-only ggml CPU variants.
                // Listing targets (a) skips the llama.cpp CLI tools, one of
                // which (llama-cli) does not cross-compile in this snapshot,
                // and (b) is what makes AGP PACKAGE the variant .so files —
                // they are loaded at runtime by HWCAP score, not linked, so
                // they are outside quantlm's link closure. The armeabi-v7a
                // CMake branch defines no-op stand-ins for these names (this
                // list is ABI-global).
                targets += listOf(
                    "quantlm",
                    "ggml-cpu-android_armv8.0_1",
                    "ggml-cpu-android_armv8.2_1",
                    "ggml-cpu-android_armv8.2_2",
                    "ggml-cpu-android_armv8.6_1",
                    "ggml-cpu-android_armv9.0_1",
                    "ggml-cpu-android_armv9.2_1",
                    "ggml-cpu-android_armv9.2_2"
                )
            }
        }
    }

    buildTypes {
        release {
            if (useReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }

    splits {
        abi {
            isEnable = enableAbiSplits
            reset()
            include(*supportedAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    sourceSets {
        getByName("main") {
            java {
                setSrcDirs(listOf("src/main/java/com/quantlm"))
            }
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        jniLibs {
            // Extract native libs to disk on install: the arm64 build packages
            // dynamically loadable ggml CPU-backend variants
            // (libggml-cpu-android_*.so) that ggml_backend_load_all_from_path
            // discovers by scanning nativeLibraryDir — which only contains real
            // files when legacy packaging is on. Costs install-size, buys
            // 2-4x faster quantized decode on dotprod/i8mm-capable SoCs.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // Required for ProcessLifecycleOwner (whole-app foreground/background callbacks).
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose (BOM aligns Material3 surface tokens, automirrored icons, etc.)
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Material Components for Android (provides Material3 DayNight themes and attrs)
    implementation("com.google.android.material:material:1.9.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Room Database
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-android-compiler:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // DataStore (for settings)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // OkHttp for model downloads and web search
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Jsoup — parses DuckDuckGo result pages and strips scraped web pages to clean
    // text for the Web Search feature. Regex HTML parsing would be too fragile.
    implementation("org.jsoup:jsoup:1.17.2")

    implementation("io.coil-kt:coil-compose:2.6.0")

    // AI & ML
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")

    // MediaPipe & TensorFlow Lite
    implementation("com.google.mediapipe:tasks-genai:0.10.32")
    // Provides com.google.mediapipe.framework.image (MPImage / BitmapImageBuilder),
    // required by tasks-genai's LLM vision API (LlmInferenceSession.addImage).
    implementation("com.google.mediapipe:tasks-core:0.10.32")
    // Required by tasks-genai AutoValue-generated models; kept explicit so tasks-vision can remain removed.
    implementation("com.google.auto.value:auto-value-annotations:1.11.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
