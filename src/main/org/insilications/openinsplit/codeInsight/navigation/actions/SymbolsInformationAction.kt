@file:Suppress("NOTHING_TO_INLINE")

package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.insilications.openinsplit.debug
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.*
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.refactoring.project
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.debugText.getDebugText
import kotlin.reflect.KCallable

class SymbolsInformationAction : DumbAwareAction() {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
        private const val GETTING_SYMBOL_INFO = "Getting symbols information..."
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

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
        val targetElementUtil: TargetElementUtil = TargetElementUtil.getInstance()
        val flags: Int = targetElementUtil.allAccepted
        val target: PsiElement? = targetElementUtil.findTargetElement(editor, flags, offset)

        if (target == null) {
            LOG.info("No target element at caret")
            return
        }

        val outputSb = StringBuilder()
//        outputSb.append("\n    Target PSI type: ${target::class.qualifiedName}")
//        if (target is KtDeclarationWithBody) {
//            LOG.info("Target name: ${target.presentation?.presentableText ?: target.name}")
//        }

        val targetDeclarationFullText: String = target.text
        outputSb.append("\n    ============ Target PSI type: ${target::class.qualifiedName} ============\n")
        outputSb.append("${targetDeclarationFullText}\n")

        runWithModalProgressBlocking(project, GETTING_SYMBOL_INFO) {
            if (DumbService.isDumb(project)) {
                LOG.warn("Dumb mode active; aborting semantic resolution.")
                return@runWithModalProgressBlocking
            }

            val decl: KtDeclaration = target as? KtDeclaration ?: return@runWithModalProgressBlocking
//            val kk = decl.text
            readAction {
                // Executes the given action in an analysis session context.
                // The project will be analyzed from the perspective of `decl`'s module, also called the use-site module.
                // Neither the analysis session nor any other lifetime owners may be leaked outside the analyze block.
                // Please consult the documentation of `KaSession` for important information about lifetime management.
                analyze(decl) {
                    val usages: List<ResolvedUsage> = collectResolvedUsagesInDeclaration(decl, maxRefs = 2000)

                    outputSb.append("\n\n    ============ Resolved Dependencies: ${usages.size} ============\n")

                    usages.forEach { usage: ResolvedUsage ->
                        val presentable: String = when (val sym: KaSymbol = usage.symbol) {
                            is KaNamedSymbol -> sym.name.asString()
                            else -> sym.toString()
                        }

                        // Returns the symbol's `PsiElement` if its type is `PSI` and `KaSymbol.origin` is
                        // `KaSymbolOrigin.SOURCE` or `KaSymbolOrigin.JAVA_SOURCE`, and null otherwise.
                        val psiSafeSymbol: KtElement? = usage.symbol.sourcePsiSafe<KtElement>()
                        if (psiSafeSymbol == null) {
//                            outputSb.append("\n    kind=${usage.usageKind} symbol=$presentable siteRange=${usage.site.textRange}\n    siteText:\n${usage.site.text}\n\n    (No PSI available for symbol)\n\n")
                            outputSb.append("\n        ========== kind=${usage.usageKind} symbol=$presentable site.textRange=${usage.site.textRange} ==========\n")
                            outputSb.append("        ========== site.text ==========\n${usage.site.text}\n")
                            outputSb.append("\n        ========== symbol.getDebugText() ==========\n(No PSI available for symbol)\n\n\n")

//                            LOG.info("kind=${usage.usageKind} symbol=$presentable siteRange=${usage.site.textRange}\n    siteText:\n${usage.site.text}\n\n    (No PSI available for symbol)\n\n")
                        } else {
//                            LOG.info(
//                                "kind=${usage.usageKind} symbol=$presentable siteRange=${usage.site.textRange}\n    siteText:\n${usage.site.text}\n\n    Symbol getDebugText():\n${psiSafeSymbol.getDebugText()}\n\n"
//                            )
//                            outputSb.append("\n    kind=${usage.usageKind} symbol=$presentable siteRange=${usage.site.textRange}\n    siteText:\n${usage.site.text}\n\n    Symbol getDebugText():\n${psiSafeSymbol.getDebugText()}\n\n")
                            outputSb.append("\n        ========== kind=${usage.usageKind} symbol=$presentable site.textRange=${usage.site.textRange} ==========\n")
                            outputSb.append("        ========== site.text ==========\n${usage.site.text}\n")
                            outputSb.append("\n        ========== symbol.getDebugText() ==========\n${psiSafeSymbol.getDebugText()}\n\n\n")
                        }
                    }
                    LOG.info(outputSb.toString())
                }
            }

        }
    }
}

