plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.t3widget"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.t3widget"
        minSdk = 26
        targetSdk = 36
        // CI (release-apk.yml) sets these; local builds stay at the last hand-cut version.
        versionCode = System.getenv("CI_VERSION_CODE")?.toIntOrNull() ?: 4
        versionName = System.getenv("CI_VERSION_NAME") ?: "0.3.1"
    }

    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_FILE")
    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.androidsvg)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
