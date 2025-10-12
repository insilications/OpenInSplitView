# Repository Guidelines

## Project Structure & Module Organization
- Project Source: `src/main/kotlin/org/insilications/openinsplit/` (Kotlin actions and core logic).
- Intellij Platform API Source Code: `intellij-community/`. This symbolic link folder contains the IntelliJ Platform API source code currently in use. Use it to enhance your knowledge of the API.
- Docs: `README.md`.
- Changelog: `CHANGELOG.md`.

## Build & Plugin configuration
- Build Config: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`.
- Gradle's version catalog is used to specify dependencies: `gradle/libs.versions.toml`.
- Main Plugin Config: `src/main/resources/META-INF/plugin.xml` (actions, dependencies, etc).
  - Optional Plugin Dependency: ﻿`src/main/resources/META-INF/kotlin-bridge.xml` (loaded only if the `org.jetbrains.kotlin` module is installed and enabled).

### Configuration Tips
- Do not commit signing materials. CI/Release uses env vars: `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`.
- Respect `pluginSinceBuild` and `pluginUntilBuild` in `gradle.properties` when upgrading platform versions.
- Update the `<actions>` elements in the `src/main/resources/META-INF/*.xml` files when actions/IDs change.

## Build And Development Commands
- Use `./gradlew buildPlugin` to compile the plugin and perform checks.

## Coding Style & Naming Conventions
- Language: Kotlin 2.2 targeting JVM 21 (Gradle toolchain configured).
- Style: IntelliJ default Kotlin style; 4-space indent.

## Testing Guidelines
- Adding tests is not currently necessary.

## Semantic Commit Messages
Format: `<type>(<scope>): <subject>`
`<scope>` is optional

### Example
```
feat: add hat wobble
^--^  ^------------^
|     |
|     +-> Summary in present tense.
|
+-------> Type: chore, docs, feat, fix, refactor, style, or test.
```