/* ============================= DATA MODELS ============================= */

enum class UsageKind {
    CALL,
    PROPERTY_ACCESS_GET,
    PROPERTY_ACCESS_SET,
    TYPE_REFERENCE,
    SUPER_TYPE,
    CONSTRUCTOR_CALL,
    ANNOTATION,
    DELEGATED_PROPERTY,
    OPERATOR_CALL,
    EXTENSION_RECEIVER
}

data class ResolvedUsage(
    val symbol: KaSymbol,
    val pointer: KaSymbolPointer<*>,
    val usageKind: UsageKind,
    val site: PsiElement,
    val call: KaCall? = null
)

/* ============================= COLLECTION LOGIC ============================= */

@OptIn(KaExperimentalApi::class)
fun KaSession.collectResolvedUsagesInDeclaration(
    root: KtDeclaration,
    maxRefs: Int = 2000
): List<ResolvedUsage> {
    val out = ArrayList<ResolvedUsage>(256)

    root.accept(object : KtTreeVisitorVoid() {

        override fun visitCallExpression(expression: KtCallExpression) {
            if (out.size >= maxRefs) return
            handleCallLike(expression, out)
            super.visitCallExpression(expression)
        }

        override fun visitBinaryExpression(expression: KtBinaryExpression) {
            if (out.size >= maxRefs) return
            val op: IElementType = expression.operationToken
            if (op == KtTokens.EQ) {
                val lhs: KtExpression? = expression.left
                if (lhs is KtReferenceExpression) {
                    resolveReferenceReadWrite(lhs, out, preferWrite = true)
                }
            } else {
                handleCallLike(expression, out)
            }
            super.visitBinaryExpression(expression)
        }

        override fun visitUnaryExpression(expression: KtUnaryExpression) {
            if (out.size >= maxRefs) return
            handleCallLike(expression, out)
            super.visitUnaryExpression(expression)
        }

        override fun visitReferenceExpression(expression: KtReferenceExpression) {
            if (out.size >= maxRefs) return
            resolveReferenceReadWrite(expression, out, preferWrite = isAssignmentLhs(expression))
            super.visitReferenceExpression(expression)
        }

        override fun visitTypeReference(typeReference: KtTypeReference) {
            if (out.size >= maxRefs) return
            resolveTypeReference(typeReference, out)
            super.visitTypeReference(typeReference)
        }

        override fun visitSuperTypeListEntry(entry: KtSuperTypeListEntry) {
            if (out.size >= maxRefs) return
            resolveSuperTypeEntry(entry, out)
            super.visitSuperTypeListEntry(entry)
        }

        override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
            if (out.size >= maxRefs) return
            resolveAnnotation(annotationEntry, out)
            super.visitAnnotationEntry(annotationEntry)
        }

        override fun visitProperty(property: KtProperty) {
            if (out.size >= maxRefs) return
            val delegateExpr: KtExpression? = property.delegate?.expression
            if (delegateExpr != null) {
                PsiTreeUtil.processElements(delegateExpr) { e ->
                    if (out.size >= maxRefs) return@processElements false
                    when (e) {
                        is KtCallExpression,
                        is KtUnaryExpression,
                        is KtBinaryExpression -> handleCallLike(e as KtExpression, out, asDelegated = true)
                    }
                    true
                }
            }
            super.visitProperty(property)
        }
    })

    return out
}

/* ============================= HELPERS (KaSession context) ============================= */

