plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.marco.panicrelay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.marco.panicrelay"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.8"
    }

    // NOTE: chiave di firma e password sono ILLUSTRATIVE (debug/sample) per privacy.
    // Per uso reale, sostituiscile con le tue e/o tieni il repository privato.
    signingConfigs {
        create("stable") {
            storeFile = file("panicrelay.keystore")
            storePassword = "changeme-dev-key"
            keyAlias = "panicrelaykey"
            keyPassword = "changeme-dev-key"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
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
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}
