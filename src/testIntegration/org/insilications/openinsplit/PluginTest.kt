package org.insilications.openinsplit

import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.path.GlobalPaths
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import com.intellij.ide.starter.utils.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes

class PluginTest {
    /**
     * Custom GlobalPaths implementation that points to the project's build directory.
     * This ensures all test artifacts are stored within the project structure.
     */
    class TemplatePaths : GlobalPaths(Git.getRepoRoot().resolve("build"))

    init {
        di = DI {
            extend(di)
            bindSingleton<GlobalPaths>(overrides = true) { TemplatePaths() }
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun publishArtifact(source: Path, artifactPath: String, artifactName: String) {
                    }

                    override fun reportTestFailure(
                        testName: String,
                        message: String,
                        details: String,
                        linkToLogs: String?
                    ) {
                        fail { "$testName fails: $message. \n$details" }
                    }

                    override fun ignoreTestFailure(testName: String, message: String, details: String?) {
                    }
                }
            }
        }
    }

    @Test
    fun simpleTestWithoutProject() {
        Starter.newContext(
            CurrentTestMethod.hyphenateWithClass(),
            TestCase(IdeProductProvider.IC, projectInfo = NoProject)
                .withVersion("2025.2.1")

        ).applyVMOptionsPatch {
            addSystemProperty("idea.system.path", "/king/.config/JetBrains/IC/system")
            addSystemProperty("idea.config.path", "/king/.config/JetBrains/IC/config")
            addSystemProperty("idea.plugins.path", "/king/.config/JetBrains/IC/config/plugins")
            addSystemProperty("idea.log.path", "/king/.config/JetBrains/IC/system/log")
            addLine("-Xms4096m")
            withXmx(8096)
            addSystemProperty("-Dawt.useSystemAAFontSettings", "lcd_hbgr")
            addSystemProperty("-Dswing.aatext", true)
            addSystemProperty("ide.experimental.ui", true)
//            addSystemProperty("ide.browser.jcef.enabled", true)
            addSystemProperty("jdk.gtk.verbose", true)
            addSystemProperty("jdk.gtk.version", 3)
            addSystemProperty("idea.is.internal", false)
            addSystemProperty("snapshots.path", "/king/stuff/snapshots")
            addLine("-XX:+UnlockDiagnosticVMOptions")
            addLine("-XX:+DebugNonSafepoints")
            // Required JVM arguments for module access
            addLine("--add-opens java.base/java.lang=ALL-UNNAMED")
            addLine("--add-opens java.desktop/javax.swing=ALL-UNNAMED")

            // Core IDE configuration
            addSystemProperty("idea.trust.all.projects", true) // Trust all projects automatically
            addSystemProperty("jb.consents.confirmation.enabled", false) // Disable consent dialogs
            addSystemProperty("jb.privacy.policy.text", "<!--999.999-->") // Skip privacy policy
            addSystemProperty("ide.show.tips.on.startup.default.value", false) // No tips on startup

            // Test framework configuration
            addSystemProperty("junit.jupiter.extensions.autodetection.enabled", true)
            addSystemProperty("shared.indexes.download.auto.consent", true)

            // UI testing specific
            addSystemProperty("expose.ui.hierarchy.url", true) // Enable UI hierarchy inspection
            addSystemProperty("ide.experimental.ui", true) // Use new UI for testing

            // ensure it does not open any project on startup
            addSystemProperty("ide.open.project.at.startup", false)
            addSystemProperty("idea.diagnostic.opentelemetry.metrics.file", "")
            addSystemProperty("idea.diagnostic.opentelemetry.meters.file.json", "")
            addSystemProperty("idea.diagnostic.opentelemetry.otlp", false)
        }.enableAsyncProfiler()
            .suppressStatisticsReport()
            .withKotlinPluginK2()
            .executeDuringIndexing(false).apply {
                val pathToPlugin = System.getProperty("path.to.build.plugin")
                println("Path to plugin: $pathToPlugin")
                PluginConfigurator(this).installPluginFromDir(Path(pathToPlugin))
//            withBuildTool<GradleBuildTool>()
            }
            .runIdeWithDriver(configure = {
                addVMOptionsPatch {
                    clearSystemProperty("ide.performance.screenshot")
                    addSystemProperty(
                        "idea.diagnostic.opentelemetry.otlp",
                        false
                    )
                    addSystemProperty("idea.diagnostic.opentelemetry.metrics.file", "")
                    addSystemProperty("idea.diagnostic.opentelemetry.meters.file.json", "")
                }
            }).useDriverAndCloseIde {
                waitForIndicators(1.minutes)
            }
    }
}
