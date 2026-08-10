import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.astracare.buildlogic.libs
import com.astracare.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `astracare.android.compose` — enables Compose and adds the shared Compose dependency set.
 *
 * Apply alongside `astracare.android.library` or `astracare.android.application`; this
 * plugin configures whichever of the two Android extensions is present rather than using
 * `CommonExtension`, whose generic signature has changed between AGP majors.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // Compose compiler plugin. Separate from AGP's built-in Kotlin support.
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.findByType(LibraryExtension::class.java)?.apply {
            buildFeatures { compose = true }
        }
        extensions.findByType(ApplicationExtension::class.java)?.apply {
            buildFeatures { compose = true }
        }

        dependencies {
            val bom = libs.library("androidx-compose-bom")
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))

            add("implementation", libs.library("androidx-compose-ui"))
            add("implementation", libs.library("androidx-compose-ui-graphics"))
            add("implementation", libs.library("androidx-compose-ui-tooling-preview"))
            add("implementation", libs.library("androidx-compose-material3"))
            add("implementation", libs.library("androidx-lifecycle-viewmodel-compose"))

            add("androidTestImplementation", libs.library("androidx-compose-ui-test-junit4"))
            add("debugImplementation", libs.library("androidx-compose-ui-tooling"))
            add("debugImplementation", libs.library("androidx-compose-ui-test-manifest"))
        }
    }
}
