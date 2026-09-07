plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.lookalike"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.lookalike"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Только 64-битный ARM: телефоны с 2019 года и новее.
        // Иначе apk тянет библиотеки ещё под три архитектуры и толстеет вдвое.
        ndk { abiFilters += "arm64-v8a" }
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    // Модель и база читаются как есть: сжимать их в apk бессмысленно и мешает чтению
    androidResources {
        noCompress += listOf("onnx", "bin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}
