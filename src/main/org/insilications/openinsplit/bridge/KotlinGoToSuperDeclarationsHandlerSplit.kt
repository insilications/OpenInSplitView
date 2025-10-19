package org.insilications.openinsplit.bridge

import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.NlsContexts
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.insilications.openinsplit.codeInsight.navigation.actions.navigateToNavigatable
import org.insilications.openinsplit.debug
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeInsight.SuperDeclaration
import org.jetbrains.kotlin.idea.codeInsight.SuperDeclarationProvider
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

internal class KotlinGoToSuperDeclarationsHandlerSplit : GotoSuperActionSplitBridge {
    @Suppress("CompanionObjectInExtension")
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
        private const val SUPER_CLASS_SPLIT = "Super Class (Split)"
        private const val SUPER_METHOD_SPLIT = "Super Method (Split)"
        private const val SUPER_PROPERTY_SPLIT = "Super Property (Split)"

        fun findTargetDeclaration(
            file: PsiFile,
            editor: Editor,
        ): KtDeclaration? {
            val element: PsiElement = file.findElementAt(editor.caretModel.offset) ?: return null
            return SuperDeclarationProvider.findDeclaration(element)
        }

        private fun findSuperDeclarations(targetDeclaration: KtDeclaration): HandlerResult? {
            val superDeclarations: List<SuperDeclaration> =
                SuperDeclarationProvider.findSuperDeclarations(targetDeclaration).takeIf { it.isNotEmpty() } ?: return null

            if (superDeclarations.size == 1) {
                return HandlerResult.Single(superDeclarations.single())
            } else {
                val title: String = when (targetDeclaration) {
                    is KtClassOrObject, is PsiClass -> KotlinBundle.message("goto.super.chooser.class.title")
                    is KtFunction, is PsiMethod -> KotlinBundle.message("goto.super.chooser.function.title")
                    is KtProperty, is KtParameter -> KotlinBundle.message("goto.super.chooser.property.title")
                    else -> error("Unexpected declaration $targetDeclaration")
                }
                return HandlerResult.Multiple(title, superDeclarations)
            }
        }

        @RequiresEdt
        fun gotoSuperDeclarations(project: Project, targetDeclaration: KtDeclaration): JBPopup? {
            when (val result: HandlerResult? = findSuperDeclarations(targetDeclaration)) {
                is HandlerResult.Single -> {
                    LOG.debug { "KotlinGoToSuperDeclarationsHandlerSplit - gotoSuperDeclarations - HandlerResult.Single" }
                    val navigatable: Navigatable = result.item.descriptor?.takeIf { it.canNavigate() } ?: return null
                    navigateToNavigatable(project, navigatable, null)
                }

                is HandlerResult.Multiple -> {
                    val superDeclarationsArray: Array<PsiElement> = result.items.mapNotNull { it.declaration.element }.toTypedArray()

                    if (superDeclarationsArray.isNotEmpty()) {
                        LOG.debug { "KotlinGoToSuperDeclarationsHandlerSplit - gotoSuperDeclarations - HandlerResult.Multiple" }
                        return PsiTargetNavigator(superDeclarationsArray).createPopup(
                            project = superDeclarationsArray[0].project,
                            title = result.title,
                        ) { element: PsiElement ->
                            navigateToNavigatable(project, element as Navigatable, null)
                            return@createPopup true
                        }
                    }
                }

                null -> {}
            }
            return null
        }
    }

    override val goToSuperActionSplitLanguage: String = "kotlin"


    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (file !is KtFile) {
            return
        }

        LOG.debug { "KotlinGoToSuperDeclarationsHandlerSplit - invoke" }

        val targetDeclaration: KtDeclaration = findTargetDeclaration(file, editor) ?: return
        gotoSuperDeclarations(project, targetDeclaration)?.showInBestPositionFor(editor)
    }

    sealed class HandlerResult {
        @ApiStatus.Internal
        class Single(val item: SuperDeclaration) : HandlerResult() {
            override val items: List<SuperDeclaration>
                get() = listOf(item)
        }

        @ApiStatus.Internal
        class Multiple(val title: @NlsContexts.PopupTitle String, override val items: List<SuperDeclaration>) : HandlerResult()

        @Suppress("UnstableTypeUsedInSignature")
        abstract val items: List<SuperDeclaration>
    }


    override fun update(
        editor: Editor,
        file: PsiFile,
        presentation: Presentation?,
    ) {
        LOG.debug { "KotlinGoToSuperDeclarationsHandlerSplit - update 0" }
        update(editor, file, presentation ?: return, actionPlace = null)
    }

    override fun update(editor: Editor, file: PsiFile, presentation: Presentation, actionPlace: String?) {
        LOG.debug { "KotlinGoToSuperDeclarationsHandlerSplit - update 1" }
        if (file !is KtFile) return
        val targetDeclaration: KtDeclaration = findTargetDeclaration(file, editor) ?: return
        presentation.text = when (targetDeclaration) {
            is KtClassOrObject, is PsiClass -> SUPER_CLASS_SPLIT
            is KtFunction, is PsiMethod -> SUPER_METHOD_SPLIT
            is KtProperty, is KtParameter -> SUPER_PROPERTY_SPLIT
            else -> error("Unexpected declaration $targetDeclaration")
        }
    }

    override fun startInWriteAction(): Boolean = false
}