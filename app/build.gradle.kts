import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
}

val debugProperties = Properties().apply {
    val file = rootProject.file("supabase.debug.properties")
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


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "SUPABASE_URL", quotedBuildConfigValue(debugProperties.getProperty("url", "https://qjpohzpapiwdrxsionyr.supabase.co")))
            buildConfigField("String", "SUPABASE_ANON_KEY", quotedBuildConfigValue(debugProperties.getProperty("anonKey", "")))
        }
        release {
            buildConfigField("String", "SUPABASE_URL", quotedBuildConfigValue(providers.environmentVariable("MEETO_RELEASE_URL").getOrElse("")))
            buildConfigField("String", "SUPABASE_ANON_KEY", quotedBuildConfigValue(providers.environmentVariable("MEETO_RELEASE_ANON_KEY").getOrElse("")))
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("io.tus.java.client:tus-java-client:0.5.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-video:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // Facebook Shimmer
    implementation("com.facebook.shimmer:shimmer:0.5.0")
    // Lottie Animation
    implementation("com.airbnb.android:lottie:6.4.0")
}
