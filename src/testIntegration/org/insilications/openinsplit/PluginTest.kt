package org.insilications.openinsplit


import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.table
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.config.ConfigurationStorage
import com.intellij.ide.starter.config.starterConfigurationStorageDefaults
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
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.fileLogger
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
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PluginTest {
    init {
        di = DI {
            extend(di)
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
            bindSingleton<IdeInstallerFactory>(overrides = true) { createInstallerFactory() }
            bindSingleton<IdeDistributionFactory>(overrides = true) { createDistributionFactory() }
            bindSingleton<IdeDownloader>(overrides = true) {
                @Suppress("PathAnnotationInspection")
                IdeNotDownloader(Paths.get(platformPath))
            }
            bindSingleton<ConfigurationStorage>(overrides = true) {
                ConfigurationStorage(
                    this,
                    starterConfigurationStorageDefaults + ("MONITORING_DUMPS_INTERVAL_SECONDS" to "6000")
                )
            }
            ConfigurationStorage.instance().put("MONITORING_DUMPS_INTERVAL_SECONDS", "6000")
        }
    }


    private val platformPath: String = System.getProperty("path.to.platform")

    private fun createDistributionFactory() = object : IdeDistributionFactory {
        override fun installIDE(unpackDir: File, executableFileName: String): InstalledIde {
            return MyLinuxIdeDistribution().installIde(unpackDir.toPath(), executableFileName)
        }

    }

    private fun createInstallerFactory() = object : IdeInstallerFactory() {
        @Suppress("PathAnnotationInspection")
        override fun createInstaller(ideInfo: IdeInfo, downloader: IdeDownloader) =
            ExistingIdeInstaller(Paths.get(platformPath))
    }

    // This helpers are required to run locally installed IDE instead of downloading one
    class IdeNotDownloader(private val installer: Path) : IdeDownloader {
        override fun downloadIdeInstaller(ideInfo: IdeInfo, installerDirectory: Path): IdeInstallerFile {
            return IdeInstallerFile(installer, "locally-installed-ide")
        }
    }

    companion object {
        private val LOG: Logger get() = fileLogger()
        private const val EDITOR_COMPONENT_IMPL: String = "EditorComponentImpl"
        private const val EDITOR_FOR_GOTODECLARATION: String = "Editor for GotoDeclarationOrUsageHandler2Split.kt"
        private const val GO_TO_DECLARATION_ACTION: String = "GotoDeclarationActionSplit"
        private const val DIV_CLASS_SHOW_USAGES_TABLE: String = "//div[@class='ShowUsagesTable']"
        private const val MESSAGE_SHOW_USAGES_TABLE_POPULATED: String = "Usages table is populated"
    }

    @Test
    fun simpleTestWithoutProject() {
        Starter.newContext(
            CurrentTestMethod.hyphenateWithClass(),
            TestCase(IdeProductProvider.IC, projectInfo = NoProject)
//                .withVersion("2025.2.1")
        ).applyVMOptionsPatch {
            withEnv("MONITORING_DUMPS_INTERVAL_SECONDS", "6000")
            addSystemProperty("idea.system.path", "/king/.config/JetBrains/IC/system")
            addSystemProperty("idea.config.path", "/king/.config/JetBrains/IC/config")
            addSystemProperty("idea.plugins.path", "/king/.config/JetBrains/IC/plugins")
//            addSystemProperty("idea.log.path", "/king/.config/JetBrains/IC/system/log")
            addLine("-Xms4096m")
            withXmx(8096)
            addSystemProperty("-Dawt.useSystemAAFontSettings", "lcd_hbgr")
            addSystemProperty("-Dswing.aatext", true)
            addSystemProperty("ide.experimental.ui", true)
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

            // UI testing specific
            // Enable UI hierarchy inspection
            addSystemProperty("expose.ui.hierarchy.url", true)
            // Use new UI for testing
            addSystemProperty("ide.experimental.ui", true)

            // Ensure it does not open any project on startup
            addSystemProperty("ide.open.project.at.startup", false)
        }.enableAsyncProfiler()
            .suppressStatisticsReport()
            .withKotlinPluginK2()
            .executeDuringIndexing(false).apply {
                val pathToPlugin: String = System.getProperty("path.to.build.plugin")
                LOG.info("Path to plugin: $pathToPlugin")
                PluginConfigurator(this).installPluginFromDir(Path(pathToPlugin))
            }
            .runIdeWithDriver().apply {
                try {
                    driver.withContext {
                        waitForIndicators(3.minutes)
                        execute(CommandChain().waitForSmartMode())
                        ideFrame {
                            val firstEditor: JEditorUiComponent = xx(JEditorUiComponent::class.java) {
                                and(
                                    byClass(EDITOR_COMPONENT_IMPL),
                                    byAccessibleName(EDITOR_FOR_GOTODECLARATION)
                                )
                            }.list()
                                .first()
                            var showUsagesTableRowCount = 0

                            firstEditor.apply {
                                goToPosition(68, 22)
                            }
                            invokeAction(GO_TO_DECLARATION_ACTION)
                            table(DIV_CLASS_SHOW_USAGES_TABLE).apply {
                                waitForIt(MESSAGE_SHOW_USAGES_TABLE_POPULATED, 1.minutes, 200.milliseconds) {
                                    this.rowCount() > 0
                                }
                                showUsagesTableRowCount = this.rowCount() - 1
                                LOG.info("Row count: ${this.rowCount()}")
                                clickCell(showUsagesTableRowCount, 0)
                                showUsagesTableRowCount -= 1
                            }

                            firstEditor.apply {
                                goToPosition(68, 22)
                            }
                            invokeAction(GO_TO_DECLARATION_ACTION)

                            table(DIV_CLASS_SHOW_USAGES_TABLE).apply {
                                waitForIt(MESSAGE_SHOW_USAGES_TABLE_POPULATED, 1.minutes, 200.milliseconds) {
                                    this.rowCount() > 0
                                }
                                clickCell(showUsagesTableRowCount, 0)
                                showUsagesTableRowCount -= 1
                            }

                            firstEditor.apply {
                                goToPosition(68, 22)
                            }
                            invokeAction(GO_TO_DECLARATION_ACTION)

                            table(DIV_CLASS_SHOW_USAGES_TABLE).apply {
                                waitForIt(MESSAGE_SHOW_USAGES_TABLE_POPULATED, 1.minutes, 200.milliseconds) {
                                    this.rowCount() > 0
                                }
                                clickCell(showUsagesTableRowCount, 0)
                                showUsagesTableRowCount -= 1
                            }

                            firstEditor.apply {
                                goToPosition(68, 22)
                            }
                            invokeAction(GO_TO_DECLARATION_ACTION)

                            table(DIV_CLASS_SHOW_USAGES_TABLE).apply {
                                waitForIt(MESSAGE_SHOW_USAGES_TABLE_POPULATED, 1.minutes, 200.milliseconds) {
                                    this.rowCount() > 0
                                }
                                keyboard {
                                    key(KeyEvent.VK_DOWN)
                                    key(KeyEvent.VK_ENTER)
                                }
                            }
                        }
                        Thread.sleep(30.minutes.inWholeMilliseconds)
                        // event=wall,interval=100000ns,jstackdepth=36384,jfrsync=profile
//                        val commands =
//                            CommandChain().startProfile("indexing", "event=wall,interval=100000ns,jstackdepth=36384,jfrsync=profile").waitForSmartMode()
//                                .stopProfile()
//                        execute(commands)

                    }
                } finally {
                    closeIdeAndWait(this, driver, 1.minutes)
                }
                return
            }
    }

    private fun closeIdeAndWait(backgroundRun: BackgroundRun, driver: Driver, closeIdeTimeout: Duration): IDEStartResult {
        val process: IDEHandle = backgroundRun.process
        try {
            if (driver.isConnected) {
                driver.exitApplication()
                waitFor("Driver is not connected", closeIdeTimeout, 3.seconds) { !driver.isConnected }
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
                waitFor("Process is closed", closeIdeTimeout, 3.seconds) { !process.isAlive }
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
