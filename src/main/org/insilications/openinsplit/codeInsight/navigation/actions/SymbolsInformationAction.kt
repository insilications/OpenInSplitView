package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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
        val targetElementUtil = TargetElementUtil.getInstance()
        val flags: Int = targetElementUtil.allAccepted
        val target: PsiElement? = targetElementUtil.findTargetElement(editor, flags, offset)

        if (target == null) {
            LOG.info("No target element at caret")
            return
        }

        LOG.info("Target PSI: ${target::class.qualifiedName}")

        if (target is KtDeclarationWithBody) {
            LOG.info("Target name: ${target.presentation?.presentableText ?: target.name}")
        }

        runWithModalProgressBlocking(project, GETTING_SYMBOL_INFO) {
            if (DumbService.isDumb(project)) {
                LOG.warn("Dumb mode active; aborting semantic resolution.")
                return@runWithModalProgressBlocking
            }

            val decl: KtDeclaration = target as? KtDeclaration ?: return@runWithModalProgressBlocking

            val usages: List<ResolvedUsage> = runReadAction {
                analyze(decl) {
                    collectResolvedUsagesInDeclaration(decl, maxRefs = 2000)
                }
            }

            LOG.info("Resolved usages count: ${usages.size}")
            usages.forEach { usage: ResolvedUsage ->
                val presentable: String = when (val sym = usage.symbol) {
                    is KaNamedSymbol -> sym.name.asString()
                    else -> sym.toString()
                }
                LOG.info(
                    "Usage: kind=${usage.usageKind} symbol=$presentable siteRange=${usage.site.textRange}"
                )
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
    val k2Refs: Array<KtReference> = expr.references as Array<KtReference>
    val syms: List<KaSymbol> = buildList {
        for (psiRef: KtReference in k2Refs) {
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
    val classSymbol = (type as? KaClassType)?.symbol as? KaClassSymbol ?: return
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
        val classSymbol = (type as? KaClassType)?.symbol as? KaClassSymbol ?: return
        // Heuristic: if there's a super constructor call syntax, record a constructor call usage
        // (Picking the primary constructor is acceptable here; refine by parameter count if needed)
        val ctorSym: KaConstructorSymbol? = classSymbol.constructors.firstOrNull()
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
    val classSymbol = (type as? KaClassType)?.symbol as? KaClassSymbol ?: return

    // Record the annotation class as a type reference
    out += ResolvedUsage(
        symbol = classSymbol,
        pointer = classSymbol.createPointer(),
        usageKind = UsageKind.TYPE_REFERENCE,
        site = typeRef
    )

    // If it has arguments, it effectively calls a constructor
    if (annotationEntry.valueArgumentList != null) {
        val ctor: KaConstructorSymbol? = classSymbol.constructors.firstOrNull()
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