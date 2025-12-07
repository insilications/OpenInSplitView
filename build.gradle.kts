@file:Suppress("LongLine")

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.gradle.kotlin.dsl.support.serviceOf
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.PrepareTestTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

//import org.gradle.kotlin.dsl.testImplementation

val pluginGroup: String = providers.gradleProperty("pluginGroup").get()
val pluginVersion: String = providers.gradleProperty("pluginVersion").get()
val pluginFullName: String = providers.gradleProperty("pluginFullName").get()
val pluginName: String = providers.gradleProperty("pluginName").get()
val pluginSinceBuild: String = providers.gradleProperty("pluginSinceBuild").get()
val pluginUntilBuild: String = providers.gradleProperty("pluginUntilBuild").get()
val changeNotesFromMd: Provider<String> = provider {
    file("CHANGELOG.md").readText().lineSequence().filterIndexed { index: Int, _: String -> index !in 1..3 }.joinToString(System.lineSeparator()).trim()
}
val descriptionFromHtml: Provider<String> = provider { file("description.html").readText() }
val gradleVersion: String = providers.gradleProperty("gradleVersion").get()

group = pluginGroup
version = pluginVersion

plugins {
    id("java")
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ben.manes.versions)
    alias(libs.plugins.changelog)
}

repositories {
    mavenCentral()
    gradlePluginPortal()


    intellijPlatform {
        defaultRepositories()
    }
}

sourceSets {
    main {
        // The Kotlin plugin already does this implicitly, but being explicit
        // can make the build script easier for others to understand.
        java.srcDirs("src/main")
        kotlin.srcDirs("src/main")
//        resources.srcDirs("src/main/resources")
    }

    test {
        java.srcDirs("src/test")
        kotlin.srcDirs("src/test")

    }

//    val testIntegration = create("testIntegration")
//    testIntegration.apply {
//        compileClasspath += sourceSets.main.get().output
//        runtimeClasspath += sourceSets.main.get().output
//        kotlin.srcDirs("src/testIntegration")
//    }
}

//val testIntegrationImplementation: Configuration by configurations.getting {
//    extendsFrom(configurations.testImplementation.get())
//}

//val testIntegrationRuntimeOnly: Configuration by configurations.getting {
//    extendsFrom(configurations.testRuntimeOnly.get())
//}

dependencies {
    // IntelliJ Platform Gradle Plugin Dependencies Extension: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Starting with version 2025.3, `use intellijIdea()`
        intellijIdeaCommunity(libs.versions.ideaVersion)
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")
        pluginVerifier()

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
//        testFramework(TestFrameworkType.JUnit5)

//        testFramework(TestFrameworkType.Starter, version = "latest", configurationName = testIntegrationImplementation.name)
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlin.test)

    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.kotlin.reflect)

    listOf(
        libs.kotlin.compiler.common,
        libs.kotlin.analysis.api.standalone,
        libs.kotlin.analysis.api.api,
        libs.kotlin.analysis.api.impl,
        libs.kotlin.analysis.api.platform,
        libs.kotlin.analysis.api.fir,
        libs.kotlin.low.level.api.fir,
        libs.kotlin.symbol.light.classes,
    ).forEach { it: Provider<MinimalExternalModuleDependency> ->
        compileOnly(it) {
            isTransitive = false // see KTIJ-19820
        }
    }
}

// Configure IntelliJ Platform Gradle Plugin: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    projectName = pluginName
    buildSearchableOptions = false
    instrumentCode = false
    autoReload = false
    sandboxContainer = file("/king/.config/JetBrains/IC")
    intellijPlatform.caching.ides.enabled = true

//    println("Sandbox container set to: ${sandboxContainer.get().asFile}")

    pluginConfiguration {
        id = pluginGroup
        name = pluginFullName
        version = pluginVersion
        changeNotes.set(provider { markdownToHTML(changeNotesFromMd.get()) })
        description = descriptionFromHtml

        vendor {
            name = "Francisco Boni Neto"
            email = "boboniboni@gmail.com"
            url = "https://github.com/insilications/OpenInSplitView"
        }

        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = pluginUntilBuild
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, libs.versions.ideaVersion)
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Set the JVM language level used to build the project.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        languageVersion = KotlinVersion.KOTLIN_2_2
        apiVersion = KotlinVersion.KOTLIN_2_2
    }

    jvmToolchain(21)
}

