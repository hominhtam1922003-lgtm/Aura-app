import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.nysa"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.nysa"
        minSdk = 26
        targetSdk = 34
        versionCode = 18
        versionName = "1.0.17"
    }

    signingConfigs {
        create("releaseFixed") {
            // Fixed signing key for ALL NySa releases: later versions
            // install over earlier ones without uninstalling (and NySa
            // never clashes with the separate Nflix app). CI injects
            // the key via env; local builds fall back to the bundled
            // keystore + keystore.properties (git-ignored, see
            // ~/workspace/nysa/signing-info.txt).
            val ksFile = System.getenv("NYSA_KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("NYSA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NYSA_KEY_ALIAS")
                // PKCS12 keystore: the key password IS the store password
                // (proven locally with jarsigner 2026-11-04). Never read a
                // separate NYSA_KEY_PASSWORD secret here — the value kept
                // for it cannot recover the key and failed
                // :app:packageRelease in CI runs #3 and #4.
                keyPassword = System.getenv("NYSA_KEYSTORE_PASSWORD")
            } else {
                val propsFile = rootProject.file("keystore.properties")
                if (propsFile.exists()) {
                    val props = Properties().apply {
                        propsFile.inputStream().use { load(it) }
                    }
                    storeFile = file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("NYSA_KEYSTORE_FILE") != null ||
                rootProject.file("keystore.properties").exists()
            )
                signingConfigs.getByName("releaseFixed")
            else
                signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    // NOTE: Haze (backdrop blur) was tried in 1.0.1 and REMOVED in
    // 1.0.2 — 1.0.1 crashed on launch on the user's Xiaomi. Do not
    // re-add it without an on-device test first.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")

    // Playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    // Images + network
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")

    // Local state store (favorites / following / history / progress)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Background new-episode checks + notifications
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
