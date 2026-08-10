// Top-level build file. Plugins are declared with `apply false` so subprojects and the
// build-logic convention plugins can apply them without re-declaring versions.
//
// NOTE: org.jetbrains.kotlin.android is deliberately absent. AGP 9 ships built-in Kotlin
// support, and applying the standalone Kotlin Android plugin alongside it fails the build.
// This is why no Android module here applies a Kotlin plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}