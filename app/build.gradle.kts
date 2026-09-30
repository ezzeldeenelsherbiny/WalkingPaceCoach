plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.openai.walkingpacecoach"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openai.walkingpacecoach"
        minSdk = 26
        targetSdk = 36

        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true

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
    jvmToolchain(17)

    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        )
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {

    // Compose
    val composeBom = platform(
        "androidx.compose:compose-bom:2025.08.00"
    )

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    // Lifecycle / ViewModel
    implementation(
        "androidx.lifecycle:lifecycle-runtime-ktx:2.10.0"
    )
    implementation(
        "androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0"
    )
    implementation(
        "androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0"
    )

    // Jetpack Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // GPS / Fused Location Provider
    implementation(
        "com.google.android.gms:play-services-location:21.4.0"
    )

    // Room local database
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")

    // Coroutines
    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2"
    )
}

