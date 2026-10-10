import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Firebase's plugin hard-fails the build when google-services.json is missing.
// That file is personal to your Firebase project and is not in the repo, so the
// plugin is applied only when you have added it. Without it the app still builds
// and everything works except waking a sleeping phone - see docs/02-firebase.md.
val googleServicesJson = file("google-services.json")
if (googleServicesJson.exists()) {
    apply(plugin = "com.google.gms.google-services")
    logger.lifecycle("google-services.json found: push notifications enabled")
} else {
    logger.warn("google-services.json missing: building WITHOUT push. The phone will only ring while the app is open.")
}

android {
    namespace = "com.kalathil.intercom.phone"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kalathil.intercom.phone"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        buildConfigField("boolean", "HAS_FIREBASE", googleServicesJson.exists().toString())

        // Real phones are ARM. The x86 builds of WebRTC are only for emulators
        // and nearly doubled the APK (49 MB -> ~24 MB without them), which is
        // enough to fail an install on a phone that is short of space.
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
        debug { signingConfig = shared }
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
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.material)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
