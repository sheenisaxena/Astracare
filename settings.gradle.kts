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

// --- Git hooks auto-install ---------------------------------------------------------------
// Git never clones .git/config, so `core.hooksPath` does not survive a clone: the hook files
// arrive but git ignores them, and a fresh machine silently has no checks at all.
//
// This runs on every configuration (i.e. every Gradle sync), which makes the hooks
// self-installing — nobody has to remember a setup command.
//
// providers.exec is used rather than a plain exec so this stays configuration-cache
// compatible. Failures are swallowed: a missing git binary or a source download with no .git
// directory must not break the build over a developer convenience.
if (file(".git").exists() && file(".githooks").isDirectory) {
    runCatching {
        providers.exec {
            commandLine("git", "config", "core.hooksPath", ".githooks")
        }.result.get()
    }
}

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
