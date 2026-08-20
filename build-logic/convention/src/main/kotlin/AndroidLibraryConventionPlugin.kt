import com.android.build.api.dsl.LibraryExtension
import com.astracare.buildlogic.AndroidSdk
import com.astracare.buildlogic.library
import com.astracare.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `astracare.android.library` — baseline configuration for every Android library module.
 *
 * Note there is no Kotlin plugin applied here: AGP 9 has built-in Kotlin support, and
 * applying `org.jetbrains.kotlin.android` on top of it breaks the build. Kotlin's jvmTarget
 * is derived from `compileOptions` below, which is why no `kotlin { }` block is needed.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("astracare.detekt")

        extensions.configure<LibraryExtension> {
            compileSdk = AndroidSdk.COMPILE_SDK

            defaultConfig {
                minSdk = AndroidSdk.MIN_SDK
                testInstrumentationRunner = AndroidSdk.TEST_RUNNER
            }

            compileOptions {
                sourceCompatibility = AndroidSdk.JAVA_VERSION
                targetCompatibility = AndroidSdk.JAVA_VERSION
            }
        }

        dependencies {
            add("implementation", libs.library("androidx-core-ktx"))
            add("implementation", libs.library("coroutines-core"))

            add("testImplementation", libs.library("junit"))
            add("testImplementation", libs.library("coroutines-test"))
            add("testImplementation", libs.library("turbine"))
            add("testImplementation", libs.library("mockk"))

            add("androidTestImplementation", libs.library("androidx-junit"))
            add("androidTestImplementation", libs.library("androidx-espresso-core"))
        }
    }
}
