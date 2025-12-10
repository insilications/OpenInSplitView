@file:Suppress("NOTHING_TO_INLINE")

package org.insilications.openinsplit

import com.intellij.driver.client.Driver
import com.intellij.driver.client.service
import com.intellij.driver.model.LockSemantics
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.ActionManager
import com.intellij.driver.sdk.AnAction
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.config.ConfigurationStorage
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.BackgroundRun
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.driver.execute
import com.intellij.ide.starter.ide.IdeDistributionFactory
import com.intellij.ide.starter.ide.IdeDownloader
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.ide.installer.ExistingIdeInstaller
import com.intellij.ide.starter.ide.installer.IdeInstallerFactory
import com.intellij.ide.starter.ide.installer.IdeInstallerFile
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.IDEStartResult
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.IDEHandle
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import com.intellij.tools.ide.util.common.logError
import com.intellij.tools.ide.util.common.logOutput
import kotlinx.coroutines.runBlocking
import org.insilications.openinsplit.custom.MyLinuxIdeDistribution
import org.insilications.openinsplit.custom.waitForIt
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PluginTest {
    init {
        di = DI {
            extend(di, true)
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun publishArtifact(source: Path, artifactPath: String, artifactName: String) {
                    }

                    override fun reportTestFailure(
                        testName: String, message: String, details: String, linkToLogs: String?
                    ) {
                        fail { "$testName fails: $message\n$details" }
                    }

                    override fun ignoreTestFailure(testName: String, message: String, details: String?) {
                    }
                }
            }
            bindSingleton<IdeInstallerFactory>(overrides = true) { createInstallerFactory() }
            bindSingleton<IdeDistributionFactory>(overrides = true) { createDistributionFactory() }
            bindSingleton<IdeDownloader>(overrides = true) {
                @Suppress("PathAnnotationInspection") IdeNotDownloader(Paths.get(platformPath))
            }
            val defaults: MutableMap<String, String> = ConfigurationStorage.instance().defaults.toMutableMap().apply {
                put("MONITORING_DUMPS_INTERVAL_SECONDS", "6000")
            }
            bindSingleton<ConfigurationStorage>(overrides = true) {
                ConfigurationStorage(this, defaults)
            }
            ConfigurationStorage.instance().put("MONITORING_DUMPS_INTERVAL_SECONDS", "6000")
        }
    }

    private val platformPath: String = System.getProperty("path.to.platform")

    private fun createDistributionFactory(): IdeDistributionFactory = object : IdeDistributionFactory {
        override fun installIDE(unpackDir: File, executableFileName: String): InstalledIde {
            return MyLinuxIdeDistribution().installIde(unpackDir.toPath(), executableFileName)
        }

    }

    private fun createInstallerFactory(): IdeInstallerFactory = object : IdeInstallerFactory() {
        @Suppress("PathAnnotationInspection")
        override fun createInstaller(ideInfo: IdeInfo, downloader: IdeDownloader): ExistingIdeInstaller = ExistingIdeInstaller(Paths.get(platformPath))
    }

    // This helpers are required to run locally installed IDE instead of downloading one
    class IdeNotDownloader(private val installer: Path) : IdeDownloader {
        override fun downloadIdeInstaller(ideInfo: IdeInfo, installerDirectory: Path): IdeInstallerFile {
            return IdeInstallerFile(installer, "locally-installed-ide")
        }
    }

    companion object {
        private const val EDITOR_COMPONENT_IMPL: String = "EditorComponentImpl"
        private const val EDITOR_FOR_GOTODECLARATION: String = "Editor for GotoDeclarationOrUsageHandler2SplitTest.kt"
        private const val SYMBOLS_INFORMATION_ACTION: String = "SymbolsInformationAction"
    }

    @Test
    fun simpleTestWithoutProject() {
        Starter.newContext(
            CurrentTestMethod.hyphenateWithClass(), TestCase(IdeProductProvider.IC, projectInfo = NoProject)
            //            CurrentTestMethod.hyphenateWithClass(), TestCase(IdeProductProvider.IC, LocalProjectInfo(Path("/aot/stuff/dev/OpenInSplitTab/")))
        ).applyVMOptionsPatch {
            addSystemProperty("idea.system.path", "/king/.config/JetBrains/IC/system")
            addSystemProperty("idea.config.path", "/king/.config/JetBrains/IC/config")
            addSystemProperty("idea.plugins.path", "/king/.config/JetBrains/IC/plugins")
            addSystemProperty("idea.plugins.path", "/king/.config/JetBrains/IC/plugins")
            addSystemProperty("idea.log.path", "/king/.config/JetBrains/IC/log")
            addLine("-Xms4096m")
            withXmx(8096)
            addSystemProperty("-Dawt.useSystemAAFontSettings", "lcd_hbgr")
            addSystemProperty("-Dswing.aatext", true)
            addSystemProperty("ide.experimental.ui", true)
            addSystemProperty("jdk.gtk.verbose", true)
            addSystemProperty("jdk.gtk.version", 3)
            addSystemProperty("idea.is.internal", false)
            addSystemProperty("snapshots.path", "/king/stuff/snapshots")
            addSystemProperty("performance.watcher.unresponsive.interval.ms", "3600000")
            addSystemProperty("performance.watcher.sampling.interval.ms", "3600000")
            addSystemProperty("idea.log.debug.categories", "org.insilications.openinsplit")

            addLine("-XX:+UnlockDiagnosticVMOptions")
            addLine("-XX:+DebugNonSafepoints")
            // Required JVM arguments for module access
            addLine("--add-opens java.base/java.lang=ALL-UNNAMED")
            addLine("--add-opens java.desktop/javax.swing=ALL-UNNAMED")

            // Core IDE configuration
            // Trust all projects automatically
            addSystemProperty("idea.trust.all.projects", true)
            // Disable consent dialogs
            addSystemProperty("jb.consents.confirmation.enabled", false)
            // Skip privacy policy
            addSystemProperty("jb.privacy.policy.text", "<!--999.999-->")
            // No tips on startup
            addSystemProperty("ide.show.tips.on.startup.default.value", false)

            // Test framework configuration
            addSystemProperty("junit.jupiter.extensions.autodetection.enabled", true)
            addSystemProperty("shared.indexes.download.auto.consent", true)

            // Use new UI for testing
            addSystemProperty("ide.experimental.ui", true)

        }.enableAsyncProfiler().suppressStatisticsReport().withKotlinPluginK2().apply {
            val pathToPlugin: String = System.getProperty("path.to.build.plugin")
//            logOutput("Path to plugin: $pathToPlugin")
//            val pathToPlatform: String = System.getProperty("path.to.platform")
//            logOutput("pathToPlatform: $pathToPlatform")
            PluginConfigurator(this).installPluginFromDir(Path(pathToPlugin))
//            ConfigurationStorage.Companion.instance().put("MONITORING_DUMPS_INTERVAL_SECONDS", "6000")
        }.runIdeWithDriver(configure = {
            addVMOptionsPatch {
                clearSystemProperty("expose.ui.hierarchy.url")
            }
        }).apply {
            try {
                driver.withContext {
//                    waitForIndicators(3.minutes, false)
                    ideFrame {
                        execute(CommandChain().waitForSmartMode())
                        val firstEditor: JEditorUiComponent = xx(JEditorUiComponent::class.java) {
                            and(
                                byClass(EDITOR_COMPONENT_IMPL), byAccessibleName(EDITOR_FOR_GOTODECLARATION)
                            )
                        }.list().first()
//                        val firstEditorComponent: Component = firstEditor.component
//                        val robot: Robot = robotProvider.defaultRobot

                        val actionManager: ActionManager = service<ActionManager>(RdTarget.DEFAULT)
                        val action: AnAction = withContext(OnDispatcher.EDT) {
                            actionManager.getAction(SYMBOLS_INFORMATION_ACTION)
                        } ?: return@ideFrame

                        firstEditor.goToPosition(24, 18)
                        withContext(OnDispatcher.EDT, semantics = LockSemantics.READ_ACTION) {
                            actionManager.tryToExecute(action, null, null, null, true)
                        }
                    }
//                    Thread.sleep(5.seconds.inWholeMilliseconds)
                }
            } finally {
                myCloseIdeAndWait(this, driver, 1.minutes)
            }
            return
        }
    }

    private inline fun myCloseIdeAndWait(backgroundRun: BackgroundRun, driver: Driver, closeIdeTimeout: Duration): IDEStartResult {
        val process: IDEHandle = backgroundRun.process
        try {
            if (driver.isConnected) {
                driver.exitApplication()
                waitForIt("Driver is not connected", closeIdeTimeout, 1.seconds) { !driver.isConnected }
            } else {
                error("Driver is not connected, so it can't exit IDE")
            }
        } catch (t: Throwable) {
            logError("Error on exit application via Driver", t)
            logOutput("Performing force kill")
            process.kill()
        } finally {
            try {
                if (driver.isConnected) {
                    driver.close()
                }
                waitForIt("Process is closed", closeIdeTimeout, 1.seconds) { !process.isAlive }
            } catch (e: Throwable) {
                logError("Error waiting IDE is closed: ${e.message}: ${e.stackTraceToString()}", e)
                logOutput("Performing force kill")
                process.kill()
                throw IllegalStateException("Process didn't die after waiting for Driver to close IDE", e)
            }
        }

        return runBlocking {
            backgroundRun.startResult.await()
        }
    }
}
