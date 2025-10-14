package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.actionSystem.DataContext

/**
 * Opens the declaration/usage in the split view immediately to the right of your active tab's split view. If there isn't one, a new one is created.
 * If there are multiple declarations/usages, a popup will appear, allowing you to select one.
 * Execution runs on the EDT via the platform action pipeline, so heavy work must be offloaded.
 */
class GotoDeclarationActionSplit : GotoDeclarationAction() {
    // Reuse a single handler instance to avoid per‑invocation allocations.
    private val gotoDeclarationOrUsageHandler2SplitShared: CodeInsightActionHandler = GotoDeclarationOrUsageHandler2Split()

    protected override fun getHandler(): CodeInsightActionHandler = gotoDeclarationOrUsageHandler2SplitShared

    protected override fun getHandler(dataContext: DataContext): CodeInsightActionHandler = gotoDeclarationOrUsageHandler2SplitShared
}
