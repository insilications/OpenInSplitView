// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.insilications.openinsplit.codeInsight.hints

import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.PsiPointerInlayActionPayload
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.util.PsiNavigateUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.insilications.openinsplit.codeInsight.navigation.actions.getAdjacentSplitView
import org.insilications.openinsplit.codeInsight.navigation.actions.getVirtualFileFromNavigatable
import org.insilications.openinsplit.codeInsight.navigation.impl.PROGRESS_TITLE_PREPARING_NAVIGATION
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

@Suppress("CompanionObjectInExtension", "ExtensionClassShouldBeFinalAndNonPublic")
class PsiPointerInlayActionNavigationHandlerSplit : InlayActionHandler {
    companion object {
        @Suppress("unused")
        const val HANDLER_ID: String = "psi.pointer.navigation.handler"
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
        private const val DUMMY_MOUSE_ACTION_ID: String = "InlayNavigationActionSplit"

        // This gives us a mask allows us to remove any button masks from modifiers
        private const val MOUSE_BUTTON_MASKS: Int = (InputEvent.BUTTON1_DOWN_MASK or InputEvent.BUTTON2_DOWN_MASK or InputEvent.BUTTON3_DOWN_MASK).inv()

        /**
         * Check if the mouse event matches any of the configured mouse shortcuts for the `InlayNavigationActionSplit` dummy action.
         * This dummy action allows the user to customize the mouse shortcut to trigger Inlay Navigation in a split view.
         *
         * The shortcut has two strict requirements:
         * 1. It **MUST** be a `Mouse Shortcut`.
         * 2. It **MUST** include `Ctrl` (`Cmd` on macOS) + `Left Click`.
         *
         * This happens because the low level inlay rendering system and its mouse click event system hardcodes these requirements.
         */
        private fun matchesAnyConfiguredMouseShortcut(e: EditorMouseEvent): Boolean {
            val keymap: Keymap = KeymapManager.getInstance().activeKeymap
            val mouseShortcuts: List<MouseShortcut> =
                keymap.getShortcuts(DUMMY_MOUSE_ACTION_ID).filterIsInstance<MouseShortcut>()
            if (mouseShortcuts.isEmpty()) return false

            val me: MouseEvent = e.mouseEvent
            val eventButton: Int = me.button
            val eventClicks: Int = me.clickCount
            // Remove any button masks from modifiers, compare only keyboard modifiers.
            val eventMods: Int = me.modifiersEx and MOUSE_BUTTON_MASKS

            return mouseShortcuts.any { ms: MouseShortcut ->
                ms.button == eventButton && ms.clickCount == eventClicks && (ms.modifiers and MOUSE_BUTTON_MASKS) == eventMods
            }
        }
    }

    @RequiresEdt
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) {
        payload as PsiPointerInlayActionPayload
        val psiElement: PsiElement? = payload.pointer.element

        if (psiElement != null && psiElement.isValid) {
//            LOG.debug { "0 PsiPointerInlayActionNavigationHandlerSplit - handleClick" }
            val editor: Editor = e.editor
            val project: Project = editor.project ?: return
            return runWithModalProgressBlocking(project, PROGRESS_TITLE_PREPARING_NAVIGATION) {
                withContext(Dispatchers.EDT) {
                    // We have implicit write intent lock under `Dispatchers.EDT`, which implies an **IMPLICIT** read lock too
                    // Therefore, in the latest Intellij Platform API, we don't need to explictly get a read lock inside EDT
                    // This will change in future releases, by shifting to using `Dispatchers.UI`, so keep an eye on it

                    val navigatable: Navigatable? = PsiNavigateUtil.getNavigatable(psiElement)
                    if (navigatable != null) {
                        if (matchesAnyConfiguredMouseShortcut(e)) {
                            // History update on EDT
                            IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
                            // We preemptively set the current split view (window) to the adjacent split view or a new split view
                            // This forces subsequent calls to Intellij Platform API's `navigate` methods to use the adjacent split view.
                            // This workaround might be fragile, but it works perfectly
                            getAdjacentSplitView(project) {
                                getVirtualFileFromNavigatable(navigatable)
                            }
//                            LOG.debug { "1 PsiPointerInlayActionNavigationHandlerSplit - handleClick" }
                        }

                        navigatable.navigate(true)
//                        LOG.debug { "2 PsiPointerInlayActionNavigationHandlerSplit - handleClick" }
                    }
                }
            }
        }
    }
}