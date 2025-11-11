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
import com.intellij.psi.util.PsiTreeUtil
import org.insilications.openinsplit.debug
import org.jetbrains.kotlin.analysis.api.resolution.KaCall
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.refactoring.project
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

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
        val targetElement: PsiElement? = targetElementUtilInstance.findTargetElement(editor, targetElementUtilAllAccepted, offset)

        if (targetElement == null) {
            LOG.info("actionPerformed - targetElement == null")
            return
        }

//            LOG.info("actionPerformed - targetElement: ${targetElement.text}")
        LOG.info("actionPerformed - targetElement::class.simpleName: ${targetElement::class.simpleName}")
        LOG.info("actionPerformed - targetElement::class.qualifiedName: ${targetElement::class.qualifiedName}")

        if (targetElement is KtDeclarationWithBody) {
            val targetElementPresentableText: String? = targetElement.presentation?.presentableText ?: targetElement.name
            LOG.info("actionPerformed - targetElement.name: $targetElementPresentableText")
        }

        runWithModalProgressBlocking(project, GETTING_SYMBOL_INFO) {
            runReadAction {
                if (DumbService.isDumb(project)) {
//                    LOG.warn("Indexing (dumb mode) is active; analysis-powered info will be limited.")
                    LOG.warn("Dumb mode active; K2 analysis resolution is unavailable.")
                    return@runReadAction
                }

                val results: List<ResolvedUsage> = runReadAction {
                    collectResolvedUsagesInDeclaration(targetElement as KtDeclaration, maxRefs = 2000)
                }
            }
        }
    }
}


/**
 * Unified usage kind classification for your downstream DTOs.
 */
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
    val call: KaCall? = null // present for call-like usages
)

/**
 * Traverse a declaration body and collect symbol usages with K2 analysis.
 * Call this INSIDE a single analyze { ... } session.
 *
 * @param root The declaration to analyze (function, class, property, etc.)
 * @param maxRefs Hard cutoff to prevent explosion on large bodies.
 */
