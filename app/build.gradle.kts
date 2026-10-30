plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.procam.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.procam.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // Jetpack Compose & UI
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.8.2")

    // CameraX (कॅमेरा आणि 4K OIS)
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")

    // Google Media3 Transformer (व्हिडिओ इंजिन)
    val media3Version = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-transformer:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // ⚡ AI Cloud API साठी हाय-स्पीड नेटवर्क लायब्ररी
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
