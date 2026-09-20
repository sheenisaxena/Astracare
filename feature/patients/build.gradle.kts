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
    // :core:domain exposes :core:model and :core:common via api(), so Outcome and
    // TimeProvider arrive transitively rather than being declared again here.
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))

    // hiltViewModel(). The artifact is named for navigation but carries no dependency on
    // Navigation Compose, which this project deliberately does not use yet.
    implementation(libs.androidx.hilt.navigation.compose)

    // collectAsLazyPagingItems + itemKey. paging-common arrives transitively through
    // :core:domain, which is where the PagingData type in the repository contract comes from.
    implementation(libs.paging.compose)

    // Day 21: ReportDrawnWhen, for time-to-full-display. This is an Activity API reaching into
    // a feature module, which is worth a second look before accepting — but the screen is the
    // only thing that knows when it has finished loading, and the alternative (hoisting a
    // "ready" flag up to :app so MainActivity can call reportFullyDrawn) would put a
    // performance concern into the navigation shell and couple it to this screen's load state.
    implementation(libs.androidx.activity.compose)
}
