plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mobileapp.iot_sign_language_recognition"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mobileapp.iot_sign_language_recognition"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures{
        viewBinding = true
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(project(":openCV"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    val cameraX_version = "1.5.0-alpha03"

    // cameraX dependencies
    implementation("androidx.camera:camera-core:${cameraX_version}")
    implementation("androidx.camera:camera-camera2:${cameraX_version}")
    implementation("androidx.camera:camera-lifecycle:${cameraX_version}")
    implementation("androidx.camera:camera-video:${cameraX_version}")
    implementation("androidx.camera:camera-mlkit-vision:${cameraX_version}")
}