import com.astracare.buildlogic.library
import com.astracare.buildlogic.libs
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * `astracare.detekt` — static analysis and formatting for every module.
 *
 * Applied automatically by the three base convention plugins, so no module opts in by hand.
 * That is the whole point: the rule set cannot drift between modules, and a new module is
 * covered the moment it is created.
 *
 * detekt-formatting wraps ktlint's rules, so formatting and static analysis are one tool at
 * one version rather than two plugins to keep in step.
 */
class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("io.gitlab.arturbosch.detekt")

        extensions.configure<DetektExtension> {
            // Start from detekt's defaults and record only our differences, so upgrades pick
            // up new rules instead of silently pinning the old set.
            buildUponDefaultConfig = true
            allRules = false
            parallel = true
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            // Report paths relative to the repo root, so CI output is readable and stable
            // across machines.
            basePath = rootProject.projectDir.absolutePath
        }

        dependencies {
            add("detektPlugins", libs.library("detekt-formatting"))
        }

        tasks.withType<Detekt>().configureEach {
            // Must match the app's Java target; detekt parses against this level.
            jvmTarget = "11"
            reports {
                html.required.set(true)
                xml.required.set(false)
                txt.required.set(false)
                // SARIF is GitHub's code-scanning format. Kept available so CI can surface
                // findings as inline annotations on a pull request rather than log output.
                sarif.required.set(true)
                md.required.set(false)
            }
        }
    }
}
