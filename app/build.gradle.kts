plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.utility.dlmsreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.utility.dlmsreader"
        minSdk = 26
        targetSdk = 35
        // CI publishes tag v1.0.<run> with versionCode = 100 + run. Local builds
        // use the current release line so About and the update check agree.
        val ciBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = (ciBuildNumber ?: 0) + 100
        versionName = "1.0.${ciBuildNumber ?: 58}"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        // Permanent, committed key shared by local builds and GitHub Actions.
        // Every APK therefore has the same signature and installs in place.
        create("release") {
            storeFile = file("meterlink-release.jks")
            storePassword = "meterlink123"
            keyAlias = "meterlink"
            keyPassword = "meterlink123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "OBISCodes.txt",
                "SaudiArabia.txt",
                "Italy.txt",
                "Spain.txt",
                "India.txt",
                "**/*.kotlin_builtins"
            )
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real org.json implementation for JVM unit tests; the Android runtime
    // already provides org.json, so this is test-only and adds no app weight.
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Gurux DLMS Core Engine
    implementation("org.gurux:gurux.dlms:4.0.97")

    // USB Serial Host Driver
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}
