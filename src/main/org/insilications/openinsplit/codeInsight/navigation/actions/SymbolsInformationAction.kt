@file:Suppress("NOTHING_TO_INLINE")

package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.PsiDocumentManager
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
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.idea.refactoring.project
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.tail
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import kotlin.reflect.KCallable

class SymbolsInformationAction : DumbAwareAction() {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
        private const val GETTING_SYMBOL_INFO = "Getting symbol information..."
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
        val psiFile: PsiFile? = CommonDataKeys.PSI_FILE.getData(dataContext)

        if (psiFile == null || editor == null) {
            LOG.debug { "No file or editor in context - File: $psiFile - Editor: $editor" }
            e.presentation.isEnabled = false
            return
        }

        // Collect everything inside a modal read so the user immediately knows the action is busy
        runWithModalProgressBlocking(project, GETTING_SYMBOL_INFO) {
            if (DumbService.isDumb(project)) {
                LOG.warn("Dumb mode active; aborting semantic resolution.")
                return@runWithModalProgressBlocking
            }

            // Resolve the caret target outside of analysis to avoid opening a KaSession without a declaration anchor
            val targetSymbol: PsiElement? = readAction {
                val offset: Int = editor.caretModel.offset

                return@readAction TargetElementUtil.getInstance().findTargetElement(editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED, offset) ?: run {
                    LOG.info("No declaration element found at caret")
                    return@readAction null
                }
            }

            if (targetSymbol == null) {
                return@runWithModalProgressBlocking
            }

            LOG.info("Analyzing...")
            val targetKtDeclaration: KtDeclaration = targetSymbol as? KtDeclaration ?: run {
                LOG.info("Target symbol is not KtDeclaration - Type: ${targetSymbol::class.qualifiedName} - kotlinFqName: ${targetSymbol.kotlinFqName}")
                return@runWithModalProgressBlocking
            }

            readAction {
                analyze(targetKtDeclaration) {
                    // Everything is inside the KaSession context/lifetime; build the payload eagerly
                    val payload: SymbolContextPayload = buildSymbolContext(project, targetKtDeclaration, 5000)
                    deliverSymbolContext(payload)
                }
            }

        }
    }

    private fun deliverSymbolContext(payload: SymbolContextPayload) {
        LOG.info(payload.toLogString())
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

data class CaretLocation(
    val offset: Int,
    val line: Int,
    val column: Int
)

data class DeclarationSlice(
    val sourceCode: String,
    val ktFilePath: String,
    val caret: CaretLocation,
    val qualifiedName: String?,
    val presentableText: String?,
    val ktNamedDeclName: String?,
    val ktFqNameRelativeString: String?,
    val symbolOriginString: String,
    val fqNameTypeString: String
)

data class TargetSymbolContext(
    val packageDirective: String?,
    val imports: List<String>,
    val declarationSlice: DeclarationSlice
)

data class ReferencedSymbolContext(
    val declarationSlice: DeclarationSlice,
    val usageClassifications: List<String>
)

data class SymbolContextPayload(
    val target: TargetSymbolContext,
    val referencedSymbols: List<ReferencedSymbolContext>
)

private data class MutableReferencedSymbolAggregation(
    val declarationSlice: DeclarationSlice,
    val usageKinds: LinkedHashSet<String>
)

/* ============================= COLLECTION LOGIC ============================= */

@OptIn(KaExperimentalApi::class)
fun KaSession.collectResolvedUsagesInDeclaration(
    root: KtDeclaration,
    maxRefs: Int = 2000
): List<ResolvedUsage> {
    val out = ArrayList<ResolvedUsage>(256)

    // PSI visitor keeps discovery simple; we bail once the caller-defined limit is reached to avoid runaway traversals
    root.accept(/* visitor = */ object : KtTreeVisitorVoid() {
        override fun visitKtElement(element: KtElement) {
            LOG.info("Visiting element - type: ${element::class.qualifiedName} - text:\n${element.text}\n\n\n")
            super.visitKtElement(element)
        }

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
                PsiTreeUtil.processElements(delegateExpr) { e: PsiElement ->
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

/* ============================= CONTEXT BUILDERS ============================= */

private fun KaSession.buildSymbolContext(
    project: Project,
    targetKtDeclaration: KtDeclaration,
    maxRefs: Int = 2000
): SymbolContextPayload {
    val targetKtFile: KtFile = targetKtDeclaration.containingKtFile
    val packageDirectiveText: String? = targetKtFile.packageDirective?.text
    val importTexts: List<String> = targetKtFile.importList?.imports?.map { it.text } ?: emptyList()

    val targetSlice: DeclarationSlice = targetKtDeclaration.toDeclarationSlice(project, targetKtDeclaration.symbol.origin)
    val targetContext = TargetSymbolContext(
        packageDirective = packageDirectiveText,
        imports = importTexts,
        declarationSlice = targetSlice
    )

    // Gather all symbol references from the declaration body and then lift them into deduplicated slices
    val usages: List<ResolvedUsage> = collectResolvedUsagesInDeclaration(targetKtDeclaration, maxRefs)
    val referencedSymbols: List<ReferencedSymbolContext> = buildReferencedSymbolContexts(project, usages)

    return SymbolContextPayload(
        target = targetContext,
        referencedSymbols = referencedSymbols
    )
}

private fun KaSession.buildReferencedSymbolContexts(
    project: Project,
    usages: List<ResolvedUsage>
): List<ReferencedSymbolContext> {
    val aggregations = LinkedHashMap<KtDeclaration, MutableReferencedSymbolAggregation>()

    for (usage: ResolvedUsage in usages) {
        val symbol: KaSymbol = usage.symbol

        // Only symbols that resolve back to PSI declarations are interesting for our context payload
        val sourceDeclarationPsi: KtDeclaration = symbol.locateSourceDeclarationPsi() ?: continue
        val declarationSlice: DeclarationSlice = sourceDeclarationPsi.sourceDeclarationToDeclarationSlice(project, symbol.origin)
        val bucket: MutableReferencedSymbolAggregation = aggregations.getOrPut(sourceDeclarationPsi) {
            MutableReferencedSymbolAggregation(
                declarationSlice = declarationSlice,
                usageKinds = LinkedHashSet()
            )
        }
        bucket.usageKinds += usage.usageKind.toClassificationString()
    }

    if (aggregations.isEmpty()) {
        return emptyList()
    }

    // Snapshot the declarations before filtering; we need the full set to identify ancestor relationships without
    // mutating iteration order (the payload order mirrors discovery order, which aids reproducibility).
    val candidateDeclarations: Set<KtDeclaration> = aggregations.keys.toSet()

    return aggregations.entries.asSequence()
        .filter { (declaration: KtDeclaration, _) ->
            // Contexts are stable only when tied to the highest-level container. If the referenced symbol lives inside
            // another declaration that already has a slice (e.g., MyClass.NestedClassMember), ignore the nested
            // one so downstream consumers do not see duplicate snippets from the same logical type.
            !declaration.hasAncestorDeclarationIn(candidateDeclarations)
        }
        .map { (_, aggregation: MutableReferencedSymbolAggregation): MutableMap.MutableEntry<KtDeclaration, MutableReferencedSymbolAggregation> ->
            ReferencedSymbolContext(
                declarationSlice = aggregation.declarationSlice,
                usageClassifications = aggregation.usageKinds.toList()
            )
        }
        .toList()
}

/** Locates the KtDeclaration PSI for this KaSymbol, preferring source declarations over compiled ones.
 * Returns null if no suitable KtDeclaration PSI can be found.
 */
private inline fun KaSymbol.locateSourceDeclarationPsi(): KtDeclaration? {
    val declarationPsi: KtDeclaration = locateDeclarationPsi() ?: return null
    return declarationPsi.preferSourceDeclaration()
}

private inline fun KaSymbol.locateDeclarationPsi(): KtDeclaration? {
    // Only certain origins can be materialized as PSI; others (e.g. synthetic SAM wrappers) do not have stable slices
    if (origin != KaSymbolOrigin.SOURCE && origin != KaSymbolOrigin.JAVA_SOURCE && origin != KaSymbolOrigin.LIBRARY && origin != KaSymbolOrigin.JAVA_LIBRARY) {
        return null
    }
    val sourcePsi: PsiElement = this.psi ?: return null
    val navSourcePsi: PsiElement = sourcePsi.navigationElement
    LOG.debug { "sourcePsi - qualifiedName: ${sourcePsi::class.qualifiedName}" }
    LOG.debug { "navSourcePsi - containingFile: ${navSourcePsi.containingFile.virtualFile.path}" }
    LOG.debug { "navSourcePsi - navSourcePsi.kotlinFqName: ${navSourcePsi.kotlinFqName}" }
    LOG.debug { "navSourcePsi - navSourcePsi::class.java.name: ${navSourcePsi::class.java.name}" }
    LOG.debug { "navSourcePsi - navSourcePsi::class.javaObjectType: ${navSourcePsi::class.javaObjectType}" }
    LOG.debug { "navSourcePsi - navSourcePsi::class.java.declaringClass: ${navSourcePsi::class.java.declaringClass}" }
    LOG.debug { "navSourcePsi - navSourcePsi::class.java: ${navSourcePsi::class.java}" }
    LOG.debug { "navSourcePsi - navSourcePsi::class: ${navSourcePsi::class}" }
    LOG.debug { "navSourcePsi - navSourcePsi::class.java.simpleName: ${navSourcePsi::class.java.simpleName}" }
    LOG.debug { "navSourcePsi - navSourcePsi.javaClass: ${navSourcePsi.javaClass}" }
    LOG.debug { "navSourcePsi - navSourcePsi.javaClass.name: ${navSourcePsi.javaClass.name}" }
    LOG.debug { "navSourcePsi - navSourcePsi.javaClass.canonicalName: ${navSourcePsi.javaClass.canonicalName}" }
    LOG.debug { "navSourcePsi - navSourcePsi.javaClass.simpleName: ${navSourcePsi.javaClass.simpleName}" }
    LOG.debug { "navSourcePsi - navSourcePsi.javaClass.declaringClass: ${navSourcePsi.javaClass.declaringClass}" }
    LOG.debug { "navSourcePsi - navSourcePsi.javaClass.descriptorString(): ${navSourcePsi.javaClass.descriptorString()}" }
    LOG.debug { "navSourcePsi - navSourcePsi::class.javaClass.simpleName: ${(navSourcePsi::class as Any).javaClass.simpleName}" }
    LOG.debug { "navSourcePsi - navSourcePsi::class.javaClass.simpleName: ${(navSourcePsi::class as Any).javaClass.simpleName}" }
    LOG.debug { "navSourcePsi - navSourcePsi.originalElement: ${navSourcePsi.originalElement}" }

    LOG.debug { "navSourcePsi - qualifiedName: ${navSourcePsi::class.qualifiedName} - javaClass.name: ${navSourcePsi.javaClass.name}  - text:\n${navSourcePsi.text}\n\n\n" }
    return when (sourcePsi) {
        is KtDeclaration -> sourcePsi
        else -> sourcePsi.getParentOfType(strict = true)
    }
}

/**
 * If this KtDeclaration is from compiled code, attempt to get the corresponding source KtDeclaration via `navigationElement` instead.
 * If no source declaration is found, returns `this`.
 */
private inline fun KtDeclaration.preferSourceDeclaration(): KtDeclaration {
    if (!containingKtFile.isCompiled) {
        return this
    }

    val sourceDeclaration: KtDeclaration? = when (val navigationElement: PsiElement = this.navigationElement) {
        is KtDeclaration -> navigationElement
        else -> navigationElement.getParentOfType(strict = false)
    }
    return if (sourceDeclaration != null && !sourceDeclaration.containingKtFile.isCompiled) sourceDeclaration else this
}

private fun KtDeclaration.toDeclarationSlice(project: Project, symbolOrigin: KaSymbolOrigin): DeclarationSlice {
    val sourceDeclaration: KtDeclaration = preferSourceDeclaration()
    val ktFile: KtFile = sourceDeclaration.containingKtFile
    val ktFilePath: String = ktFile.virtualFilePath
    val caretLocation: CaretLocation = resolveCaretLocation(project, ktFile as PsiFile, sourceDeclaration.textOffset)
    val kqFqName: FqName? = sourceDeclaration.kotlinFqName
    val qualifiedName: String? = computeQualifiedName(kqFqName)
    val ktFqNameRelativeString: String? = computeRelativektFqName(kqFqName, ktFile.packageFqName)
    val presentableText: String? = sourceDeclaration.computePresentableText()
    val ktNamedDeclName: String? = sourceDeclaration.computeKtNamedDeclName()
    val symbolOriginString: String = when (symbolOrigin) {
        KaSymbolOrigin.SOURCE -> "KaSymbolOrigin.SOURCE"
        KaSymbolOrigin.SOURCE_MEMBER_GENERATED -> "KaSymbolOrigin.SOURCE_MEMBER_GENERATED"
        KaSymbolOrigin.LIBRARY -> "KaSymbolOrigin.LIBRARY"
        KaSymbolOrigin.JAVA_SOURCE -> "KaSymbolOrigin.JAVA_SOURCE"
        KaSymbolOrigin.JAVA_LIBRARY -> "KaSymbolOrigin.JAVA_LIBRARY"
        KaSymbolOrigin.SAM_CONSTRUCTOR -> "KaSymbolOrigin.SAM_CONSTRUCTOR"
        KaSymbolOrigin.TYPEALIASED_CONSTRUCTOR -> "KaSymbolOrigin.TYPEALIASED_CONSTRUCTOR"
        KaSymbolOrigin.INTERSECTION_OVERRIDE -> "KaSymbolOrigin.INTERSECTION_OVERRIDE"
        KaSymbolOrigin.SUBSTITUTION_OVERRIDE -> "KaSymbolOrigin.SUBSTITUTION_OVERRIDE"
        KaSymbolOrigin.DELEGATED -> "KaSymbolOrigin.DELEGATED"
        KaSymbolOrigin.JAVA_SYNTHETIC_PROPERTY -> "KaSymbolOrigin.JAVA_SYNTHETIC_PROPERTY"
        KaSymbolOrigin.PROPERTY_BACKING_FIELD -> "KaSymbolOrigin.PROPERTY_BACKING_FIELD"
        KaSymbolOrigin.PLUGIN -> "KaSymbolOrigin.PLUGIN"
        KaSymbolOrigin.JS_DYNAMIC -> "KaSymbolOrigin.JS_DYNAMIC"
        KaSymbolOrigin.NATIVE_FORWARD_DECLARATION -> "KaSymbolOrigin.NATIVE_FORWARD_DECLARATION"
    }
    val fqNameTypeString: String = sourceDeclaration::class.qualifiedName ?: sourceDeclaration.javaClass.name
    // Pack every attribute that downstream tooling may need to reconstruct a declarative slice
    return DeclarationSlice(
        sourceCode = sourceDeclaration.text,
        ktFilePath = ktFilePath,
        caret = caretLocation,
        qualifiedName = qualifiedName,
        presentableText = presentableText,
        ktNamedDeclName = ktNamedDeclName,
        ktFqNameRelativeString = ktFqNameRelativeString,
        symbolOriginString = symbolOriginString,
        fqNameTypeString = fqNameTypeString
    )
}

private fun KtDeclaration.sourceDeclarationToDeclarationSlice(project: Project, symbolOrigin: KaSymbolOrigin): DeclarationSlice {
    val ktFile: KtFile = containingKtFile
    val ktFilePath: String = ktFile.virtualFilePath
    val caretLocation: CaretLocation = resolveCaretLocation(project, ktFile as PsiFile, textOffset)
    val kqFqName: FqName? = kotlinFqName
    val qualifiedName: String? = computeQualifiedName(kqFqName)
    val ktFqNameRelativeString: String? = computeRelativektFqName(kqFqName, ktFile.packageFqName)
    val presentableText: String? = computePresentableText()
    val ktNamedDeclName: String? = computeKtNamedDeclName()
    val symbolOriginString: String = when (symbolOrigin) {
        KaSymbolOrigin.SOURCE -> "SOURCE"
        KaSymbolOrigin.SOURCE_MEMBER_GENERATED -> "SOURCE_MEMBER_GENERATED"
        KaSymbolOrigin.LIBRARY -> "LIBRARY"
        KaSymbolOrigin.JAVA_SOURCE -> "JAVA_SOURCE"
        KaSymbolOrigin.JAVA_LIBRARY -> "JAVA_LIBRARY"
        KaSymbolOrigin.SAM_CONSTRUCTOR -> "SAM_CONSTRUCTOR"
        KaSymbolOrigin.TYPEALIASED_CONSTRUCTOR -> "TYPEALIASED_CONSTRUCTOR"
        KaSymbolOrigin.INTERSECTION_OVERRIDE -> "INTERSECTION_OVERRIDE"
        KaSymbolOrigin.SUBSTITUTION_OVERRIDE -> "SUBSTITUTION_OVERRIDE"
        KaSymbolOrigin.DELEGATED -> "DELEGATED"
        KaSymbolOrigin.JAVA_SYNTHETIC_PROPERTY -> "JAVA_SYNTHETIC_PROPERTY"
        KaSymbolOrigin.PROPERTY_BACKING_FIELD -> "PROPERTY_BACKING_FIELD"
        KaSymbolOrigin.PLUGIN -> "PLUGIN"
        KaSymbolOrigin.JS_DYNAMIC -> "JS_DYNAMIC"
        KaSymbolOrigin.NATIVE_FORWARD_DECLARATION -> "NATIVE_FORWARD_DECLARATION"
    }
    val fqNameTypeString: String = this::class.qualifiedName ?: this.javaClass.name
    // Pack every attribute that downstream tooling may need to reconstruct a declarative slice
    return DeclarationSlice(
        sourceCode = text,
        ktFilePath = ktFilePath,
        caret = caretLocation,
        qualifiedName = qualifiedName,
        presentableText = presentableText,
        ktNamedDeclName = ktNamedDeclName,
        ktFqNameRelativeString = ktFqNameRelativeString,
        symbolOriginString = symbolOriginString,
        fqNameTypeString = fqNameTypeString
    )
}

/**
 * Returns true if this declaration sits inside another declaration that is already represented in the referenced-symbol
 * payload. The traversal intentionally uses raw PSI parents (instead of KtPsiUtil utilities) because we might be looking
 * at navigation PSI sourced from compiled code, where the tree can swap between light and physical elements.
 */
private fun KtDeclaration.hasAncestorDeclarationIn(candidates: Set<KtDeclaration>): Boolean {
    var ancestor: PsiElement? = parent
    while (ancestor != null) {
        if (ancestor is KtDeclaration && ancestor in candidates) {
            return true
        }
        ancestor = ancestor.parent
    }
    return false
}

/**
 * Computes the relative FqName string of `kqFqName` with respect to the given package `packageFqName` FqName
 * Example: if `kqFqName` is "com.example.MyClass.myMethod" and `packageFqName` is "com.example", the result will be "MyClass.myMethod"
 * If `kqFqName` is null, returns null
 */
private inline fun computeRelativektFqName(kqFqName: FqName?, packageFqName: FqName): String? {
    return kqFqName?.tail(packageFqName)?.asString()
}

private inline fun computeQualifiedName(kqFqName: FqName?): String? {
    return kqFqName?.asString()
}

private inline fun KtDeclaration.computePresentableText(): String? {
    return (this as? KtNamedDeclaration)?.presentation?.presentableText
}

private inline fun KtDeclaration.computeKtNamedDeclName(): String? {
    return (this as? KtNamedDeclaration)?.name
}

private fun resolveCaretLocation(project: Project, psiFile: PsiFile, offset: Int): CaretLocation {
    val document: Document? = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        ?: psiFile.virtualFile?.let { virtualFile: VirtualFile ->
            FileDocumentManager.getInstance().getDocument(virtualFile)
        }

    if (document != null && offset in 0..document.textLength) {
        val lineIndex: Int = document.getLineNumber(offset)
        val columnIndex: Int = offset - document.getLineStartOffset(lineIndex)
        return CaretLocation(
            offset = offset,
            line = lineIndex + 1,
            column = columnIndex + 1
        )
    }

    return CaretLocation(offset = offset, line = -1, column = -1)
}

private inline fun UsageKind.toClassificationString(): String = when (this) {
    UsageKind.CALL -> "call"
    UsageKind.PROPERTY_ACCESS_GET -> "property_access_get"
    UsageKind.PROPERTY_ACCESS_SET -> "property_access_set"
    UsageKind.TYPE_REFERENCE -> "type_reference"
    UsageKind.SUPER_TYPE -> "super_type"
    UsageKind.CONSTRUCTOR_CALL -> "constructor_call"
    UsageKind.ANNOTATION -> "annotation"
    UsageKind.DELEGATED_PROPERTY -> "delegated_property"
    UsageKind.OPERATOR_CALL -> "operator_call"
    UsageKind.EXTENSION_RECEIVER -> "extension_receiver"
}

@Suppress("LongLine")
private fun SymbolContextPayload.toLogString(): String {
    val sb = StringBuilder()
    sb.appendLine()
    sb.appendLine("============ Target PSI Type: ${target.declarationSlice.fqNameTypeString} - Referenced Symbols: ${referencedSymbols.size} ============")
    sb.appendLine("Target ktFilePath: ${target.declarationSlice.ktFilePath}")
    sb.appendLine("Target Qualified Name: ${target.declarationSlice.qualifiedName ?: "<anonymous>"}")
    sb.appendLine("Target symbolOriginString: ${target.declarationSlice.symbolOriginString}")
    sb.appendLine("Target ktFqNameRelativeString: ${target.declarationSlice.ktFqNameRelativeString ?: "<anonymous>"}")
    sb.appendLine("Target presentableText: ${target.declarationSlice.presentableText ?: "<anonymous>"}")
    sb.appendLine("Target ktNamedDeclName: ${target.declarationSlice.ktNamedDeclName ?: "<anonymous>"}")
    sb.appendLine("Target caret: offset=${target.declarationSlice.caret.offset}, line=${target.declarationSlice.caret.line}, column=${target.declarationSlice.caret.column}")
    sb.appendLine("Package: ${target.packageDirective ?: "<none>"}")
    if (target.imports.isNotEmpty()) {
        sb.appendLine("Imports:")
        target.imports.forEach { sb.appendLine("  $it") }
    } else {
        sb.appendLine("Imports: <none>")
    }
    sb.appendLine()
    sb.appendLine("Target Declaration Source Code:")
    sb.appendLine(target.declarationSlice.sourceCode)

    referencedSymbols.forEachIndexed { index: Int, referenced: ReferencedSymbolContext ->
        sb.appendLine()
        sb.appendLine("---- Referenced symbol #${index + 1} - PSI Type: ${referenced.declarationSlice.fqNameTypeString} ----")
        sb.appendLine("ktFilePath: ${referenced.declarationSlice.ktFilePath}")
        sb.appendLine("Qualified Name: ${referenced.declarationSlice.qualifiedName ?: "<anonymous>"}")
        sb.appendLine("symbolOriginString: ${referenced.declarationSlice.symbolOriginString}")
        sb.appendLine("ktFqNameRelativeString: ${referenced.declarationSlice.ktFqNameRelativeString ?: "<anonymous>"}")
//        sb.appendLine("presentableText: ${referenced.declarationSlice.presentableText ?: "<anonymous>"}")
//        sb.appendLine("ktNamedDeclName: ${referenced.declarationSlice.ktNamedDeclName ?: "<anonymous>"}")
//        sb.appendLine("Caret: offset=${referenced.declarationSlice.caret.offset}, line=${referenced.declarationSlice.caret.line}, column=${referenced.declarationSlice.caret.column}")
        sb.appendLine("Usage Classifications: ${referenced.usageClassifications.joinToString()}")
        sb.appendLine("Declaration Source Code:")
        sb.appendLine(referenced.declarationSlice.sourceCode)
    }

    sb.appendLine("==============================================================")
    return sb.toString()
}

/* ============================= HELPERS (KaSession context) ============================= */

/**
 * Resolves any call-like PSI (function call, operator call, delegated call, etc.) and records the
 * corresponding [KaSymbol] alongside the usage site. When the resolved call exposes an extension
 * receiver, the receiver symbol is captured via [recordExtensionReceiverIfAny].
 */
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
            // Record every callable invocation and keep the original call handy for extension-receiver bookkeeping
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
            val accessKind: UsageKind = classifyVariableAccess(call)
            if (shouldRecordResolvedUsage(sym, accessKind)) {
                val ptr: KaSymbolPointer<KaVariableSymbol> = sym.createPointer()
                out += ResolvedUsage(
                    symbol = sym,
                    pointer = ptr,
                    usageKind = accessKind,
                    site = expression,
                    call = call
                )
                recordExtensionReceiverIfAny(call, expression, out)
            }
        }

        is KaCompoundArrayAccessCall -> LOG.debug {
            "Unhandled KaCompoundArrayAccessCall in handleCallLike at ${expression.textRange}"
        }

        is KaCompoundVariableAccessCall -> LOG.debug {
            "Unhandled KaCompoundVariableAccessCall in handleCallLike at ${expression.textRange}"
        }
    }
}

/**
 * Resolves all K2 references that correspond to the supplied [KtReferenceExpression], classifying
 * them as read/write/property/type usages depending on the resolved symbol. Each resolved symbol is
 * appended to [out] with the original PSI site so downstream logic can build enriched slices.
 */
private fun KaSession.resolveReferenceReadWrite(
    expr: KtReferenceExpression,
    out: MutableList<ResolvedUsage>,
    preferWrite: Boolean
) {
    // Resolve K2 references
    val k2Refs: Array<PsiReference> = expr.references
    val syms: List<KaSymbol> = buildList {
        for (psiRef: PsiReference in k2Refs) {
            val k2Ref: KtReference = psiRef as? KtReference ?: continue
            addAll(runCatching<MutableList<KaSymbol>, Collection<KaSymbol>> { k2Ref.resolveToSymbols() }.getOrElse { emptyList() })
        }
    }
    if (syms.isEmpty()) return

    for (sym: KaSymbol in syms) {
        val usage: UsageKind = when (sym) {
            is KaPropertySymbol -> if (preferWrite && !sym.isVal) UsageKind.PROPERTY_ACCESS_SET else UsageKind.PROPERTY_ACCESS_GET
            is KaVariableSymbol -> if (preferWrite) UsageKind.PROPERTY_ACCESS_SET else UsageKind.PROPERTY_ACCESS_GET
            is KaFunctionSymbol -> UsageKind.CALL
            is KaClassLikeSymbol -> UsageKind.TYPE_REFERENCE
            else -> UsageKind.PROPERTY_ACCESS_GET
        }
        if (!shouldRecordResolvedUsage(sym, usage)) continue
        val ptr: KaSymbolPointer<KaSymbol> = sym.createPointer()
        // We store both the resolved symbol and the PSI site for later context reconstruction
        out += ResolvedUsage(symbol = sym, pointer = ptr, usageKind = usage, site = expr)
    }
}

/**
 * Records the classifier symbol for a Kotlin type reference by resolving its [KaType] and grabbing
 * the associated [KaClassSymbol]. Type references are treated as usages even when there is no
 * callable involvement (e.g. annotations, property types, super types).
 */
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

/**
 * Handles entries inside a `super` clause. We log both the classifier usage for the type reference
 * itself and, when the syntax uses a constructor invocation (e.g. `: Base()`), we heuristically
 * record the primary constructor usage to capture call relationships.
 */
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

/**
 * Resolves annotation entries by treating the annotation class as a type usage and, if arguments are
 * present, recording the invoked constructor. This mirrors how annotations are lowered at the
 * compiler level—constructor invocation with potential arguments.
 */
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

/**
 * If the resolved [KaCall] is dispatched through an extension receiver, emit an additional
 * [ResolvedUsage] that classifies the callable symbol as an [UsageKind.EXTENSION_RECEIVER]. This
 * allows the downstream consumer to understand when a symbol participates as an extension vs.
 * regular call.
 */
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
    // Treat extension receivers as separate usages so downstream consumers understand the symbol is being used as an extension
    out += ResolvedUsage(
        symbol = sym,
        pointer = sym.createPointer(),
        usageKind = UsageKind.EXTENSION_RECEIVER,
        site = site,
        call = call
    )
}

/**
 * Filters out low-signal usages that we do not want to surface in the context payload.
 * Currently skips PROPERTY_ACCESS_GET references to Kotlin parameters or properties, which
 * otherwise overwhelm the output with boilerplate like method parameters and LOG fields.
 */
private fun shouldRecordResolvedUsage(symbol: KaSymbol, usageKind: UsageKind): Boolean {
    if (usageKind != UsageKind.PROPERTY_ACCESS_GET) {
        return true
    }
    val psi: PsiElement = symbol.psi ?: return true
    return psi !is KtParameter && psi !is KtProperty && psi !is KtDestructuringDeclarationEntry
}

/**
 * Uses the `isSet` reflection hook (when available) to distinguish property gets from sets so we can
 * label usages precisely. Falls back to `PROPERTY_ACCESS_GET` when setter intent cannot be proven.
 */
private fun KaSession.classifyVariableAccess(call: KaVariableAccessCall): UsageKind {
    val setFlag: Boolean? = call.isSetAccessOrNull()
    return if (setFlag == true) UsageKind.PROPERTY_ACCESS_SET else UsageKind.PROPERTY_ACCESS_GET
}

private fun isAssignmentLhs(expr: KtReferenceExpression): Boolean {
    val parent: KtBinaryExpression = expr.parent as? KtBinaryExpression ?: return false
    if (parent.operationToken != KtTokens.EQ) return false
    return parent.left == expr
}

/**
 * Reflectively calls the analysis API's internal `isSet()` flag, which is not yet part of the
 * stable surface. Returns null when the flag is unavailable or throws, signalling that the caller
 * should treat the usage as an indeterminate access.
 */
private fun KaVariableAccessCall.isSetAccessOrNull(): Boolean? = try {
    val m: KCallable<*> = this::class.members.firstOrNull { it.name == "isSet" } ?: return null
    (m.call(this) as? Boolean)
} catch (_: Throwable) {
    null
}
