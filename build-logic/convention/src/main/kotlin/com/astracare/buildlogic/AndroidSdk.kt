package com.astracare.buildlogic

import org.gradle.api.JavaVersion

/**
 * Single source of truth for SDK and Java levels across every module.
 *
 * This object is the entire point of the build-logic build: bumping [COMPILE_SDK] is one
 * edit here instead of seven edits across module build files, one of which you forget.
 */
object AndroidSdk {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 24

    /**
     * Java 11 for app/library code. Deliberately lower than the Gradle daemon's toolchain
     * (21) and build-logic's own target (17) — those compile the build, this compiles the app.
     */
    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_11

    const val TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
}
