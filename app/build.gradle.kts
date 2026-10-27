plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "uk.org.beebem.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.org.beebem.android.gamepad"
        minSdk = 28
        targetSdk = 36
        // CI builds number themselves from the workflow run, so each one is a
        // newer version than the last (used by the GitHub release and Obtainium).
        // The name matches the release tag (v0.2.N) exactly, so Obtainium can
        // read the installed version rather than trusting its own record.
        val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = 2 + (ciBuild ?: 0)
        versionName = "0.2.${ciBuild ?: 0}"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    // Fixed debug signing key, kept in the repository, so that every CI build is
    // signed the same way and a new APK installs over the previous one (keeping
    // the app's saved settings). It only identifies these test builds and is not
    // a secret; a Play Store release would need its own private key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("beebem-debug.keystore")
            storePassword = "android"
            keyAlias = "beebem"
            keyPassword = "android"
        }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
