<div align="center">
  <img src="https://raw.githubusercontent.com/insilications/OpenInSplitView/master/assets/plugin_logo.png" />
</div>
<div align="center">
  <a href="https://plugins.jetbrains.com/plugin/PLUGIN_ID"><img src="https://img.shields.io/jetbrains/plugin/v/PLUGIN_ID.svg" /></a>
  <a href="https://plugins.jetbrains.com/plugin/PLUGIN_ID"><img src="https://img.shields.io/jetbrains/plugin/d/PLUGIN_ID.svg" /></a>
</div>

<div align="center">

# Open In Split View

</div>

VS Code lets you open a symbol’s declaration in a split view without losing your current context. **IntelliJ IDEs lack
this functionality.** This plugin brings that essential feature to all JetBrains IDEs.

<div align="center">

[Plugin Demo](https://github.com/user-attachments/assets/8bfffa9f-d6d0-4c5a-bc6f-438284101352)

</div>

## Key Features

- Keep your active tab focused while opening declarations, usages, or implementations in an adjacent split view.
  - Navigation rule: **Always opens in the split view immediately to the right of your active tab's split view.**
  - If there isn't one, a new one is created.
  - Example: Imagine three split views in a row: `[Split 1] [Split 2] [Split 3]`
    - Triggering navigation from a tab in `Split 1` will always open the target in `Split 2`.
    - Triggering navigation from a tab in `Split 2` will always open the target in `Split 3`.
    - Triggering navigation from a tab in `Split 3` will always wrap around and open the target in `Split 1`.
- Kotlin Inlay Hint Navigation: Opens inlay hint targets (e.g., parameter names, type hints).

## Configuration

Configure shortcuts in `Settings / Preferences → Keymap`.

<div align="center">

|              Action               |      Default Shortcut       |             Description              |
| :-------------------------------: | :-------------------------: | :----------------------------------: |
| **Declaration or Usages (Split)** |  `Ctrl + Alt + Left Click`  | Opens symbol's declaration or usages |
|   **Implementation(s) (Split)**   | `Ctrl + Shift + Left Click` |   Opens symbol's implementation(s)   |
|   **Inlay Navigation (Split)**    |  `Ctrl + Alt + Left Click`  |      Opens inlay hint's target       |

*Need help with keymap configuration? See
[JetBrains Documentation](https://www.jetbrains.com/help/idea/configuring-keyboard-and-mouse-shortcuts.html)*

</div>

### Special Note for Kotlin Inlay Hints

Due to IntelliJ platform constraints, the shortcut for **Inlay Navigation (Split)** has two strict requirements:

1. It **MUST** be a `Mouse Shortcut`.
2. It **MUST** include `Ctrl` (`Cmd` on macOS) + `Left Click`.

You can add other modifiers like `Shift` or `Alt` (e.g., `Ctrl + Alt + Left Click`), but the base combination shown
above is required by the IDE’s inlay system. Without it, the **Inlay Navigation (Split)** feature will
not work. The default Inlay Navigation behavior, using `Ctrl` (`Cmd` on macOS) + `Left Click`, is not replaced and can
still be used.

## Installation

### Method 1: JetBrains Marketplace (Recommended)

**Coming Soon**: The plugin will be available on the [JetBrains Marketplace](https://plugins.jetbrains.com/).

### Method 2: From Source

<details>
<summary>Click to expand for build instructions</summary>

1. Clone this repository: `git clone <repository_url>`.
2. Build the plugin using the Gradle wrapper: `./gradlew buildPlugin` (requires JDK 21+, Kotlin 2.2+ and Gradle 9.0+).
3. The plugin `.zip` file will be created in `build/distributions/`.
4. Install it in the IDE via: `Settings → Plugins → ⚙️ → Install Plugin from Disk...`.
5. Select the `.zip` from `build/distributions/`

</details>

## Compatibility

- **All JetBrains IDEs** (IntelliJ IDEA, WebStorm, PyCharm, etc.)
- **Minimum Version**: 2025.2.1 (Build 252.x)
