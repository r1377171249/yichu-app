plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.xiaoyichu.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.xiaoyichu.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "0.15.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    signingConfigs.getByName("debug") {
        val localDebugKey = rootProject.file(".local/debug.keystore")
        if (localDebugKey.exists()) storeFile = localDebugKey
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
