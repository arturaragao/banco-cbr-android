plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStore = rootProject.file("keystore.p12")
val releasePassword = providers.environmentVariable("KEYSTORE_PASS").orNull
val canSignRelease = releaseStore.isFile && !releasePassword.isNullOrBlank()

android {
    namespace = "io.github.arturaragao.bancocbr"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.arturaragao.bancocbr"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("versionCode").orElse("3").get().toInt()
        versionName = providers.gradleProperty("versionName").orElse("1.9.0").get()
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseStore
                storeType = "PKCS12"
                storePassword = releasePassword
                keyAlias = "bcbr"
                keyPassword = releasePassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
