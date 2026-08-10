import com.astracare.buildlogic.libs
import com.astracare.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `astracare.android.hilt` — applies KSP + Hilt and wires the annotation processor.
 *
 * KSP is applied before Hilt because Hilt's processor runs through it.
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")

        dependencies {
            add("implementation", libs.library("hilt-android"))
            add("ksp", libs.library("hilt-compiler"))

            add("androidTestImplementation", libs.library("hilt-android-testing"))
            add("kspAndroidTest", libs.library("hilt-compiler"))
        }
    }
}
