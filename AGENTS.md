<repository_guidelines>
# Project Structure & Module Organization
- Project Source Code: `src/main/`.
- Intellij Platform API Source Code: `intellij-community/`. This symbolic link folder contains the IntelliJ Platform API source code that is currently in use. Use it to augment your knowledge of the API.
- Documentation: `README.md`.
- Changelog: `CHANGELOG.md`.

# Build & Plugin Configuration
- Build config: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`.
- Specify dependencies in `gradle/libs.versions.toml` (Gradle version catalog).
- Main Plugin Config: `src/main/resources/META-INF/plugin.xml` (actions, dependencies, etc).
  - Optional Plugin Dependency: ﻿`src/main/resources/META-INF/kotlin-bridge.xml` (loaded only if the `org.jetbrains.kotlin` module is installed and enabled).

## Configuration Tips
- Keep `pluginSinceBuild` and `pluginUntilBuild` in `gradle.properties` updated when upgrading platform versions.
- Update `<actions>` elements in all `src/main/resources/META-INF/*.xml` files when actions or IDs change.

# Build and Development Commands
- Run `./gradlew buildPlugin` to build the plugin and execute checks.

# Coding Style & Naming Conventions
- Kotlin 2.2 targeting JVM 21 (configured with the Gradle toolchain).
- Follow the IntelliJ default Kotlin style; use 4-space indents.

# Testing Guidelines
- Adding tests is not currently required.

# Semantic Commit Messages
Format: `<type>(<scope>): <subject>`, where `<scope>` is optional.

**Example:**
```
feat: add hat wobble
^--^  ^------------^
|     |
|     +-> Summary in present tense.
|
+-------> Type: chore, docs, feat, fix, refactor, style, or test.
```
</repository_guidelines>