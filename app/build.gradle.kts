plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVersion = (System.getenv("SIGNAR_VERSION_NAME") ?: "0.1.0").removePrefix("v")
require(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}""").matches(releaseVersion)) { "Use semantic version X.Y.Z" }
val versionParts = releaseVersion.split('.').map { it.toInt() }
val versionNumber = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]

android {
    namespace = "com.ikegami99.signar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ikegami99.signar"
        minSdk = 26
        targetSdk = 35
        versionCode = versionNumber
        versionName = releaseVersion
        buildConfigField("String", "GITHUB_REPO", "\"IKEGAMI-99/SIGNAR\"")
    }

    signingConfigs {
        create("releaseKey") {
            val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("SIGNING_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("releaseKey")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("com.google.ar:core:1.45.0")
}
