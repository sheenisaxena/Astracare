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
}
