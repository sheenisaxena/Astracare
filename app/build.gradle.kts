plugins {
    id("astracare.android.application")
    id("astracare.android.compose")
    id("astracare.android.hilt")
}

// Only genuinely app-specific configuration remains here. compileSdk, minSdk, targetSdk,
// Java level, the test runner, Compose setup, Hilt/KSP wiring and the shared test
// dependencies all come from the convention plugins in build-logic.
android {
    namespace = "com.astracare"

    defaultConfig {
        applicationId = "com.astracare"
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:domain"))
    // Required even though :app never references it directly: DataModule's @Binds for
    // BeneficiaryRepository must be on the runtime classpath, or Hilt fails to find a
    // binding when assembling SingletonComponent here.
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:patients"))
}