tasks {
    withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        compilerOptions.languageVersion.set(KotlinVersion.KOTLIN_2_2)
        compilerOptions.apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }

    wrapper {
        gradleVersion = gradleVersion
    }

    withType<PrepareSandboxTask> {
        sandboxDirectory.set(file("/king/.config/JetBrains/IC/"))
        sandboxSuffix.set("")
//        println("Sandbox directory set to: ${sandboxDirectory.get().asFile}")
        if (name == "prepareSandbox") {
            // Resolve the source directory during the configuration phase.
            val sourceConfigDir: File = project.file("sandbox-config")
            val execOps: ExecOperations = project.serviceOf<ExecOperations>()

            doLast {
                // Resolve the DirectoryProperty to a concrete File at execution time.
                val destinationDir: File = sandboxDirectory.get().asFile

                if (sourceConfigDir.exists() && sourceConfigDir.isDirectory) {
                    println("Copying custom IDE configuration from '${sourceConfigDir.path}' to sandbox folder '${destinationDir.path}'.")
                    execOps.exec {
                        commandLine("rsync", "-avc", "--no-times", "${sourceConfigDir.path}/", "${destinationDir.path}/")
                    }

                } else {
                    println("Skipping custom IDE configuration copy: '${sourceConfigDir.path}' does not exist.")
                }

                // --- Start: Truncate idea.log ---
                val ideaLogFile: File = destinationDir.resolve("log/idea.log")

                if (ideaLogFile.exists()) {
                    println("Truncating log file: ${ideaLogFile.path}")
                    ideaLogFile.writeText("") // Overwrites the file with an empty string.
                } else {
                    println("Log file not found, skipping truncation: ${ideaLogFile.path}")
                }
            }
        } else {
            doLast {
                // Resolve the DirectoryProperty to a concrete File at execution time.
                val destinationDir: File = sandboxDirectory.get().asFile

                // --- Start: Truncate idea.log ---
                val ideaLogFile: File = destinationDir.resolve("log/idea.log")

                if (ideaLogFile.exists()) {
//                    println("Truncating log file: ${ideaLogFile.path}")
                    ideaLogFile.writeText("") // Overwrites the file with an empty string.
                } else {
//                    println("Log file not found, skipping truncation: ${ideaLogFile.path}")
                }
            }
        }
    }

    withType<PrepareTestTask> {
        sandboxDirectory.set(file("/king/.config/JetBrains/IC/"))
//        println("Test sandbox directory set to: ${sandboxDirectory.get().asFile}")

        // Resolve the source directory during the configuration phase.
        val sourceConfigDir: File = project.file("sandbox-config")
        val execOps: ExecOperations = project.serviceOf<ExecOperations>()

        doLast {
            // Resolve the DirectoryProperty to a concrete File at execution time.
            val destinationDir: File = sandboxDirectory.get().asFile

            // --- Start: Truncate idea.log ---
            val ideaLogFile: File = destinationDir.resolve("log/idea.log")

            if (ideaLogFile.exists()) {
//                println("Truncating log file: ${ideaLogFile.path}")
                ideaLogFile.writeText("") // Overwrites the file with an empty string.
            } else {
//                println("Log file not found, skipping truncation: ${ideaLogFile.path}")
            }
        }
    }

    test {
        outputs.upToDateWhen { false }

        testLogging {
            showStandardStreams = true
        }

        jvmArgs = listOf(
            "-Xms256m",
            "-Xmx8096m",
            "-Dawt.useSystemAAFontSettings=lcd_hbgr",
            "-Dswing.aatext=true",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+DebugNonSafepoints",
            "-Dignore.ide.script.launcher.used=true",
            "-Dide.slow.operations.assertion=true",
            "-Didea.is.internal=true",
            "-Didea.logger.exception.expiration.minutes=0",
            "-Dsnapshots.path=/king/stuff/snapshots",
            "-Djdk.gtk.verbose=true",
            "-Djdk.gtk.version=3",
            "-Didea.diagnostic.opentelemetry.metrics.file=",
            "-Didea.diagnostic.opentelemetry.meters.file.json=",
            "-Didea.diagnostic.opentelemetry.file=",
            "-Didea.diagnostic.opentelemetry.otlp=false",
            "-Xlog:disable"
        )
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Xms256m",
                "-Xmx8096m",
                "-Dawt.useSystemAAFontSettings=lcd_hbgr",
                "-Dswing.aatext=true",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+DebugNonSafepoints",
                "-Dignore.ide.script.launcher.used=true",
                "-Dide.slow.operations.assertion=true",
                "-Didea.is.internal=true",
                "-Didea.logger.exception.expiration.minutes=0",
                "-Dsnapshots.path=/king/stuff/snapshots",
                "-Djdk.gtk.verbose=true",
                "-Djdk.gtk.version=3",
                "-Didea.diagnostic.opentelemetry.metrics.file=",
                "-Didea.diagnostic.opentelemetry.meters.file.json=",
                "-Didea.diagnostic.opentelemetry.file=",
                "-Didea.diagnostic.opentelemetry.otlp=false",
                "-Xlog:disable"
            )
        }

        systemProperty("idea.log.debug.categories", "org.insilications.openinsplit:all")
    }


    runIde {
        jvmArgs = listOf(
            "-Xms256m",
            "-Xmx8096m",
            "-Dawt.useSystemAAFontSettings=lcd_hbgr",
            "-Dswing.aatext=true",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+DebugNonSafepoints",
            "-Dignore.ide.script.launcher.used=true",
            "-Dide.slow.operations.assertion=true",
            "-Didea.is.internal=true",
            "-Didea.logger.exception.expiration.minutes=0",
            "-Dsnapshots.path=/king/stuff/snapshots",
            "-Djdk.gtk.verbose=true",
            "-Djdk.gtk.version=3",
            "-Didea.diagnostic.opentelemetry.metrics.file=",
            "-Didea.diagnostic.opentelemetry.meters.file.json=",
            "-Didea.diagnostic.opentelemetry.file=",
            "-Didea.diagnostic.opentelemetry.otlp=false"
        )
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Xms256m",
                "-Xmx8096m",
                "-Dawt.useSystemAAFontSettings=lcd_hbgr",
                "-Dswing.aatext=true",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+DebugNonSafepoints",
                "-Dignore.ide.script.launcher.used=true",
                "-Dide.slow.operations.assertion=true",
                "-Didea.is.internal=true",
                "-Didea.logger.exception.expiration.minutes=0",
                "-Dsnapshots.path=/king/stuff/snapshots",
                "-Djdk.gtk.verbose=true",
                "-Djdk.gtk.version=3",
                "-Didea.diagnostic.opentelemetry.metrics.file=",
                "-Didea.diagnostic.opentelemetry.meters.file.json=",
                "-Didea.diagnostic.opentelemetry.file=",
                "-Didea.diagnostic.opentelemetry.otlp=false"
            )
        }

        args(listOf("nosplash"))
    }


}

