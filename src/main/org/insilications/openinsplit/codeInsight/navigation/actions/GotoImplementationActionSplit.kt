package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.navigation.actions.GotoImplementationAction

/**
 * Opens the implementation(s) in the split view immediately to the right of your active tab's split view. If there isn't one, a new one is created.
 * If there are multiple implementations, a popup will appear, allowing you to select one.
 */
class GotoImplementationActionSplit : GotoImplementationAction() {
    // Reuse a single handler instance to avoid per‑invocation allocations.
    private val gotoImplementationHandlerSplitShared: CodeInsightActionHandler = GotoImplementationHandlerSplit()

    protected override fun getHandler(): CodeInsightActionHandler = gotoImplementationHandlerSplitShared
}