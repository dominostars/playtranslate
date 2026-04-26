pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// Lets Gradle auto-download a matching JDK when a project declares a toolchain
// (e.g. `kotlin { jvmToolchain(17) }` in app/build.gradle.kts). Paired with
// `org.gradle.java.installations.auto-download=true` in gradle.properties, this
// lets anyone build without needing to install a specific JDK first — Gradle
// will fetch one from foojay.io on demand and cache it under ~/.gradle/jdks.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // KOMORAN (Korean morphological analyzer) is distributed via JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "GameLens"
include(":app")
include(":build-tools")
