import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "com.astracare.buildlogic"

// Convention plugins run inside the Gradle daemon (toolchain 21 per
// gradle/gradle-daemon-jvm.properties), so they target 17 for headroom. This is
// unrelated to the app's own Java 11 target.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // compileOnly, not implementation: these plugins are already on the consuming build's
    // classpath at execution time. Using implementation would put two copies of AGP on the
    // classpath and fail with duplicate-class errors.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "astracare.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "astracare.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "astracare.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "astracare.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "astracare.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
