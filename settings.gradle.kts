pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }

    plugins {
        kotlin("jvm").version(extra["kotlin.version"] as String)
        kotlin("plugin.serialization").version(extra["kotlin.version"] as String)
        kotlin("plugin.compose").version(extra["kotlin.version"] as String)
        id("org.jetbrains.compose").version(extra["compose.version"] as String)
        id("org.jlleitschuh.gradle.ktlint").version(extra["ktlint.plugin.version"] as String)
    }
}

// Compose Hot Reload runs on JetBrains Runtime for enhanced class redefinition. Provision it on
// machines that only have a regular JDK, so `./gradlew hotRun --auto` works from a clean checkout.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "EpsonReset"
