import com.astracare.buildlogic.AndroidSdk
import com.astracare.buildlogic.libs
import com.astracare.buildlogic.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * `astracare.jvm.library` — for pure Kotlin/JVM modules (:core:model, :core:common,
 * :core:domain).
 *
 * These modules have no Android dependency at all, which is the point: the compiler
 * physically cannot let an `android.*` import into the domain layer. That turns "my domain
 * is platform-independent" from a claim into something enforced by the build — and it is
 * what makes a future KMP extraction possible rather than a rewrite.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("java-library")
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("astracare.detekt")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = AndroidSdk.JAVA_VERSION
            targetCompatibility = AndroidSdk.JAVA_VERSION
        }

        // The Kotlin JVM plugin does not infer jvmTarget from the java extension; leaving
        // them mismatched produces an "inconsistent JVM target" failure.
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
            }
        }

        dependencies {
            add("implementation", libs.library("coroutines-core"))

            add("testImplementation", libs.library("junit"))
            add("testImplementation", libs.library("coroutines-test"))
            add("testImplementation", libs.library("turbine"))
            add("testImplementation", libs.library("mockk"))
        }
    }
}
