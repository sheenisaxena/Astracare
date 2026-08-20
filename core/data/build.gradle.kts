plugins {
    id("astracare.android.library")
    id("astracare.android.hilt")
}

android {
    namespace = "com.astracare.core.data"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
}
