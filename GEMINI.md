<repository_guidelines>
# Project Structure
- Project Source Code: `src/main/`
- Documentation: `README.md`
- Changelog: `CHANGELOG.md`

# API Reference Sources
For IntelliJ Platform and Kotlin Analysis APIs: Always verify behavior against the actual source code in `intellij-community/` and `kotlin-src/` rather than making assumptions, especially when:
- API behavior or parameters are unclear
- You need implementation examples or usage patterns
- Verifying deprecated methods, best practices, or undocumented behaviors

These sources contain the ground truth—treat them as the definitive reference.

# Build & Plugin Configuration
- Build config: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- Specify dependencies in `gradle/libs.versions.toml` (Gradle Version Catalog)
- Main Plugin Config: `src/main/resources/META-INF/plugin.xml` (actions, dependencies, etc)
  - Optional Plugin Dependencies: `src/main/resources/META-INF/kotlin-bridge.xml`, `src/main/resources/META-INF/java-bridge.xml`

# Build and Development Commands
- Run `./gradlew buildPlugin` to build the plugin and run checks.

# Coding Style & Naming Conventions
- Kotlin 2.2 targeting JVM 21 (configured with the Gradle toolchain).
- Follow the IntelliJ default Kotlin style; use 4-space indents.

# Testing Guidelines
- Adding tests is not currently required.

# Semantic Commit Messages
Format: `<type>(<scope>): <subject>`, where `<scope>` is optional.

Example:
```
feat: add hat wobble
^--^  ^------------^
|     |
|     +-> Summary in present tense.
|
+-------> Type: chore, docs, feat, fix, refactor, style, or test.
```
</repository_guidelines>