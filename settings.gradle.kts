pluginManagement {
    // Convention plugins live in a separate included build. This must be inside
    // pluginManagement (not a top-level includeBuild) for the astracare.* plugin ids to
    // be resolvable from module build scripts.
    includeBuild("build-logic")

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Must match the project directory name — changing it invalidates every cached
// IDE module entity ("Can't find module entity for Astracare.app").
rootProject.name = "Astracare"

include(":app")

// Kotlin JVM modules — no android.* on the classpath, so platform independence is
// enforced by the compiler rather than by convention.
include(":core:model")          // pure domain models
include(":core:common")         // dispatchers, Result wrapper, extensions
include(":core:domain")         // use cases + repository INTERFACES

// Android library modules
include(":core:data")           // Room + mock remote + repository IMPLS
include(":core:sync")           // WorkManager sync engine (no UI -> not a :feature: module)
include(":core:designsystem")   // Compose M3 theme + shared components

include(":feature:patients")    // capture screen, history list, MVI ViewModel

// :macrobenchmark is added alongside the performance work
