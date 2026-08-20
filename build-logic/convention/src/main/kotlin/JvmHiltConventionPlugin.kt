import com.astracare.buildlogic.library
import com.astracare.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `astracare.jvm.hilt` — Hilt/Dagger for pure Kotlin-JVM modules.
 *
 * Separate from `astracare.android.hilt` because the Hilt **Gradle plugin**
 * (`com.google.dagger.hilt.android`) is Android-only: it rewrites bytecode for
 * `@AndroidEntryPoint`, which has no meaning here. A JVM module needs only KSP plus the
 * annotation processor.
 *
 * `hilt-core` rather than `hilt-android`: it carries the Dagger and Hilt annotations
 * (`@Module`, `@InstallIn`, `@Inject`, `@Provides`) with no Android dependency at all. That
 * matters — pulling `hilt-android` in here would put `android.*` on the classpath of modules
 * whose entire purpose is not having it, and quietly destroy the compiler-enforced boundary
 * described in DECISION_LOG 1.4.
 *
 * The processor is needed in each module so Dagger generates factories locally. Without it,
 * Dagger regenerates them in every downstream module that consumes these classes — which
 * still works, but duplicates work on every build.
 */
class JvmHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")

        dependencies {
            add("implementation", libs.library("hilt-core"))
            add("ksp", libs.library("hilt-compiler"))
        }
    }
}