fun collectResolvedUsagesInDeclaration(
    root: KtDeclaration,
    maxRefs: Int = 2000
): List<ResolvedUsage> {
    val out = ArrayList<ResolvedUsage>(256)
    if (root !is KtDeclaration) return out

    // Visit the whole declaration subtree. You can narrow to body as needed.
    root.accept(object : KtTreeVisitorVoid() {
        override fun visitCallExpression(expression: KtCallExpression) {
            if (out.size >= maxRefs) return
            handleCallLike(expression, out)
            super.visitCallExpression(expression)
        }

        override fun visitBinaryExpression(expression: KtBinaryExpression) {
            if (out.size >= maxRefs) return
            // Compound assignments and operator calls are resolved as calls.
            // Simple "=" assignment: special-case LHS as property SET.
            val op = expression.operationToken
            if (op == KtTokens.EQ) {
                val lhs = expression.left
                if (lhs is KtReferenceExpression) {
                    resolveReferenceReadWrite(lhs, out, preferWrite = true)
                }
            } else {
                // e.g., +=, -, *, comparisons -> resolve as operator calls
                handleCallLike(expression, out)
            }
            super.visitBinaryExpression(expression)
        }

        override fun visitUnaryExpression(expression: KtUnaryExpression) {
            if (out.size >= maxRefs) return
            // ++, --, unaryMinus, etc. resolve to operator function calls
            handleCallLike(expression, out)
            super.visitUnaryExpression(expression)
        }

        override fun visitQualifiedExpression(expression: KtQualifiedExpression) {
            if (out.size >= maxRefs) return
            // Selector could be a call or reference, both handled downstream.
            super.visitQualifiedExpression(expression)
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
            // Delegated properties: resolve the delegate provider calls
            val delegateExpr = property.delegate?.expression
            if (delegateExpr != null) {
                // The delegate expression can contain calls; resolve them
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

/**
 * Resolve any "call-like" expression (call, unary/binary operator, invoke) via K2.
 * Produces CALL / OPERATOR_CALL, and marks EXTENSION_RECEIVER when present.
 */
private fun handleCallLike(
    expression: KtExpression,
    out: MutableList<ResolvedUsage>,
    asDelegated: Boolean = false
) {
    val call = expression.resolveCall() ?: return
    when (call) {
        is KaFunctionCall<*> -> {
            val sym: KaFunctionSymbol = call.symbol
            val ptr: KaSymbolPointer<KaFunctionSymbol> = sym.createPointer()
            val isOperator: Boolean = (sym as? KaFunctionSymbol)?.isOperator == true
            out += ResolvedUsage(
                symbol = sym,
                pointer = ptr,
                usageKind = if (isOperator) UsageKind.OPERATOR_CALL else if (asDelegated) UsageKind.DELEGATED_PROPERTY else UsageKind.CALL,
                site = expression,
                call = call
            )
            recordExtensionReceiverIfAny(call, expression, out)
            // Constructor calls are also function calls with KaConstructorSymbol
            if (sym is KaConstructorSymbol) {
                out += ResolvedUsage(
                    symbol = sym,
                    pointer = ptr,
                    usageKind = UsageKind.CONSTRUCTOR_CALL,
                    site = expression,
                    call = call
                )
            }
        }

        is KaVariableAccessCall -> {
            val sym = call.symbol
            val ptr = sym.createPointer()
            val accessKind = classifyVariableAccess(call)
            out += ResolvedUsage(
                symbol = sym,
                pointer = ptr,
                usageKind = accessKind,
                site = expression,
                call = call
            )
            recordExtensionReceiverIfAny(call, expression, out)
        }

        is KaErrorType -> {
            // Ignore or log; could add a warning bucket if you like
        }
    }
}

/**
 * Resolve a plain reference expression (no call) to its symbols using K2 and
 * classify as GET or SET depending on LHS-of-assignment heuristics.
 */
private fun resolveReferenceReadWrite(
    expr: KtReferenceExpression,
    out: MutableList<ResolvedUsage>,
    preferWrite: Boolean
) {
    // Prefer K2's resolveToSymbols() to avoid FE1.0 resolution
    val syms = runCatching { expr.resolveToSymbols() }.getOrElse { emptyList() }
    if (syms.isEmpty()) return

    for (sym in syms) {
        val ptr = sym.createPointer()
        val usage = when (sym) {
            is KaPropertySymbol -> if (preferWrite && sym.isVar) UsageKind.PROPERTY_ACCESS_SET else UsageKind.PROPERTY_ACCESS_GET
            is KaVariableSymbol -> if (preferWrite) UsageKind.PROPERTY_ACCESS_SET else UsageKind.PROPERTY_ACCESS_GET
            is KaFunctionSymbol -> UsageKind.CALL
            is KaClassLikeSymbol -> UsageKind.TYPE_REFERENCE
            else -> UsageKind.PROPERTY_ACCESS_GET
        }
        out += ResolvedUsage(symbol = sym, pointer = ptr, usageKind = usage, site = expr)
    }
}

/**
 * Resolve a type reference to its classifier symbol.
 */
private fun resolveTypeReference(
    typeRef: KtTypeReference,
    out: MutableList<ResolvedUsage>
) {
    val type: KaType = typeRef.getType()
    val cls = (type as? KaClassType)?.expandedClassSymbol ?: type.expandedClassSymbol
    val sym = cls ?: return
    out += ResolvedUsage(
        symbol = sym,
        pointer = sym.createPointer(),
        usageKind = UsageKind.TYPE_REFERENCE,
        site = typeRef
    )
}

/**
 * Resolve super type entries (class or constructor call).
 */
private fun resolveSuperTypeEntry(
    entry: KtSuperTypeListEntry,
    out: MutableList<ResolvedUsage>
) {
    val typeRef = entry.typeReference ?: return
    resolveTypeReference(typeRef, out)
    // Constructor call case (SuperTypeCallEntry)
    if (entry is KtSuperTypeCallEntry) {
        handleCallLike(entry, out)
    }
}

/**
 * Resolve annotation usage. Primary: annotation class (TYPE_REFERENCE).
 * Secondary: constructor call (if resolved).
 */
private fun resolveAnnotation(
    annotationEntry: KtAnnotationEntry,
    out: MutableList<ResolvedUsage>
) {
    annotationEntry.typeReference?.let { resolveTypeReference(it, out) }
    // Try resolve constructor call if annotation has arguments (behaves like constructor call)
    val parentCallCandidate: KtElement? =
        annotationEntry.valueArgumentList?.parent as? KtElement ?: annotationEntry
    handleCallLike(parentCallCandidate as KtExpression, out)
}

/**
 * Mark EXTENSION_RECEIVER usage when present on a call.
 */
private fun recordExtensionReceiverIfAny(
    call: KaCall,
    site: PsiElement,
    out: MutableList<ResolvedUsage>
) {
    val par = when (call) {
        is KaFunctionCall -> call.partiallyAppliedSymbol
        is KaVariableAccessCall -> call.partiallyAppliedSymbol
        else -> null
    } ?: return

    val hasExtensionReceiver = par.extensionReceiver != null
    if (!hasExtensionReceiver) return

    val sym = when (call) {
        is KaFunctionCall -> call.symbol
        is KaVariableAccessCall -> call.symbol
        else -> return
    }
    out += ResolvedUsage(
        symbol = sym,
        pointer = sym.createPointer(),
        usageKind = UsageKind.EXTENSION_RECEIVER,
        site = site,
        call = call
    )
}

/**
 * Classify variable access call as GET or SET. Some API versions expose a dedicated access kind;
 * fallback to simple heuristics if not available.
 */
private fun classifyVariableAccess(call: KaVariableAccessCall): UsageKind {
    // Newer K2 exposes direction/kind; if not, rely on the presence of a setter symbol or syntactic context.
    return when {
        call is KaVariableAccessCall && call.isSetAccessOrNull() == true -> UsageKind.PROPERTY_ACCESS_SET
        else -> UsageKind.PROPERTY_ACCESS_GET
    }
}

/**
 * Best effort check when a reference is the LHS of a simple assignment.
 */
private fun isAssignmentLhs(expr: KtReferenceExpression): Boolean {
    val parent = expr.parent as? KtBinaryExpression ?: return false
    if (parent.operationToken != KtTokens.EQ) return false
    return parent.left == expr
}

/**
 * Some K2 versions don’t expose a direct accessor. Try reflect/feature-detect cleanly.
 */
private fun KaVariableAccessCall.isSetAccessOrNull(): Boolean? = try {
    val m = this::class.members.firstOrNull { it.name == "isSet" } ?: return null
    (m.call(this) as? Boolean)
} catch (_: Throwable) {
    null
}