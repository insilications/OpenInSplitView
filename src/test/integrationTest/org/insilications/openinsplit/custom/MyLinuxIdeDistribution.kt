package org.insilications.openinsplit.custom


import com.intellij.ide.starter.ide.IDEStartConfig
import com.intellij.ide.starter.ide.IdeDistribution
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.models.VMOptions
import com.intellij.ide.starter.models.VMOptionsDiff
import com.intellij.ide.starter.utils.FileSystem.listDirectoryEntriesQuietly
import com.intellij.ide.starter.utils.JvmUtils
import com.intellij.openapi.util.SystemInfo
import com.intellij.tools.ide.util.common.logOutput
import com.intellij.util.system.OS
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable

//const val DEFAULT_DISPLAY_ID = "88"

class MyLinuxIdeDistribution : IdeDistribution() {
    companion object {
        //        private const val DEFAULT_DISPLAY_RESOLUTION = "1920x1080"
//        private const val xvfbRunTool: String = "/usr/bin/xvfb-run"
        private const val DISPLAY_ENV: String = "DISPLAY=:88"

//        fun linuxCommandLine(xvfbRunLog: Path, vmOptions: VMOptions): List<String> {
//            return listOf(
//                xvfbRunTool,
//                "--error-file=" + xvfbRunLog.toAbsolutePath().toString(),
//                "--server-args=-ac -screen 0 ${DEFAULT_DISPLAY_RESOLUTION}x24",
//                "--auto-servernum",
//                "--server-num=$DEFAULT_DISPLAY_ID"
//            )
//        }

//        fun createXvfbRunLog(logsDir: Path): Path {
//            val logTxt = logsDir.resolve("xvfb-log.txt")
//            logTxt.deleteIfExists()
//
//            return Files.createFile(logTxt)
//        }
    }

    override fun installIde(unpackDir: Path, executableFileName: String): InstalledIde {
        require(SystemInfo.isLinux) { "Can only run on Linux, docker is possible, please PR" }

        val appHome: Path = (unpackDir.listDirectoryEntriesQuietly()?.singleOrNull { it.isDirectory() } ?: unpackDir).toAbsolutePath()
        val (productCode: String, build: String) = readProductCodeAndBuildNumberFromBuildTxt(appHome.resolve("build.txt"))

        val executablePath: Path =
            listOf(appHome / "bin" / executableFileName, appHome / "bin" / "$executableFileName.sh").firstOrNull { it.exists() && it.isExecutable() }
                ?: error("Neither ${appHome / "bin" / executableFileName} nor ${appHome / "bin" / "$executableFileName.sh"} is executable or exists")

        return object : InstalledIde {
            override val bundledPluginsDir: Path = appHome.resolve("plugins")

            private val vmOptionsFinal: VMOptions = VMOptions(
                ide = this, data = emptyList(), env = emptyMap()
            )

            override val vmOptions: VMOptions
                get() = vmOptionsFinal

            @Suppress("PathAnnotationInspection")
            override val patchedVMOptionsFile: Path = appHome.parent.resolve("${appHome.fileName}.vmoptions")

            override fun startConfig(vmOptions: VMOptions, logsDir: Path): IDEStartConfig {
                vmOptions.clearSystemProperty("ide.performance.screenshot")
                vmOptions.clearSystemProperty("idea.diagnostic.opentelemetry.metrics.max-files-to-keep")
                vmOptions.clearSystemProperty("idea.diagnostic.opentelemetry.metrics.file")
                vmOptions.clearSystemProperty("idea.diagnostic.opentelemetry.meters.file.json")
                vmOptions.clearSystemProperty("idea.diagnostic.opentelemetry.file")
                vmOptions.clearSystemProperty("idea.log.path")
                vmOptions.clearSystemProperty("memory.snapshots.path")
                vmOptions.clearSystemProperty("expose.ui.hierarchy.url")
                vmOptions.addSystemProperty("expose.ui.hierarchy.url", false)
                vmOptions.addSystemProperty("idea.log.path", "/king/.config/JetBrains/IC/log")
                vmOptions.addSystemProperty("idea.diagnostic.opentelemetry.metrics.file", "")
                vmOptions.addSystemProperty("idea.diagnostic.opentelemetry.meters.file.json", "")
                vmOptions.addSystemProperty("idea.diagnostic.opentelemetry.file", "")
                vmOptions.addSystemProperty(
                    "idea.diagnostic.opentelemetry.otlp", false
                )
                vmOptions.withEnv("ENV_MONITORING_DUMPS_INTERVAL_SECONDS", "6000")
                vmOptions.withEnv("MONITORING_DUMPS_INTERVAL_SECONDS", "6000")
                vmOptions.removeLine("-Xlog:gc*:file=/aot/stuff/dev/OpenInSplitView/out/ide-tests/tests/IC-locally-installed-ide/plugin-test/simple-test-without-project/reports/gcLog.log")
                vmOptions.removeLine("-XX:ErrorFile=/aot/stuff/dev/OpenInSplitView/out/ide-tests/tests/IC-locally-installed-ide/plugin-test/simple-test-without-project/log/jvm-crash/java_error_in_idea_%p.log")
                vmOptions.removeLine("-XX:HeapDumpPath=/aot/stuff/dev/OpenInSplitView/out/ide-tests/tests/IC-locally-installed-ide/plugin-test/simple-test-without-project/log/heap-dump/heap-dump.hprof")
//                val xvfbRunLog: Path = createXvfbRunLog(logsDir)
                return object : InstalledBackedIDEStartConfig(patchedVMOptionsFile, vmOptions) {
                    //                    override val errorDiagnosticFiles: List<Path> = listOf(xvfbRunLog)
                    override val workDir: Path = appHome

                    //                    override val commandLine: List<String> = listOf(executablePath.toAbsolutePath().toString())
//                    override val commandLine: List<String> = linuxCommandLine(xvfbRunLog, vmOptions) + executablePath.toAbsolutePath().toString()
                    override val commandLine: List<String> = listOf(executablePath.toAbsolutePath().toString())

                    override val environmentVariables: Map<String, String>
                        get() = System.getenv().filterKeys {
                            // don't inherit these environment variables from parent process
                            it != "IDEA_PROPERTIES" && !it.endsWith("VM_OPTIONS") && it != "JAVA_HOME"
                        } + ("DISPLAY" to ":88")
                }
            }

            override val build = build
            override val os = OS.Linux
            override val productCode = productCode
            override val isFromSources = false
            override val installationPath: Path = appHome

            override fun toString() = "IDE{$productCode, $build, $os, home=$unpackDir}"

            override suspend fun resolveAndDownloadTheSameJDK(): Path {
                val jbrHome = appHome.resolve("jbr")
                require(jbrHome.isDirectory()) {
                    "JbrHome is not found under $jbrHome"
                }

                val jbrFullVersion = JvmUtils.callJavaVersion(jbrHome).substringAfter("build ").substringBefore(")")
                logOutput("Found following $jbrFullVersion in the product: $productCode $build")
                return jbrHome
            }
        }
    }
}

abstract class InstalledBackedIDEStartConfig(
    private val patchedVMOptionsFile: Path, private val finalVMOptions: VMOptions
) : IDEStartConfig {
    init {
        finalVMOptions.writeIntelliJVmOptionFile(patchedVMOptionsFile)
    }

    final override fun vmOptionsDiff(): VMOptionsDiff = finalVMOptions.diffIntelliJVmOptionFile(patchedVMOptionsFile)
}