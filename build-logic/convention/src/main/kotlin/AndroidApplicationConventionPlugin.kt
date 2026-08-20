import com.android.build.api.dsl.ApplicationExtension
import com.astracare.buildlogic.AndroidSdk
import com.astracare.buildlogic.library
import com.astracare.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `astracare.android.application` — baseline configuration for the `:app` module.
 *
 * Deliberately does NOT set applicationId, versionCode, versionName or buildTypes: those
 * are genuinely app-specific and belong in app/build.gradle.kts. Only the values that were
 * duplicated across modules move here.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("astracare.detekt")

        extensions.configure<ApplicationExtension> {
            compileSdk = AndroidSdk.COMPILE_SDK

            defaultConfig {
                minSdk = AndroidSdk.MIN_SDK
                targetSdk = AndroidSdk.TARGET_SDK
                testInstrumentationRunner = AndroidSdk.TEST_RUNNER
            }

            compileOptions {
                sourceCompatibility = AndroidSdk.JAVA_VERSION
                targetCompatibility = AndroidSdk.JAVA_VERSION
            }
        }

        dependencies {
            add("implementation", libs.library("androidx-core-ktx"))
            add("implementation", libs.library("coroutines-android"))

            add("testImplementation", libs.library("junit"))
            add("testImplementation", libs.library("coroutines-test"))
            add("testImplementation", libs.library("turbine"))
            add("testImplementation", libs.library("mockk"))

            add("androidTestImplementation", libs.library("androidx-junit"))
            add("androidTestImplementation", libs.library("androidx-espresso-core"))
        }
    }
}
