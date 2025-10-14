package org.insilications.openinsplit.codeInsight.hints

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Inlay Navigation (Split) is a dummy action used only to hold a mouse shortcut in the Keymap.
 * Users can customize this action's mouse shortcut customize the mouse shortcut that triggers
 * the Inlay Navigation in a split view.
 *
 * The shortcut has two strict requirements:
 * 1. It **MUST** be a `Mouse Shortcut`.
 * 2. It **MUST** include `Ctrl` (`Cmd` on macOS) + `Left Click`.
 *
 * This happens because the low level inlay rendering system and its mouse click event system hardcodes these requirements.
 */
class InlayNavigationActionSplit : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        // Intentionally no-op. This action exists only for its configurable mouse shortcut.
    }
}