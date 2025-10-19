package org.insilications.openinsplit.bridge

import com.intellij.codeInsight.generation.actions.PresentableCodeInsightActionHandler
import com.intellij.openapi.extensions.ExtensionPointName

interface GotoSuperActionSplitBridge : PresentableCodeInsightActionHandler {
    companion object {
        /**
         * The ExtensionPointName that allows the platform to find implementations
         * The name must be unique, typically prefixed with the plugin ID
         */
        @JvmField
        val EP_NAME: ExtensionPointName<GotoSuperActionSplitBridge> = ExtensionPointName("org.insilications.openinsplit.gotoSuperActionSplit")
    }

    val goToSuperActionSplitLanguage: String
}
