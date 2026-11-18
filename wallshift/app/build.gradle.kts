plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aravind.wallshift"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aravind.wallshift"
        minSdk = 26
        targetSdk = 34
        // GitHub Actions sets this from the run number, so every build is newer than the last.
        val build = System.getenv("WALLSHIFT_VERSION_CODE")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "2.0 (build $build)"

        // Repo whose GitHub Releases the in-app updater checks.
        buildConfigField("String", "UPDATE_REPO", "\"vkaravindam/Wallpaper-app\"")
    }

    // A fixed keystore committed with the project, so every CI build is signed
    // with the same key and new APKs install as updates over the old one.
    signingConfigs {
        create("personal") {
            storeFile = file("wallshift.keystore")
            storePassword = "wallshift"
            keyAlias = "wallshift"
            keyPassword = "wallshift"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.material:material:1.12.0")
}
