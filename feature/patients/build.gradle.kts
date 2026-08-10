plugins {
    id("astracare.android.library")
    id("astracare.android.compose")
    id("astracare.android.hilt")
}

android {
    namespace = "com.astracare.feature.patients"
}

dependencies {
    // Explicit project paths rather than the projects.* type-safe accessors, which rely on
    // a feature-preview flag whose stability varies by Gradle version.
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
}
