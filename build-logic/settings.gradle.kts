// build-logic is a separate Gradle build ("included build") that produces the project's
// convention plugins. It is wired in from the root settings.gradle.kts via
// pluginManagement { includeBuild("build-logic") }.
//
// It must declare its own repositories and re-use the root version catalog, because an
// included build does not inherit either from the build that includes it.

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
