package org.insilications.openinsplit.bridge

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiFile

interface GotoSuperActionSplitBridge : CodeInsightActionHandler {
    companion object {
        /**
         * The ExtensionPointName that allows the platform to find implementations
         * The name must be unique, typically prefixed with the plugin ID
         */
        @JvmField
        val EP_NAME: ExtensionPointName<GotoSuperActionSplitBridge> = ExtensionPointName("org.insilications.openinsplit.gotoSuperActionSplit")
    }

    val goToSuperActionSplitLanguage: String

    fun update(editor: Editor, file: PsiFile, presentation: Presentation, isFromMainMenu: Boolean, isFromContextMenu: Boolean)
}
