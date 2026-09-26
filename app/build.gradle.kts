plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.meetomxh"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.meetomxh"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    implementation("com.google.firebase:firebase-storage")
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Firebase Authentication
    implementation("com.google.firebase:firebase-auth")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Cloud Firestore
    implementation("com.google.firebase:firebase-firestore")
}