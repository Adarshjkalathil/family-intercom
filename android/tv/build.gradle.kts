import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.kalathil.intercom.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kalathil.intercom.tv"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        // Google TV chipsets are ARM, and budget TVs have little storage. The x86
        // native libraries (WebRTC, UVC) are only for emulators.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // Your own key, described by keystore/release.properties (see
        // keystore/README.md), so every build signs the same way and updates
        // install over the previous version instead of being refused. Without
        // one - a fresh clone - builds use the debug key, which is fine for
        // trying the apps out.
        val keyProps = rootProject.file("keystore/release.properties")
        if (keyProps.exists()) {
            val key = Properties().apply { keyProps.inputStream().use { load(it) } }
            create("shared") {
                storeFile = rootProject.file("keystore/" + key.getProperty("storeFile"))
                storePassword = key.getProperty("storePassword")
                keyAlias = key.getProperty("keyAlias")
                keyPassword = key.getProperty("keyPassword")
            }
        } else {
            logger.warn("No keystore/release.properties: signing with the debug key.")
        }
    }

    buildTypes {
        val shared = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")
        debug {
            signingConfig = shared
        }
        release {
            signingConfig = shared
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.uvc.camera)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.material)
}
