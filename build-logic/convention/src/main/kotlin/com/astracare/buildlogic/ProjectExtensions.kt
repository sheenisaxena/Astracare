package com.astracare.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/**
 * The root `libs` version catalog, reachable from inside a convention plugin.
 *
 * Convention plugins cannot use the generated `libs.` type-safe accessors — those only
 * exist in `.gradle.kts` scripts — so the catalog is looked up by name instead.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Looks up a library by its catalog alias, failing loudly with the alias name if it is
 * absent. A bare `.get()` on the empty Optional produces "No value present", which tells
 * you nothing about which dependency is missing.
 */
internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow {
        IllegalStateException("Version catalog has no library aliased '$alias'")
    }
