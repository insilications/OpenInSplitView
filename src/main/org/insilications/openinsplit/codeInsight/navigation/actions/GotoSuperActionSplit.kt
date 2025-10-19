package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.actions.BaseCodeInsightAction
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilCore
import org.insilications.openinsplit.bridge.GotoSuperActionSplitBridge
import org.insilications.openinsplit.debug
import org.jetbrains.annotations.ApiStatus


/**
 * Opens the declaration of a method that the current method overrides or implements in the split view immediately to the right of your active tab's split view.
 * If there isn't one, a new one is created.
 * If there are multiple implementations, a popup will appear, allowing you to select one.
 */
@ApiStatus.Internal
//class GotoSuperActionSplit : PresentableActionHandlerBasedAction(), CodeInsightActionHandler, DumbAware {
class GotoSuperActionSplit : BaseCodeInsightAction(), CodeInsightActionHandler, DumbAware {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
        private const val KOTLIN = "kotlin"
//        @NonNls
//        const val FEATURE_ID: String = "navigation.goto.super"
    }

    private var myCurrentActionName: @NlsContexts.Command String? = null

    @Suppress("UsagesOfObsoleteApi")
    override fun getHandler(): CodeInsightActionHandler {
        return this
    }

    override fun invoke(project: Project, editor: Editor, psiFile: PsiFile) {
        val offset: Int = editor.caretModel.offset
        val language: Language = PsiUtilCore.getLanguageAtOffset(psiFile, offset)
        LOG.debug { "Invoking GotoSuperActionSplit for language: ${language.id}" }

        val gotoSuperActionSplit: GotoSuperActionSplitBridge? = GotoSuperActionSplitBridge.EP_NAME.extensions.firstOrNull()
        gotoSuperActionSplit?.invoke(project, editor, psiFile)
//        val codeInsightActionHandler: @UnknownNullability CodeInsightActionHandler? = CodeInsightActions.GOTO_SUPER.forLanguage(language)
//        if (codeInsightActionHandler != null) {
//            DumbService.getInstance(project).withAlternativeResolveEnabled(Runnable { codeInsightActionHandler.invoke(project, editor, psiFile) })
//        }
    }

    override fun startInWriteAction(): Boolean {
        return false
    }

    override fun getCommandName(): String? {
        val actionName: String? = myCurrentActionName
        return if (actionName != null) myCurrentActionName else super.getCommandName()
    }

    override fun update(
        presentation: Presentation,
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
        dataContext: DataContext,
        actionPlace: String?,
    ) {
        // avoid evaluating isValidFor several times unnecessary
//        val handler: CodeInsightActionHandler? = getValidHandler(editor, psiFile)
        val gotoSuperActionSplit: GotoSuperActionSplitBridge? = GotoSuperActionSplitBridge.EP_NAME.extensions.firstOrNull()
        presentation.setEnabled(gotoSuperActionSplit != null)
//        if (handler is ContextAwareActionHandler && !ActionPlaces.isMainMenuOrActionSearch(actionPlace)) {
//            presentation.setVisible((handler as ContextAwareActionHandler).isAvailableForQuickList(editor, psiFile, dataContext))
//        }

//        if (presentation.isVisible && gotoSuperActionSplit is PresentableCodeInsightActionHandler) {
        if (presentation.isVisible && gotoSuperActionSplit is GotoSuperActionSplitBridge) {
            gotoSuperActionSplit.update(editor, psiFile, presentation, actionPlace)
        }
//        super.update(presentation, project, editor, psiFile, dataContext, actionPlace)
    }

    override fun update(event: AnActionEvent) {
        val gotoSuperActionSplit: GotoSuperActionSplitBridge? = GotoSuperActionSplitBridge.EP_NAME.extensions.firstOrNull()

        if (gotoSuperActionSplit == null) {
            event.presentation.setEnabledAndVisible(false)
            return
        }
        // since previous handled may have changed the presentation, we need to restore it; otherwise it will stick.
        event.presentation.copyFrom(getTemplatePresentation())
        applyTextOverride(event)
        super.update(event)

        // for Undo to show the correct action name, we remember it here to return from getCommandName(), which lack context of AnActionEvent
        myCurrentActionName = event.presentation.text
    }
}