private fun KaSession.handleCallLike(
    expression: KtExpression,
    out: MutableList<ResolvedUsage>,
    asDelegated: Boolean = false
) {
    val call: KaCall = (expression.resolveToCall() as? KaSuccessCallInfo)?.call ?: return
    when (call) {
        is KaFunctionCall<*> -> {
            val sym: KaFunctionSymbol = call.symbol
            val ptr: KaSymbolPointer<KaFunctionSymbol> = sym.createPointer()
            var isOperator: Boolean = false
            if (sym is KaNamedFunctionSymbol) {
                isOperator = sym.isOperator
            }
            val usageKind: UsageKind = when {
                isOperator -> UsageKind.OPERATOR_CALL
                asDelegated -> UsageKind.DELEGATED_PROPERTY
                sym is KaConstructorSymbol -> UsageKind.CONSTRUCTOR_CALL
                else -> UsageKind.CALL
            }
            out += ResolvedUsage(
                symbol = sym,
                pointer = ptr,
                usageKind = usageKind,
                site = expression,
                call = call
            )
            recordExtensionReceiverIfAny(call, expression, out)
        }

        is KaVariableAccessCall -> {
            val sym: KaVariableSymbol = call.symbol
            val ptr: KaSymbolPointer<KaVariableSymbol> = sym.createPointer()
            val accessKind: UsageKind = classifyVariableAccess(call)
            out += ResolvedUsage(
                symbol = sym,
                pointer = ptr,
                usageKind = accessKind,
                site = expression,
                call = call
            )
            recordExtensionReceiverIfAny(call, expression, out)
        }

        is KaCompoundArrayAccessCall -> LOG.debug {
            "Unhandled KaCompoundArrayAccessCall in handleCallLike at ${expression.textRange}"
        }

        is KaCompoundVariableAccessCall -> LOG.debug {
            "Unhandled KaCompoundVariableAccessCall in handleCallLike at ${expression.textRange}"
        }
    }
}

private fun KaSession.resolveReferenceReadWrite(
    expr: KtReferenceExpression,
    out: MutableList<ResolvedUsage>,
    preferWrite: Boolean
) {
    // Resolve K2 references, not FE1.0
    val k2Refs: Array<PsiReference> = expr.references
    val syms: List<KaSymbol> = buildList {
        for (psiRef: PsiReference in k2Refs) {
            val k2Ref: KtReference = psiRef as? KtReference ?: continue
            addAll(runCatching { k2Ref.resolveToSymbols() }.getOrElse { emptyList() })
        }
    }
    if (syms.isEmpty()) return

    for (sym: KaSymbol in syms) {
        val ptr: KaSymbolPointer<KaSymbol> = sym.createPointer()
        val usage: UsageKind = when (sym) {
            is KaPropertySymbol -> if (preferWrite && !sym.isVal) UsageKind.PROPERTY_ACCESS_SET else UsageKind.PROPERTY_ACCESS_GET
            is KaVariableSymbol -> if (preferWrite) UsageKind.PROPERTY_ACCESS_SET else UsageKind.PROPERTY_ACCESS_GET
            is KaFunctionSymbol -> UsageKind.CALL
            is KaClassLikeSymbol -> UsageKind.TYPE_REFERENCE
            else -> UsageKind.PROPERTY_ACCESS_GET
        }
        out += ResolvedUsage(symbol = sym, pointer = ptr, usageKind = usage, site = expr)
    }
}

private fun KaSession.resolveTypeReference(
    typeRef: KtTypeReference,
    out: MutableList<ResolvedUsage>
) {
    // Use typeProvider from the session
    val type: KaType = typeRef.type
    val classSymbol: KaClassSymbol = (type as? KaClassType)?.symbol as? KaClassSymbol ?: return
    out += ResolvedUsage(
        symbol = classSymbol,
        pointer = classSymbol.createPointer(),
        usageKind = UsageKind.TYPE_REFERENCE,
        site = typeRef
    )
}

private fun KaSession.resolveSuperTypeEntry(
    entry: KtSuperTypeListEntry,
    out: MutableList<ResolvedUsage>
) {
    val typeRef: KtTypeReference = entry.typeReference ?: return
    // Record the super type classifier
    resolveTypeReference(typeRef, out)

    // If this is a super type call (constructor call), try to record constructor usage
    if (entry is KtSuperTypeCallEntry) {
        val type: KaType = typeRef.type
        val classSymbol: KaClassSymbol = (type as? KaClassType)?.symbol as? KaClassSymbol ?: return
        // Heuristic: if there's a super constructor call syntax, record a constructor call usage
        // (Picking the primary constructor is acceptable here; refine by parameter count if needed)
        val ctorSym: KaConstructorSymbol? = classSymbol.memberScope.constructors.firstOrNull()
        if (ctorSym != null) {
            out += ResolvedUsage(
                symbol = ctorSym,
                pointer = ctorSym.createPointer(),
                usageKind = UsageKind.CONSTRUCTOR_CALL,
                site = entry
            )
        }
    }
}

