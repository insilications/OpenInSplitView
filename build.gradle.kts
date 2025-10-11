@file:Suppress("LongLine")

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

plugins {
    id("java")
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ben.manes.versions)
}

repositories {
    mavenCentral()
    gradlePluginPortal()


    intellijPlatform {
        defaultRepositories()
        localPlatformArtifacts()
    }
}

dependencies {
    // IntelliJ Platform Gradle Plugin Dependencies Extension: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Starting with version 2025.3, `use intellijIdea()`
        intellijIdeaCommunity(libs.versions.ideaVersion)
        bundledPlugin("org.jetbrains.kotlin")

        pluginVerifier(libs.versions.pluginVerifier)
    }

//    compileOnly("org.jetbrains:annotations:26.0.2")
    compileOnly(libs.jetbrains.annotations)
}

// Configure IntelliJ Platform Gradle Plugin: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    projectName = providers.gradleProperty("pluginName").get()
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        id = providers.gradleProperty("pluginGroup").get()
        name = providers.gradleProperty("pluginFullName").get()
        version = providers.gradleProperty("pluginVersion").get()
        changeNotes = provider { file("changes.html").readText() }
        description = provider { file("description.html").readText() }

        vendor {
            name = "Francisco Boni Neto"
            email = "boboniboni@gmail.com"
            url = "https://github.com/insilications/OpenInSplitView"
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild").get()
            untilBuild = providers.gradleProperty("pluginUntilBuild").get()
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
        // The Kotlin plugin already does this implicitly, but being explicit
        // can make the build script easier for others to understand.
        java.srcDirs("src/main/kotlin")
    }
}

tasks {
    withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        compilerOptions.languageVersion.set(KotlinVersion.KOTLIN_2_2)
        compilerOptions.apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }

    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
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