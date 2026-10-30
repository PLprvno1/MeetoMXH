import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun quotedBuildConfigValue(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

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

        buildConfigField("String", "SUPABASE_URL", quotedBuildConfigValue(localProperties.getProperty("supabase.url", "")))
        buildConfigField("String", "SUPABASE_ANON_KEY", quotedBuildConfigValue(localProperties.getProperty("supabase.anonKey", "")))

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
        buildConfig = true
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
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Firebase Authentication
    implementation("com.google.firebase:firebase-auth")
    // Cloud Firestore
    implementation("com.google.firebase:firebase-firestore")
    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // Facebook Shimmer
    implementation("com.facebook.shimmer:shimmer:0.5.0")
    // Lottie Animation
    implementation("com.airbnb.android:lottie:6.4.0")
}
