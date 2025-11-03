@file:Suppress("LongLine")

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val pluginGroup: String = providers.gradleProperty("pluginGroup").get()
val pluginVersion: String = providers.gradleProperty("pluginVersion").get()
val pluginFullName: String = providers.gradleProperty("pluginFullName").get()
val pluginName: String = providers.gradleProperty("pluginName").get()
val pluginSinceBuild: String = providers.gradleProperty("pluginSinceBuild").get()
val pluginUntilBuild: String = providers.gradleProperty("pluginUntilBuild").get()
val changeNotesFromMd: Provider<String> = provider {
    file("CHANGELOG.md").readText().lineSequence().filterIndexed { index, _ -> index !in 1..3 }.joinToString(System.lineSeparator()).trim()
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

dependencies {
    // IntelliJ Platform Gradle Plugin Dependencies Extension: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Starting with version 2025.3, `use intellijIdea()`
        intellijIdeaCommunity(libs.versions.ideaVersion)
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.java")

        pluginVerifier()
    }

    compileOnly(libs.jetbrains.annotations)
}

// Configure IntelliJ Platform Gradle Plugin: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    projectName = pluginName
    buildSearchableOptions = false
    instrumentCode = false
    autoReload = false
//    sandboxContainer = file("/king/.config/JetBrains/")

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

sourceSets {
    main {
        java.srcDirs("src/main")
        kotlin.srcDirs("src/main")
    }
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

        // Resolve the source directory during the configuration phase.
        val sourceConfigDir = project.file("sandbox-config")

        doLast {
            // Resolve the DirectoryProperty to a concrete File at execution time.
            val destinationDir = sandboxDirectory.get().asFile

            if (sourceConfigDir.exists() && sourceConfigDir.isDirectory) {
                println("Copying custom IDE configuration from '${sourceConfigDir.path}' to sandbox.")

                // Use standard Kotlin I/O, which is configuration-cache-safe.
                sourceConfigDir.copyRecursively(destinationDir, overwrite = true)
            } else {
                println("Skipping custom IDE configuration copy: '${sourceConfigDir.path}' does not exist.")
            }
        }
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
            "-Didea.logger.exception.expiration.minutes=0"
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
                "-Didea.logger.exception.expiration.minutes=0"
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

fun isStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val latestKeyword = listOf("SNAPSHOT").any { version.uppercase().contains(it) }
    val regex = "^[\\d,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable || latestKeyword
}
