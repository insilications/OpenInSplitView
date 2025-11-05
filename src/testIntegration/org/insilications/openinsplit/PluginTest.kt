package org.insilications.openinsplit

import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.buildTool.GradleBuildTool
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes

class PluginTest {
    init {
        di = DI {
            extend(di)
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun reportTestFailure(
                        testName: String,
                        message: String,
                        details: String,
                        linkToLogs: String?
                    ) {
                        fail { "$testName fails: $message. \n$details" }
                    }
                }
            }
        }
    }

    //
//    -Xms256m
//    -Xmx8096m
//    -Dawt.useSystemAAFontSettings=lcd_hbgr
//    -Dswing.aatext=true
//    -XX:+UnlockDiagnosticVMOptions
//    -XX:+DebugNonSafepoints
    @Test
    fun simpleTestWithoutProject() {
        Starter.newContext(
            CurrentTestMethod.hyphenateWithClass(),
            TestCase(IdeProductProvider.IC, projectInfo = NoProject)
                .withVersion("2025.2.1")
        ).enableAsyncProfiler().executeDuringIndexing(false).applyVMOptionsPatch {
            addSystemProperty("idea.system.path", "/king/.config/JetBrains/IC/system")
            addSystemProperty("idea.config.path", "/king/.config/JetBrains/IC/config")
            addSystemProperty("idea.plugins.path", "/king/.config/JetBrains/IC/config/plugins")
            addSystemProperty("idea.log.path", "/king/.config/JetBrains/IC/system/log")
            withXmx(8096)
            addSystemProperty("gradle.user.home", "/king/.gradle")
            addSystemProperty("-Dawt.useSystemAAFontSettings", "lcd_hbgr")
            addSystemProperty("-Dswing.aatext", true)
            addSystemProperty("ide.experimental.ui", true)
            addSystemProperty("ide.browser.jcef.enabled", true)
            addSystemProperty("jdk.gtk.verbose", true)
            addSystemProperty("jdk.gtk.version", 3)
            addSystemProperty("idea.is.internal", false)

            // Required JVM arguments for module access
//            addSystemProperty("--add-opens", "java.base/java.lang=ALL-UNNAMED")
//            addSystemProperty("--add-opens", "java.desktop/javax.swing=ALL-UNNAMED")

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
        }.apply {
            val pathToPlugin = System.getProperty("path.to.build.plugin")
//            println("Path to plugin: $pathToPlugin")
            PluginConfigurator(this).installPluginFromPath(Path(pathToPlugin))
            withBuildTool<GradleBuildTool>()
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForIndicators(1.minutes)
        }
    }
}
