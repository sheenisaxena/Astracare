import com.android.build.api.dsl.TestExtension
import com.astracare.buildlogic.AndroidSdk
import com.astracare.buildlogic.library
import com.astracare.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `astracare.android.test` — baseline configuration for a `com.android.test` module.
 *
 * A third module *kind*, alongside application and library, and the least familiar of the
 * three. A `com.android.test` module produces an APK containing only instrumentation, which is
 * installed next to a separate target APK and drives it from outside the process. That
 * separation is what makes a macrobenchmark meaningful: measuring an app's startup from inside
 * that app would mean the measuring code is part of what is being measured.
 *
 * ## Why a convention plugin for exactly one module
 *
 * The honest answer is that one module does not justify indirection, and the rule this project
 * has followed since Day 3 does: SDK and Java levels are defined once, in [AndroidSdk]. The
 * alternative was a fourth copy of `compileSdk = 37` living in a module nobody opens, which is
 * precisely the copy that gets missed on an upgrade — and the benchmark module is the one place
 * where being a version behind produces a *number* rather than an error.
 *
 * ## minSdk is higher here than in the app, deliberately
 *
 * The app supports API 24. Macrobenchmark needs the target APK to be `profileable`, which is
 * API 29, and needs `dumpsys` output this library only parses on 29+. Setting [BENCHMARK_MIN_SDK]
 * here rather than lowering the app's floor keeps the constraint where it belongs: on the
 * measuring instrument, not on the thing being measured.
 */
class AndroidTestModuleConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.test")
        pluginManager.apply("astracare.detekt")

        extensions.configure<TestExtension> {
            compileSdk = AndroidSdk.COMPILE_SDK

            defaultConfig {
                minSdk = BENCHMARK_MIN_SDK
                targetSdk = AndroidSdk.TARGET_SDK
                testInstrumentationRunner = AndroidSdk.TEST_RUNNER
            }

            compileOptions {
                sourceCompatibility = AndroidSdk.JAVA_VERSION
                targetCompatibility = AndroidSdk.JAVA_VERSION
            }
        }

        dependencies {
            add("implementation", libs.library("junit"))
            add("implementation", libs.library("androidx-junit"))
        }
    }

    private companion object {
        /** `profileable android:shell="true"` is API 29+, and so is a usable macrobenchmark. */
        const val BENCHMARK_MIN_SDK = 29
    }
}
