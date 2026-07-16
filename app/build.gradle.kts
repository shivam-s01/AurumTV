plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aurum.musictv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aurum.musictv"
        minSdk = 21          // Android TV boxes go back to 5.0 Lollipop
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
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
    kotlinOptions {
        jvmTarget = "17"
    }

    // Only ship the ABI most TV boxes use — cuts APK size a lot.
    // arm64-v8a covers virtually all modern Android TV / Fire TV / Google TV
    // hardware. Add armeabi-v7a back in only if you must support very old
    // boxes (adds ~2-3MB).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/**",
            "kotlin/**",
            "**/*.kotlin_metadata"
        )
    }
}

dependencies {
    // Leanback: the classic lightweight Android TV UI toolkit (rows, browse
    // fragment, D-pad focus handling built in). Far lighter than Compose-TV.
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-preference:1.0.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

    // Media3 / ExoPlayer — same engine family as your phone app's native
    // AurumAudioEngine. Core + only the HTTP datasource, no extra extractors
    // you don't need, keeps this lean.
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-session:1.4.0")
    implementation("androidx.media3:media3-datasource:1.4.0")

    // Coil: much smaller footprint than Glide/Picasso for simple poster/art
    // loading, Kotlin-first, good TV-safe defaults.
    implementation("io.coil-kt:coil:2.6.0")

    // Networking: OkHttp directly (no Retrofit) + manual org.json parsing
    // to avoid pulling in Retrofit + Moshi/Gson (~1-1.5MB combined saved).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
