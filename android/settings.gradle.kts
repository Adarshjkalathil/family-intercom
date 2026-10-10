pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // UVCAndroid publishes here as well as to Maven Central; keeping jitpack
        // as a fallback means a version bump has somewhere to resolve from.
        maven("https://jitpack.io")
    }
}

rootProject.name = "grandpa-intercom"
include(":core", ":tv", ":phone")