tasks.named<DependencyUpdatesTask>("dependencyUpdates").configure {

    rejectVersionIf {
        isStable(currentVersion) && !isStable(candidate.version)
    }

    checkForGradleUpdate = true
    outputDir = "build/dependencyUpdates"
    reportfileName = "report"
}

//val testIntegration: TaskProvider<Test> = tasks.register<Test>("testIntegration") {
////    outputs.upToDateWhen { false }
//    dependsOn(tasks.buildPlugin)
//    dependsOn(tasks.prepareSandbox)
//
//    val integrationTestSourceSet: SourceSet = sourceSets.getByName("testIntegration")
//    testClassesDirs = integrationTestSourceSet.output.classesDirs
//    classpath = integrationTestSourceSet.runtimeClasspath
//    systemProperty("path.to.build.plugin", tasks.prepareSandbox.get().pluginDirectory.get().asFile)
//    systemProperty("path.to.platform", tasks.prepareSandbox.get().platformPath.toFile())
//    environment("MONITORING_DUMPS_INTERVAL_SECONDS", "6000")
//    environment("ENV_MONITORING_DUMPS_INTERVAL_SECONDS", "6000")
//    useJUnitPlatform()
//
//}

fun isStable(version: String): Boolean {
    val stableKeyword: Boolean = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val latestKeyword: Boolean = listOf("SNAPSHOT").any { version.uppercase().contains(it) }
    val regex: Regex = "^[\\d,.v-]+(-r)?$".toRegex()
    val isStable: Boolean = stableKeyword || regex.matches(version)
    return isStable || latestKeyword
}
