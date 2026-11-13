import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ============================================================================
// Single source of truth for prototype endpoint URLs.
// Change these two constants (or pass -PBACKEND_BASE_URL=... / -PSIMULATOR_BASE_URL=...)
// when switching between:
//   1. Android Emulator:     http://10.0.2.2:8080/ and http://10.0.2.2:8001/
//   2. Physical phone (LAN): http://<PC_LAN_IP>:8080/ and http://<PC_LAN_IP>:8001/
//   3. USB adb reverse:      http://localhost:8080/ and http://localhost:8001/
// ============================================================================
val backendBaseUrl = (project.findProperty("BACKEND_BASE_URL") as String?) ?: "http://192.168.18.68:8080/"
val simulatorBaseUrl = (project.findProperty("SIMULATOR_BASE_URL") as String?) ?: "http://192.168.18.68:8001/"

android {
    namespace = "com.kilowhat.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kilowhat.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
        buildConfigField("String", "SIMULATOR_BASE_URL", "\"$simulatorBaseUrl\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
}
