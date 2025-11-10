package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.insilications.openinsplit.debug
import org.jetbrains.kotlin.idea.k2.codeinsight.structureView.KotlinFirStructureViewElement
import org.jetbrains.kotlin.idea.refactoring.project
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

class SymbolsInformationAction : DumbAwareAction() {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
        private const val GETTING_SYMBOL_INFO = "Getting symbols information..."
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        e.presentation.setEnabledAndVisible(e.project != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        LOG.debug { "actionPerformed" }

        val dataContext: DataContext = e.dataContext
        val project: Project = dataContext.project
        val editor: Editor? = CommonDataKeys.EDITOR.getData(dataContext)
        val file: PsiFile? = CommonDataKeys.PSI_FILE.getData(dataContext)

        if (file == null || editor == null) {
            e.presentation.isEnabled = false
            return
        }

        val offset: Int = editor.caretModel.offset
        val targetElementUtilInstance: TargetElementUtil = TargetElementUtil.getInstance()
        val targetElementUtilAllAccepted: Int = targetElementUtilInstance.allAccepted
        // Find the semantic target element at the caret `offset`
        var targetElement: PsiElement? = targetElementUtilInstance.findTargetElement(editor, targetElementUtilAllAccepted, offset)

        if (targetElement == null) {
            LOG.info("actionPerformed - targetElement == null")
            targetElement = file
            if (targetElement is KtCommonFile) {
                LOG.debug("actionPerformed - targetElement file name: ${targetElement.name}")
            }
        } else {
//            LOG.info("actionPerformed - targetElement: ${targetElement.text}")
            LOG.info("actionPerformed - targetElement::class.simpleName: ${targetElement::class.simpleName}")
            LOG.info("actionPerformed - targetElement::class.qualifiedName: ${targetElement::class.qualifiedName}")

            if (targetElement is KtDeclarationWithBody) {
                LOG.info("actionPerformed - targetElement.name: ${targetElement.name}")
            }
        }
        runWithModalProgressBlocking(project, GETTING_SYMBOL_INFO) {
            runReadAction {
                val targetElementStructure: Collection<StructureViewTreeElement> = targetElement.getStructureViewChildren {
                    KotlinFirStructureViewElement(it, it, isInherited = false)
                }
                LOG.debug { "actionPerformed - targetElementStructure size: ${targetElementStructure.size}" }
                for (element: StructureViewTreeElement in targetElementStructure) {
                    LOG.info("actionPerformed - targetElementStructure: ${element.presentation.presentableText}")
                }
            }
        }
    }
}

fun isStructureViewNode(element: PsiElement?): Boolean =
    element is KtDeclaration &&
            element !is KtPropertyAccessor &&
            element !is KtFunctionLiteral &&
            !(element is KtParameter && !element.hasValOrVar()) &&
            !((element is KtProperty || element is KtFunction) && !element.topLevelDeclaration && element.containingClassOrObject !is KtNamedDeclaration)

private val KtDeclaration.topLevelDeclaration: Boolean
    get() = parent is PsiFile || parent is KtBlockExpression && parent.parent is KtScript

fun PsiElement.getStructureViewChildren(factory: (KtDeclaration) -> StructureViewTreeElement): Collection<StructureViewTreeElement> {
    @Suppress("DEPRECATION")
    val children: List<KtDeclaration> = when (val element: PsiElement = this) {
        is KtCommonFile -> {
            val declarations: List<KtDeclaration> = element.declarations
            if (element.isScript()) {
                (declarations.singleOrNull() as? KtScript) ?: element
            } else {
                element
            }.declarations
        }

        is KtClass -> element.getStructureDeclarations()
        is KtClassOrObject -> element.declarations
        is KtFunction, is KtClassInitializer, is KtProperty -> element.collectLocalDeclarations()
        else -> emptyList()
    }

    return children.map { factory(it) }
}

private fun PsiElement.collectLocalDeclarations(): List<KtDeclaration> {
    val result: MutableList<KtDeclaration> = mutableListOf<KtDeclaration>()

    acceptChildren(object : KtTreeVisitorVoid() {
        override fun visitClassOrObject(classOrObject: KtClassOrObject) {
            result.add(classOrObject)
        }

        override fun visitNamedFunction(function: KtNamedFunction) {
            result.add(function)
        }
    })

    return result
}

fun KtClassOrObject.getStructureDeclarations(): List<KtDeclaration> =
    buildList {
        primaryConstructor?.let { add(it) }
        primaryConstructorParameters.filterTo(this) { it.hasValOrVar() }
        addAll(declarations)
    }