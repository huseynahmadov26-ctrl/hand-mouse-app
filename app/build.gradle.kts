import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.handmouse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.handmouse"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
    val cameraxVersion = "1.5.2"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")

    implementation("com.google.mediapipe:tasks-vision:0.10.26.1")
}

tasks.register("downloadHandModel") {
    val modelUrl = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
    val outputFile = layout.projectDirectory.file("src/main/assets/hand_landmarker.task")

    outputs.file(outputFile)

    doLast {
        val target = outputFile.asFile
        target.parentFile.mkdirs()
        if (!target.exists()) {
            println("Downloading MediaPipe Hand Landmarker model...")
            URL(modelUrl).openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            println("Model already exists at ${target.absolutePath}")
        }
    }
}
