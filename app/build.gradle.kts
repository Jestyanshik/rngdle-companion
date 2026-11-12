plugins {
    id("com.android.application") version "8.9.2"
    id("org.jetbrains.kotlin.android") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

val releaseRepository = providers.gradleProperty("releaseRepository").orElse("").get()
require(releaseRepository.isEmpty() || releaseRepository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
val signingFile = providers.environmentVariable("RNGDLE_KEYSTORE").orNull

android {
    namespace = "app.rngdle.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.rngdle.companion"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"
        buildConfigField("String", "RELEASE_REPOSITORY", "\"$releaseRepository\"")
    }

    if (signingFile != null) {
        signingConfigs.create("distribution") {
            storeFile = file(signingFile)
            storePassword = providers.environmentVariable("RNGDLE_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("RNGDLE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("RNGDLE_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            if (signingFile != null) signingConfig = signingConfigs.getByName("distribution")
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
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    testImplementation("junit:junit:4.13.2")
}
