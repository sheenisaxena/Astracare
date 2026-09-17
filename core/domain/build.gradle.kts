plugins {
    id("astracare.jvm.library")
    id("astracare.jvm.hilt")
}

dependencies {
    // api, not implementation: repository signatures and use-case return types expose these
    // to callers, so consumers need them on their own compile classpath.
    //
    // Explicit project paths rather than the projects.* accessors — see DECISION_LOG 3.6.
    api(project(":core:model"))
    api(project(":core:common"))

    // api, not implementation: BeneficiaryRepository returns Flow<PagingData<Beneficiary>>,
    // so every consumer needs the type. paging-common publishes a plain JVM jar with no
    // Android dependency, which is what lets this stay a Kotlin/JVM module — the compiler
    // still rejects `import android.*` here. See DECISION_LOG 7.1.
    api(libs.paging.common)
}
