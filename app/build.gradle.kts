plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes the run number so every build gets a higher versionCode than the
// last. The updater compares this against the release tag.
val buildVersionCode = (System.getenv("VERSION_CODE") ?: "1").toIntOrNull() ?: 1

// Release signing comes from the environment so the keystore never lands in git.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val hasKeystore = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "com.farhan.wristops"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.farhan.wristops"
        minSdk = 30
        targetSdk = 34
        versionCode = buildVersionCode
        versionName = "0.$buildVersionCode"
    }

    signingConfigs {
        if (hasKeystore) {
            create("upload") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "wristops"
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a keystore we fall back to the debug key so the APK is at
            // least installable. That key is regenerated on every CI runner, so
            // self-update will refuse such a build: set the secrets before the
            // first install.
            signingConfig = if (hasKeystore) {
                signingConfigs.getByName("upload")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
