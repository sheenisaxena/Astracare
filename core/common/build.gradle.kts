plugins {
    id("astracare.jvm.library")
    id("astracare.jvm.hilt")
}

dependencies {
    // api: TimeProvider returns Timestamp, so callers need the type on their own classpath.
    api(project(":core:model"))
}
