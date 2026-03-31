import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Fix [4.1]: `com.google.ai.edge:litertlm-android` targets Kotlin 2.2+. This module stays on Kotlin 1.9.x
// for Compose compiler / KSP stability; LiteRT-LM native APIs are not wired here — see LiteRTEngine
// and ModelImportDialog for user-visible behavior when only .litertlm is available.

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

android {
    namespace = "com.quantlm.yaser"
    compileSdk = 34

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
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // NDK configuration
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-29"
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-android-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    ksp("androidx.hilt:hilt-compiler:1.1.0")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // DataStore (for settings)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // OkHttp for model downloads
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("io.coil-kt:coil-compose:2.6.0")

    // AI & ML (MediaPipe & TensorFlow Lite)
    implementation("com.google.mediapipe:tasks-genai:0.10.32")
    implementation("com.google.mediapipe:tasks-vision:0.20230731")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
