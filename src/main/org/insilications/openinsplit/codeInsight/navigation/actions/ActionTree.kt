package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import org.insilications.openinsplit.debug

class ActionTree : DumbAwareAction() {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        e.presentation.setEnabledAndVisible(e.project != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        LOG.debug { "ActionTree - actionPerformed" }
    }


}