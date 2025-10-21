@file:Suppress("NOTHING_TO_INLINE")

package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.generation.actions.PresentableActionHandlerBasedAction
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.lang.CodeInsightActions
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilBase
import com.intellij.psi.util.PsiUtilCore
import org.insilications.openinsplit.bridge.GotoSuperActionSplitBridge
import org.insilications.openinsplit.debug
import org.jetbrains.annotations.ApiStatus


/**
 * Opens the declaration of a method that the current method overrides or implements in the split view immediately to the right of your active tab's split view.
 * If there isn't one, a new one is created.
 * If there are multiple overrides, a popup will appear, allowing you to select one.
 */
@Suppress("CompanionObjectInExtension")
@ApiStatus.Internal
class GotoSuperActionSplit : PresentableActionHandlerBasedAction(), CodeInsightActionHandler, DumbAware {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
        private var myCurrentActionName: @NlsContexts.Command String? = null
    }

    @Suppress("UsagesOfObsoleteApi")
    override fun getHandler(): CodeInsightActionHandler {
        return this
    }

    override fun invoke(project: Project, editor: Editor, psiFile: PsiFile) {
        val languageId: String = PsiUtilCore.getLanguageAtOffset(psiFile, editor.caretModel.offset).id
        LOG.debug { "GotoSuperActionSplit - invoke for language: $languageId" }
        val gotoSuperActionSplit: GotoSuperActionSplitBridge? =
            GotoSuperActionSplitBridge.EP_NAME.extensions.find { it.goToSuperActionSplitLanguage == languageId }

        if (gotoSuperActionSplit != null) {
            LOG.debug { "GotoSuperActionSplit - invoke for language found: $languageId" }
            gotoSuperActionSplit.invoke(project, editor, psiFile)
        } else {
            LOG.debug { "GotoSuperActionSplit - invoke for language NOT found: $languageId" }
        }
    }

    override fun update(event: AnActionEvent) {
        LOG.debug { "GotoSuperActionSplit - update" }
        val gotoSuperActionSplitExtensions: Array<GotoSuperActionSplitBridge> = GotoSuperActionSplitBridge.EP_NAME.extensions

        if (gotoSuperActionSplitExtensions.isEmpty()) {
            event.presentation.setEnabledAndVisible(false)
            return
        }
        // since previous handled may have changed the presentation, we need to restore it; otherwise it will stick.
        event.presentation.copyFrom(getTemplatePresentation())
        applyTextOverride(event)

        val presentation: Presentation = event.presentation
        val dataContext: DataContext = event.dataContext
        val project: Project? = event.project
        if (project == null) {
            presentation.setEnabled(false)
            return
        }

        val activeLookup: Lookup? = LookupManager.getInstance(project).activeLookup
        if (activeLookup != null) {
            presentation.setEnabled(isValidForLookup)
        } else {
            val editor = getEditor(dataContext, project, true)
            if (editor == null) {
                presentation.setVisible(!event.isFromContextMenu)
                presentation.setEnabled(false)
                return
            }

            val psiFile: PsiFile? = PsiUtilBase.getPsiFileInEditor(editor, project)
            if (psiFile == null) {
                presentation.setEnabled(false)
                return
            }

            val languageId: String = PsiUtilCore.getLanguageAtOffset(psiFile, editor.caretModel.offset).id
            LOG.debug { "GotoSuperActionSplit - update for language: $languageId" }

            val gotoSuperActionSplit: GotoSuperActionSplitBridge? = gotoSuperActionSplitExtensions.find { it.goToSuperActionSplitLanguage == languageId }
            if (gotoSuperActionSplit != null) {
                LOG.debug { "GotoSuperActionSplit - update for language found: $languageId" }
                presentation.setEnabled(true)
                if (presentation.isVisible) {
                    gotoSuperActionSplit.update(editor, psiFile, presentation, event.isFromMainMenu, event.isFromContextMenu)
                }
            } else {
                presentation.setEnabled(false)
                LOG.debug { "GotoSuperActionSplit - update for language NOT found: $languageId" }
            }
        }

        // for Undo to show the correct action name, we remember it here to return from getCommandName(), which lack context of AnActionEvent
        myCurrentActionName = event.presentation.text
    }

    override fun isValidForFile(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
    ): Boolean {
        val languageId: String = PsiUtilCore.getLanguageAtOffset(psiFile, editor.caretModel.offset).id
        LOG.debug { "GotoSuperActionSplit - isValidForFile for language: $languageId" }
        return GotoSuperActionSplitBridge.EP_NAME.extensions.find { it.goToSuperActionSplitLanguage == languageId } != null
    }

    override fun getLanguageExtension(): LanguageExtension<out CodeInsightActionHandler?> {
        return CodeInsightActions.GOTO_SUPER
    }

    override fun startInWriteAction(): Boolean {
        return false
    }

    override fun getCommandName(): String? {
        val actionName: String? = myCurrentActionName
        return if (actionName != null) myCurrentActionName else super.getCommandName()
    }
}
