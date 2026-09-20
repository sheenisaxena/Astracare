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

    // One set of fakes, two runners. `src/sharedTest` is not an AGP convention — it is an
    // ordinary directory added to both test source sets, which is the standard way to stop
    // the doubles being written twice and drifting apart. The Robolectric run uses them
    // today; a device run of the same tests uses the same objects, unchanged.
    sourceSets {
        getByName("test") { kotlin.srcDir("src/sharedTest/kotlin") }
        getByName("androidTest") { kotlin.srcDir("src/sharedTest/kotlin") }
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged manifest and the real resources. Without this the
            // unit-test classpath carries neither, and a Compose test fails on inflating the
            // theme rather than on anything it meant to assert.
            isIncludeAndroidResources = true
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
    // Same reason as :core:data above — :app never references it directly, but SyncModule's
    // binding for SyncScheduler must be on the runtime classpath or Hilt cannot satisfy
    // OfflineFirstBeneficiaryRepository when it assembles SingletonComponent here.
    implementation(project(":core:sync"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:patients"))

    // Configuration.Provider and HiltWorkerFactory, used by AstraCareApplication. Declared
    // rather than leaned on transitively: :core:sync exposes them as implementation details,
    // and a module that names a type should depend on it.
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    // Day 20. The convention plugins put the Compose test artifacts and Hilt's test support
    // on `androidTestImplementation` only, because until now every UI test was instrumented.
    // These four lines are what move that capability to the JVM side, and they are declared
    // here rather than in the convention plugins deliberately: :app is the only module with
    // an Activity to launch, so a library module inheriting Robolectric would be paying a
    // large dependency for nothing.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.hilt.android.testing)
    // Generates the test application component. Without it @HiltAndroidTest compiles and
    // then fails at runtime saying the test was not annotated — one of Hilt's less helpful
    // messages, because the annotation is right there.
    kspTest(libs.hilt.compiler)
}