private fun KaSession.resolveAnnotation(
    annotationEntry: KtAnnotationEntry,
    out: MutableList<ResolvedUsage>
) {
    val typeRef: KtTypeReference = annotationEntry.typeReference ?: return
    val type: KaType = typeRef.type
    val classSymbol: KaClassSymbol = (type as? KaClassType)?.symbol as? KaClassSymbol ?: return

    // Record the annotation class as a type reference
    out += ResolvedUsage(
        symbol = classSymbol,
        pointer = classSymbol.createPointer(),
        usageKind = UsageKind.TYPE_REFERENCE,
        site = typeRef
    )

    // If it has arguments, it effectively calls a constructor
    if (annotationEntry.valueArgumentList != null) {
        val ctor: KaConstructorSymbol? = classSymbol.memberScope.constructors.firstOrNull()
        if (ctor != null) {
            out += ResolvedUsage(
                symbol = ctor,
                pointer = ctor.createPointer(),
                usageKind = UsageKind.CONSTRUCTOR_CALL,
                site = annotationEntry
            )
        }
    }
}

private fun KaSession.recordExtensionReceiverIfAny(
    call: KaCall,
    site: PsiElement,
    out: MutableList<ResolvedUsage>
) {
    val par: KaPartiallyAppliedSymbol<KaCallableSymbol, KaCallableSignature<KaCallableSymbol>> = when (call) {
        is KaFunctionCall<*> -> call.partiallyAppliedSymbol
        is KaVariableAccessCall -> call.partiallyAppliedSymbol
        is KaCompoundArrayAccessCall -> {
            LOG.debug {
                "Unhandled KaCompoundArrayAccessCall in handleCallLike at ${site.textRange}"
            }
            null
        }

        is KaCompoundVariableAccessCall -> {
            LOG.debug {
                "Unhandled KaCompoundVariableAccessCall in handleCallLike at ${site.textRange}"
            }
            null
        }
    } ?: return

    if (par.extensionReceiver == null) return

    val sym: KaCallableSymbol = when (call) {
        is KaFunctionCall<*> -> call.symbol
        is KaVariableAccessCall -> call.symbol
        is KaCompoundArrayAccessCall -> {
            LOG.debug {
                "Unhandled KaCompoundArrayAccessCall in handleCallLike at ${site.textRange}"
            }
            null
        }

        is KaCompoundVariableAccessCall -> {
            LOG.debug {
                "Unhandled KaCompoundVariableAccessCall in handleCallLike at ${site.textRange}"
            }
            null
        }
    } ?: return
    out += ResolvedUsage(
        symbol = sym,
        pointer = sym.createPointer(),
        usageKind = UsageKind.EXTENSION_RECEIVER,
        site = site,
        call = call
    )
}

private fun KaSession.classifyVariableAccess(call: KaVariableAccessCall): UsageKind {
    val setFlag: Boolean? = call.isSetAccessOrNull()
    return if (setFlag == true) UsageKind.PROPERTY_ACCESS_SET else UsageKind.PROPERTY_ACCESS_GET
}

private fun isAssignmentLhs(expr: KtReferenceExpression): Boolean {
    val parent: KtBinaryExpression = expr.parent as? KtBinaryExpression ?: return false
    if (parent.operationToken != KtTokens.EQ) return false
    return parent.left == expr
}

private fun KaVariableAccessCall.isSetAccessOrNull(): Boolean? = try {
    val m: KCallable<*> = this::class.members.firstOrNull { it.name == "isSet" } ?: return null
    (m.call(this) as? Boolean)
} catch (_: Throwable) {
    null
}

private inline fun buildText(body: StringBuilder.() -> Unit): String? {
    val sb = StringBuilder()
    sb.body()
    return sb.toString()
}

private inline fun StringBuilder.appendInn(target: Any?, prefix: String = "", suffix: String = "") {
    if (target == null) return
    append(prefix)
    append(
        when (target) {
            is KtElement -> target.getDebugText()
            else -> target.toString()
        }
    )
    append(suffix)
}