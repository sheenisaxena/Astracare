plugins {
    id("astracare.android.library")
    // Applies KSP as well as Hilt. Needed here for the first time: @HiltWorker is processed
    // by androidx.hilt's compiler, which registers against this same KSP instance.
    id("astracare.android.hilt")
}

android {
    namespace = "com.astracare.core.sync"
}

dependencies {
    // The sync algorithm lives in :core:domain as PushPendingRecordsUseCase; this module only
    // schedules it and adapts WorkManager's Result. It therefore needs the domain but NOT
    // :core:data — the repository and remote source arrive through their interfaces.
    implementation(project(":core:domain"))
    implementation(project(":core:model"))

    implementation(libs.work.runtime.ktx)

    // @HiltWorker + the generated factory entry. Two artifacts, same version line: hilt-work
    // carries the annotation, androidx-hilt-compiler generates the AssistedFactory. Declaring
    // only the first compiles and then fails at runtime with "Could not instantiate worker",
    // which reads like a WorkManager problem rather than a missing processor.
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.work.testing)
}
