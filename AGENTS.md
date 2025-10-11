# Repository Guidelines

## Project Structure & Module Organization
- Project Source: `src/main/kotlin/org/insilications/openinsplit/` (Kotlin actions and core logic).
- Intellij Platform API Source Code: `intellij-community/`. This symbolic link folder contains the IntelliJ Platform API source code, which will help you enhance your knowledge of the API.
- Plugin config: `src/main/resources/META-INF/plugin.xml` (actions, IDs).
- Build: `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`.
- Versioning: Semantic Versioning 2.0.0 (https://semver.org).
- Docs: `README.md`.
- Changelog: `CHANGELOG.md`.

## Coding Style & Naming Conventions
- Language: Kotlin 2.2 targeting JVM 21 (Gradle toolchain configured).
- Style: IntelliJ default Kotlin style; 4-space indent; no wildcard imports.

## Shell Tools
### Find files with `fd`
- Find files by file name pattern in a directory: `fd -L <regex-pattern> <directory>`
- List all files in a directory: `fd -L . <directory>`

### Find text with `rg` (ripgrep)
- Find Text: `rg -L` (ripgrep)

## Build And Development Commands
- `./gradlew buildPlugin` — Compiles, checks and packages. Creates the *.zip distribution file in `build/distributions/`.
- `./gradlew verifyPlugin` — Runs IntelliJ Plugin Verifier against configured IDEs.

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

## Security & Configuration Tips
- Do not commit signing materials. CI/Release uses env vars: `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`.
- Respect `pluginSinceBuild` in `gradle.properties` when upgrading platform versions.
- The project uses Gradle's version catalog to specify dependencies: `gradle/libs.versions.toml`.
- Update the `<actions>` element in `plugin.xml` when actions/IDs change.
