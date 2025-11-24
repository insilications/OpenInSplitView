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
import com.intellij.psi.*
import com.intellij.psi.util.PsiUtil
import org.insilications.openinsplit.debug
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.idea.refactoring.project
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.tail
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Action that collects and logs detailed semantic information about the symbol under the caret.
 *
 * It is marked as `DumbAware` so it can be invoked even during indexing, although the core logic
 * checks `DumbService` to avoid expensive or inaccurate resolution when indices are incomplete.
 */
class SymbolsInformationAction : DumbAwareAction() {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
        private const val GETTING_SYMBOL_INFO = "Getting symbol information..."
    }

    // Run on a background thread to avoid freezing the UI during update checks.
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

        // We use `runWithModalProgressBlocking` to show a progress indicator to the user.
        // Semantic analysis can be slow, especially for complex files or large hierarchies.
        runWithModalProgressBlocking(project, GETTING_SYMBOL_INFO) {
            // Early exit if indices are not ready. The Analysis API relies heavily on indices.
            if (DumbService.isDumb(project)) {
                LOG.warn("Dumb mode active; aborting semantic resolution.")
                return@runWithModalProgressBlocking
            }

            // Step 1: Find the target element (symbol) at the caret.
            // We do this in a `readAction` because it accesses the PSI/AST.
            // We do NOT use the Analysis API yet; `TargetElementUtil` is sufficient for finding the declaration.
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

            // Step 2: Build the full context (references, types, etc.) for the found symbol.
            readAction {
                buildSymbolContext(project, targetSymbol)
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

enum class SymbolKind {
    FUNCTION,
    CLASS
}

data class CaretLocation(
    val offset: Int, val line: Int, val column: Int
)

data class DeclarationSlice(
    val psiFilePath: String,
    val caretLocation: CaretLocation,
    val presentableText: String?,
    val name: String?,
    val ktFqNameRelativeString: String?,
    val fqNameTypeString: String,
    val kotlinFqNameString: String?,
    val sourceCode: String
)

data class ReferencedDeclaration(
    val declarationSlice: DeclarationSlice, val usageKinds: Set<UsageKind>
)

data class TargetSymbolContext(
    val packageDirective: String?,
    val importsList: List<String>,
    val declarationSlice: DeclarationSlice,
    val symbolKind: SymbolKind,
    val referencedTypes: List<ReferencedDeclaration>,
    val referencedFunctions: List<ReferencedDeclaration>,
    val referenceLimitReached: Boolean
)

private data class ReferencedCollections(
    val typeSlices: List<ReferencedDeclaration>, val functionSlices: List<ReferencedDeclaration>, val limitReached: Boolean
)

private val SYMBOL_USAGE_LOG: Logger = Logger.getInstance("org.insilications.openinsplit")

/* ============================= CONTEXT BUILDERS ============================= */

/**
 * Orchestrates the gathering of information for the target symbol.
 * It determines the symbol kind, converts the target to a slice, and triggers the recursive reference collection.
 */
private fun buildSymbolContext(
    project: Project, targetSymbol: PsiElement, maxRefs: Int = 5000
) {
    val targetPsiFile: PsiFile = targetSymbol.containingFile
    val packageDirective: String? = getPackageDirective(targetPsiFile)
    val importsList: List<String> = getImportsList(targetPsiFile)

    // Determine if we are looking at a Function or a Class to decide the scope of traversal.
    val symbolKind: SymbolKind = targetSymbol.detectSymbolKind() ?: run {
        LOG.warn("Unsupported target symbol: ${targetSymbol::class.qualifiedName}")
        return
    }

    val targetSlice: DeclarationSlice = targetSymbol.toDeclarationSlice(project)

    // Collect references (types, calls, etc.) used WITHIN the target symbol's scope.
    val referencedCollections: ReferencedCollections = collectReferencedDeclarations(project, targetSymbol, symbolKind, maxRefs)

    val targetContext = TargetSymbolContext(
        packageDirective = packageDirective,
        importsList = importsList,
        declarationSlice = targetSlice,
        symbolKind = symbolKind,
        referencedTypes = referencedCollections.typeSlices,
        referencedFunctions = referencedCollections.functionSlices,
        referenceLimitReached = referencedCollections.limitReached
    )
    LOG.info(targetContext.toLogString())
}

/**
 * Normalize the caret element into a coarse symbol kind so we know how deep the traversal
 * must go (only the function body vs. the entire class hierarchy of declarations).
 * 
 * We check `preferSourceDeclaration` first because we might be at a usage site (reference)
 * and want to analyze the definition.
 */
private fun PsiElement.detectSymbolKind(): SymbolKind? {
    val (sourceDeclaration: PsiElement) = preferSourceDeclaration()
    return when (sourceDeclaration) {
        is KtFunction -> SymbolKind.FUNCTION
        is PsiMethod -> SymbolKind.FUNCTION
        is PsiFunctionalExpression -> SymbolKind.FUNCTION
        is KtClassOrObject -> SymbolKind.CLASS
        is PsiClass -> SymbolKind.CLASS
        else -> null
    }
}

/**
 * Entrypoint for the "semantic slice" aggregation.
 * 
 * It converts the declaration into a UAST root (Universal AST) which allows us to share 
 * the traversal logic between Java and Kotlin.
 * 
 * @param targetSymbol The PSI element to analyze.
 * @param symbolKind The kind of symbol (Class/Function) determining the UAST root type.
 */
private fun collectReferencedDeclarations(
    project: Project, targetSymbol: PsiElement, symbolKind: SymbolKind, maxRefs: Int
): ReferencedCollections {
    // Attempt to convert the PSI element to a UAST element (UDeclaration).
    // If the direct conversion fails (common with some PSI wrappers), we try the navigation element.
    val uRoot: UElement = targetSymbol.toUDeclarationRoot(symbolKind) ?: targetSymbol.navigationElement?.toUDeclarationRoot(symbolKind)
    ?: return ReferencedCollections(emptyList(), emptyList(), limitReached = false)

    val collector = SymbolUsageCollector(project, targetSymbol, maxRefs)
    uRoot.accept(collector)
    return collector.buildResult(project)
}

// Different languages surface different concrete PSI classes; we rely on UAST to provide
// a single node type per concept (UMethod/UClass) and gracefully fall back to lambdas.
private fun PsiElement.toUDeclarationRoot(symbolKind: SymbolKind): UElement? = when (symbolKind) {
    SymbolKind.FUNCTION -> this.toUElementOfType<UMethod>() ?: this.toUElementOfType<ULambdaExpression>()
    SymbolKind.CLASS -> this.toUElementOfType<UClass>()
}

// Walks the target declaration once, recording all referenced types/functions while keeping
// the output deterministic (LinkedHashMap) and bounded (maxRefs guard).
//
// This visitor extends `AbstractUastVisitor`, allowing it to traverse the Universal Abstract Syntax Tree (UAST).
// UAST provides a unified view over Java and Kotlin (and other JVM languages), simplifying the task
// of finding "calls", "type references", etc., without writing separate visitors for each language.
private class SymbolUsageCollector(
    private val project: Project, targetPsi: PsiElement, private val maxRefs: Int
) : AbstractUastVisitor() {

    // We store smart pointers so the underlying PSI can be re-read after the traversal without
    // holding onto invalid elements when the file changes.
    private data class CollectedUsage(
        val pointer: SmartPsiElementPointer<PsiElement>, val usageKinds: MutableSet<UsageKind>
    )

    private val pointerManager: SmartPointerManager = SmartPointerManager.getInstance(project)

    // Normalize the target to its source declaration to ensure consistent comparison.
    private val targetDeclaration: PsiElement = targetPsi.preferSourceDeclaration().first

    // Storage: LinkedHashMap preserves insertion order for deterministic output.
    private val typeUsages: LinkedHashMap<String, CollectedUsage> = LinkedHashMap()
    private val functionUsages: LinkedHashMap<String, CollectedUsage> = LinkedHashMap()
    private var hasReachedLimit: Boolean = false

    /**
     * Finalizes the collection process.
     * 1. Resolves all collected smart pointers.
     * 2. Filters out redundant declarations (nested within others).
     * 3. Converts them to serializable `ReferencedDeclaration` objects.
     */
    fun buildResult(project: Project): ReferencedCollections {
        val resolvedTypes: List<Pair<PsiElement, CollectedUsage>> =
            typeUsages.values.mapNotNull { usage: CollectedUsage -> usage.pointer.element?.let { it to usage } }
        val resolvedFunctions: List<Pair<PsiElement, CollectedUsage>> =
            functionUsages.values.mapNotNull { usage: CollectedUsage -> usage.pointer.element?.let { it to usage } }

        // Filter out any referenced declaration if it is structurally contained within another
        // referenced declaration (or the target itself). This avoids redundant noise in the output.
        // e.g. If we reference class `A` and method `A.foo`, and `A` is already collected, we might suppress `A.foo`
        // depending on the desired granularity. Here, we suppress if the parent is in the set.
        val suppressionSet: Set<PsiElement> = (resolvedTypes.map { it.first } + resolvedFunctions.map { it.first } + targetDeclaration).toSet()

        fun isRedundant(element: PsiElement): Boolean {
            var parent: PsiElement? = element.parent
            while (parent != null) {
                if (parent in suppressionSet) return true
                if (parent is PsiFile) break
                parent = parent.parent
            }
            return false
        }

        val typeSlices: List<ReferencedDeclaration> = resolvedTypes.mapNotNull { (element: PsiElement, usage: CollectedUsage) ->
            if (isRedundant(element)) null else usage.toReferencedDeclaration(project, element)
        }
        val functionSlices: List<ReferencedDeclaration> = resolvedFunctions.mapNotNull { (element: PsiElement, usage: CollectedUsage) ->
            if (isRedundant(element)) null else usage.toReferencedDeclaration(project, element)
        }

        return ReferencedCollections(typeSlices, functionSlices, hasReachedLimit)
    }

    // Re-hydrate a `CollectedUsage` into the serializable payload.
    private fun CollectedUsage.toReferencedDeclaration(project: Project, element: PsiElement): ReferencedDeclaration {
        return ReferencedDeclaration(
            declarationSlice = element.toDeclarationSlice(project), usageKinds = usageKinds.toSet()
        )
    }

    private fun shouldStopTraversal(): Boolean = hasReachedLimit

    override fun visitElement(node: UElement): Boolean {
        if (shouldStopTraversal()) return true
        return super.visitElement(node)
    }

    override fun visitLambdaExpression(node: ULambdaExpression): Boolean {
        if (shouldStopTraversal()) return true

        // Standard UAST traversal (super.visitLambdaExpression) correctly handles:
        // 1. Java lambda parameters (treated as UVariables).
        // 2. Standard Kotlin lambda parameters (treated as UParameters).
        // 3. The body of the lambda for both languages.
        //
        // However, it does NOT traverse Kotlin destructuring declarations in lambda parameters
        // (e.g., `{ (requestor, _) -> ... }`). These are structurally different in the PSI
        // and often opaque to UAST's parameter list view.
        // We must manually inspect the underlying Kotlin PSI to capture types referenced
        // inside these destructuring entries.
        val ktLambda: KtLambdaExpression? = node.sourcePsi as? KtLambdaExpression
        if (ktLambda != null) {
            ktLambda.functionLiteral.valueParameters.forEach { parameter: KtParameter ->
                parameter.destructuringDeclaration?.entries?.forEach { entry: KtDestructuringDeclarationEntry ->
                    entry.typeReference?.let { typeRef: KtTypeReference ->
                        val resolved: PsiElement? = resolveClassifierWithAnalysis(typeRef)
                        recordType(resolved, UsageKind.TYPE_REFERENCE)
                    }
                }
            }
        }

        return super.visitLambdaExpression(node)
    }

    // --- UAST VISITOR METHODS ---
    // These methods are called for each node in the UAST. We inspect the node
    // to see if it represents a reference to a type or a function/method.

    override fun visitMethod(node: UMethod): Boolean {
        if (shouldStopTraversal()) return true
        // Check return type
        val resolved = node.returnType?.let { PsiUtil.resolveClassInType(it) } ?: node.returnTypeReference?.resolvePsiClass()
        ?: node.returnTypeReference?.sourcePsi?.let { resolveClassifierWithAnalysis(it) } // Fallback to Analysis API
        recordType(resolved, UsageKind.TYPE_REFERENCE)
        return super.visitMethod(node)
    }

    override fun visitVariable(node: UVariable): Boolean {
        if (shouldStopTraversal()) return true
        // Check variable type
        val resolved = node.type.let { PsiUtil.resolveClassInType(it) } ?: node.typeReference?.resolvePsiClass()
        ?: node.typeReference?.sourcePsi?.let { resolveClassifierWithAnalysis(it) } // Fallback to Analysis API
        recordType(resolved, UsageKind.TYPE_REFERENCE)
        return super.visitVariable(node)
    }

    override fun visitTypeReferenceExpression(node: UTypeReferenceExpression): Boolean {
        if (shouldStopTraversal()) return true
        val resolved: PsiElement? = node.resolvePsiClass() ?: node.sourcePsi?.let { resolveClassifierWithAnalysis(it) }
        recordType(resolved, UsageKind.TYPE_REFERENCE)
        return super.visitTypeReferenceExpression(node)
    }

    override fun visitClassLiteralExpression(node: UClassLiteralExpression): Boolean {
        if (shouldStopTraversal()) return true
        val resolved: PsiElement? = node.type?.let { PsiUtil.resolveClassInType(it) } ?: node.sourcePsi?.let { resolveClassifierWithAnalysis(it) }
        recordType(resolved, UsageKind.TYPE_REFERENCE)
        return super.visitClassLiteralExpression(node)
    }

    override fun visitSuperExpression(node: USuperExpression): Boolean {
        if (shouldStopTraversal()) return true
        val resolved: PsiElement? = node.resolve() ?: node.sourcePsi?.let { resolveClassifierWithAnalysis(it) }
        recordType(resolved, UsageKind.SUPER_TYPE)
        return super.visitSuperExpression(node)
    }

    override fun visitAnnotation(node: UAnnotation): Boolean {
        if (shouldStopTraversal()) return true
        if (node.sourcePsi == null) return super.visitAnnotation(node)
        val resolved: PsiElement? = node.resolve() ?: node.javaPsi?.nameReferenceElement?.resolve()
        recordType(resolved, UsageKind.ANNOTATION)
        return super.visitAnnotation(node)
    }

    override fun visitCallExpression(node: UCallExpression): Boolean {
        LOG.info("visitCallExpression node.asSourceString(): ${node.asSourceString()}")
        LOG.info("visitCallExpression node.asLogString(): ${node.asLogString()}")
        LOG.info("visitCallExpression node.asRenderString(): ${node.asRenderString()}")
        LOG.info("visitCallExpression node.methodName: ${node.methodName}")
        LOG.info("visitCallExpression node.methodIdentifier?.asRenderString(): ${node.methodIdentifier?.asRenderString()}")

//        val kk = node.methodIdentifier?.


        if (shouldStopTraversal()) return true
        val usageKind: UsageKind = if (node.kind == UastCallKind.CONSTRUCTOR_CALL) {
            UsageKind.CONSTRUCTOR_CALL
        } else {
            UsageKind.CALL
        }

        val resolvedCallable: PsiElement? = node.resolve()

        if (resolvedCallable != null) {
            LOG.info("visitCallExpression resolvedCallable: ${resolvedCallable?.kotlinFqName?.asString()}")
            val fqNameTypeString: String = resolvedCallable::class.qualifiedName ?: resolvedCallable.javaClass.name
            LOG.info("visitCallExpression fqNameTypeString: $fqNameTypeString")
            val ueL = resolvedCallable.toUElement()
            LOG.info("visitCallExpression ueL?.asRenderString(): ${ueL?.asRenderString()}")

        }
        LOG.info("\n\n")
        recordFunction(resolvedCallable, usageKind)

        // If it's a constructor call, we also want to record the Type being instantiated.
        if (node.kind == UastCallKind.CONSTRUCTOR_CALL) {
            val constructorOwner: PsiElement? =
                (resolvedCallable as? PsiMethod)?.containingClass ?: node.classReference?.resolve() ?: node.sourcePsi?.let { resolveClassifierWithAnalysis(it) }
            recordType(constructorOwner, UsageKind.CONSTRUCTOR_CALL)
        }

        return super.visitCallExpression(node)
    }

    override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
        LOG.info("visitSimpleNameReferenceExpression node.getQualifiedName(): ${node.getQualifiedName()}")
        LOG.info("visitSimpleNameReferenceExpression node.asSourceString(): ${node.asSourceString()}")
        LOG.info("visitSimpleNameReferenceExpression node.asLogString(): ${node.asLogString()}")
        LOG.info("visitSimpleNameReferenceExpression node.asRenderString(): ${node.asRenderString()}")


        if (shouldStopTraversal()) return true

        // Avoid double counting calls (which are handled in visitCallExpression)
        if (node.uastParent is UCallExpression) {
            LOG.info("visitSimpleNameReferenceExpression node.uastParent is UCallExpression")
            return super.visitSimpleNameReferenceExpression(node)
        }
        LOG.info("\n\n")
        // Fallback to Analysis API
        val resolved: List<PsiElement?>? = node.resolve()?.let { listOf(it) } ?: node.sourcePsi?.let { resolveReferenceWithAnalysis(it) }
//        val resolved: List<PsiElement?>? = node.resolve() ?: node.sourcePsi?.let { resolveReferenceWithAnalysis(it) }

        for (element: PsiElement? in resolved.orEmpty()) {
            when (element) {
                is PsiMethod -> recordFunction(element, UsageKind.CALL)
                is KtFunction -> recordFunction(element, UsageKind.CALL)
                is PsiClass -> recordType(element, UsageKind.TYPE_REFERENCE)
                is KtClassOrObject -> recordType(element, UsageKind.TYPE_REFERENCE)
            }
        }
        return super.visitSimpleNameReferenceExpression(node)
    }
//    override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
//        if (shouldStopTraversal()) return true
//
//        LOG.info("node.getQualifiedName(): ${node.getQualifiedName()}")
//        // Avoid double counting calls (which are handled in visitCallExpression)
//        if (node.uastParent is UCallExpression) {
//            return super.visitSimpleNameReferenceExpression(node)
//        }
//        val resolved: PsiElement? = node.resolve() ?: node.sourcePsi?.let { resolveReferenceWithAnalysis(it) } // Fallback to Analysis API
//
//        when (resolved) {
//            is PsiMethod -> recordFunction(resolved, UsageKind.CALL)
//            is KtFunction -> recordFunction(resolved, UsageKind.CALL)
//            is PsiClass -> recordType(resolved, UsageKind.TYPE_REFERENCE)
//            is KtClassOrObject -> recordType(resolved, UsageKind.TYPE_REFERENCE)
//        }
//        return super.visitSimpleNameReferenceExpression(node)
//    }

    private fun recordType(element: PsiElement?, usageKind: UsageKind) {
        record(element, usageKind, typeUsages)
    }

    private fun recordFunction(element: PsiElement?, usageKind: UsageKind) {
        record(element, usageKind, functionUsages)
    }

    // Shared bookkeeping for both type/function usage buckets. We dedupe using a stable key,
    // short-circuit when the limit is hit, and avoid reporting the root declaration itself.
    private fun record(
        element: PsiElement?, usageKind: UsageKind, bucket: LinkedHashMap<String, CollectedUsage>
    ) {
        if (element == null || hasReachedLimit) return
        val normalized: PsiElement = element.preferSourceDeclaration().first
        if (normalized.isSameDeclarationAs(targetDeclaration)) return
        val key: String = normalized.computeStableStorageKey() ?: return
        val existing: CollectedUsage? = bucket[key]
        if (existing != null) {
            existing.usageKinds.add(usageKind)
            return
        }
        if (typeUsages.size + functionUsages.size >= maxRefs) {
            hasReachedLimit = true
            return
        }
        bucket[key] = CollectedUsage(
            pointer = pointerManager.createSmartPsiElementPointer(normalized), usageKinds = linkedSetOf(usageKind)
        )
    }
}

// Helper extracted for readability: UAST type reference -> PsiClass when possible.
private fun UTypeReferenceExpression.resolvePsiClass(): PsiElement? = type.let { PsiUtil.resolveClassInType(it) }

private fun PsiElement.computeStableStorageKey(): String? {
    val psiFile: PsiFile? = containingFile
    val virtualFilePath: String? = psiFile?.virtualFile?.path
    val offset: Int? = textRange?.startOffset?.takeIf { it >= 0 } ?: textOffset.takeIf { it >= 0 }
    return when {
        virtualFilePath != null && offset != null -> "$virtualFilePath@$offset"
        virtualFilePath != null -> "$virtualFilePath@${hashCode()}"
        this is KtDeclaration && kotlinFqName != null -> kotlinFqName?.asString()
        else -> hashCode().toString()
    }
}

private fun PsiElement.isSameDeclarationAs(other: PsiElement): Boolean {
    if (this == other) return true
    val thisNav: PsiElement = navigationElement ?: this
    val otherNav: PsiElement = other.navigationElement ?: other
    return thisNav == other || this == otherNav || thisNav == otherNav
}

// =================================================================================================
// KOTLIN ANALYSIS API HELPERS
//
// The following functions bridge the gap between UAST/PSI and the Kotlin Analysis API (K2).
// UAST is excellent for language-agnostic structural traversal, but it sometimes struggles to
// resolve complex Kotlin constructs (like `typealias`, inferred types, or specific constructor calls)
// to their underlying declaration.
//
// We use `analyze(element) { ... }` to enter the Analysis API context (`KaSession`).
// Within this session, we can access semantic information like symbols (`KaSymbol`), types (`KaType`),
// and call resolution results (`KaCall`).
// =================================================================================================

/**
 * Attempts to resolve a Kotlin reference to its target declaration using the Analysis API.
 * This is a fallback for when standard UAST resolution (`node.resolve()`) fails or returns null.
 */
private fun resolveReferenceWithAnalysis(element: PsiElement): List<PsiElement?>? {
    val ktReferenceExpr: KtReferenceExpression = element as? KtReferenceExpression ?: return null
    return ktReferenceExpr.runAnalysisSafely {

        // Resolve K2 references
        val k2References: Array<PsiReference> = ktReferenceExpr.references

        val kaSymbols: List<KaSymbol> = buildList {
            for (psiRef: PsiReference in k2References) {
                val k2Ref: KtReference = psiRef as? KtReference ?: continue
                addAll(runCatching<MutableList<KaSymbol>, Collection<KaSymbol>> { k2Ref.resolveToSymbols() }.getOrElse { emptyList() })
            }
        }
        if (kaSymbols.isEmpty()) return@runAnalysisSafely null

        return@runAnalysisSafely kaSymbols.map { it.psi }

        // `resolveToSymbol()` is a K2 API that resolves the reference to a `KaSymbol`.
        // We then access `.psi` to get back to the underlying PSI element (source declaration).
//        ref.resolveToSymbol()?.psi
    }
}
//private fun resolveReferenceWithAnalysis(element: PsiElement): PsiElement? {
//    val ktElement: KtElement = element as? KtElement ?: return null
//    return ktElement.runAnalysisSafely {
//
//        // Resolve K2 references
//        val k2Refs: Array<PsiReference> = expr.references
//
//        // `KaSession` Context
//        // `mainReference` retrieves the primary reference from the PSI element (e.g., the name in a function call).
//        val ref: KtReference = (ktElement as? KtReferenceExpression)?.mainReference ?: return@runAnalysisSafely null
//
//        // `resolveToSymbol()` is a K2 API that resolves the reference to a `KaSymbol`.
//        // We then access `.psi` to get back to the underlying PSI element (source declaration).
//        ref.resolveToSymbol()?.psi
//    }
//}

/**
 * A more specialized resolver for classifiers (classes, interfaces, objects) and types.
 * It handles cases where UAST might see a "TypeReference" but not know exactly what it points to,
 * especially with type aliases, generics, or constructor calls.
 */
private fun resolveClassifierWithAnalysis(element: PsiElement): PsiElement? {
    val ktElement: KtElement = element as? KtElement ?: return null
    return ktElement.runAnalysisSafely {
        // `KaSession` Context
        when (ktElement) {
            // Case 1: Type References (e.g., `val x: MyType`)
            is KtTypeReference -> {
                // `expandedSymbol` follows type aliases to the final class symbol.
                ktElement.type.expandedSymbol?.psi
            }

            // Case 2: Function/Constructor Calls (e.g., `MyClass()`)
            is KtCallExpression -> {
                // `resolveToCall()` returns a `KaCallInfo`. We check if it's a successful function call.
                val call: KaFunctionCall<*>? = ktElement.resolveToCall()?.successfulCallOrNull<KaFunctionCall<*>>()
                val symbol: KaFunctionSymbol? = call?.symbol

                // If it's a constructor call, we want the class (containing symbol), not the constructor function itself.
                if (symbol is KaConstructorSymbol) {
                    symbol.containingSymbol?.psi
                } else null
            }

            // Case 3: Class Literals (e.g., `MyClass::class`)
            is KtClassLiteralExpression -> {
                val type: KaClassType? = ktElement.expressionType as? KaClassType
                // We unwrap the `KClass<T>` to get `T`, then find its expanded symbol.
                type?.typeArguments?.firstOrNull()?.type?.expandedSymbol?.psi
            }

            // Case 4: Super Types (e.g., `class A : B()`)
            is KtSuperExpression -> {
                ktElement.superTypeQualifier?.type?.expandedSymbol?.psi
            }

            // Case 5: Simple Name References (e.g., `MyClass.SOME_CONSTANT`)
            is KtNameReferenceExpression -> {
                val symbol = ktElement.mainReference.resolveToSymbol()
                // We only care if it resolves to a Class or TypeAlias.
                if (symbol is KaClassSymbol || symbol is KaTypeAliasSymbol) symbol.psi else null
            }

            else -> null
        }
    }
}

/**
 * Safe execution wrapper for the Kotlin Analysis API.
 *
 * `analyze(element)` is the entry point for K2 analysis. It provides a `KaSession` scope.
 * This wrapper handles exceptions that might occur during analysis (e.g., invalidation)
 * to prevent the action from crashing.
 *
 * @param block The analysis logic to run within the `KaSession`.
 */
private inline fun <T> KtElement.runAnalysisSafely(
    crossinline block: KaSession.() -> T?
): T? = try {
    analyze(this) { block() }
} catch (throwable: Throwable) {
    SYMBOL_USAGE_LOG.debug("Analysis failed for ${this::class.qualifiedName}", throwable)
    null
}

private inline fun PsiElement.preferSourceDeclaration(): Pair<PsiElement, PsiFile> {
    val sourceDeclaration: NavigatablePsiElement? = when (val navigationElement: PsiElement = this.navigationElement) {
        is NavigatablePsiElement -> navigationElement
        else -> navigationElement.getParentOfType(strict = false)
    }

    if (sourceDeclaration != null) {
        return sourceDeclaration to sourceDeclaration.containingFile
    }

    return this to this.containingFile
}

inline fun getImportsList(file: PsiFile): List<String> {
    return when (file) {
        // Handle Java files
        is PsiJavaFile -> file.importList?.allImportStatements?.map { it.text } ?: emptyList()

        // Handle Kotlin files
        is KtFile -> file.importList?.imports?.map { it.text } ?: emptyList()

        // Handle other file types or if the cast fails
        else -> emptyList()
    }
}

inline fun getPackageDirective(file: PsiFile): String? {
    return when (file) {
        // Handle Java files
        is PsiJavaFile -> file.packageStatement?.text

        // Handle Kotlin files
        is KtFile -> file.packageDirective?.text

        // Handle other file types or if the cast fails
        else -> null
    }
}

private fun PsiElement.toDeclarationSlice(
    project: Project
): DeclarationSlice {
    val (sourceDeclaration: PsiElement, psiFile: PsiFile) = preferSourceDeclaration()
    val psiFilePath: String = psiFile.virtualFile.path
    val caretLocation: CaretLocation = resolveCaretLocation(project, psiFile, sourceDeclaration.textOffset)
    val kotlinFqName: FqName? = sourceDeclaration.kotlinFqName
    val packageName: String = (containingFile as? PsiClassOwner)?.packageName ?: ""
    val ktFqNameRelativeString: String? = computeRelativeFqName(kotlinFqName, FqName(packageName))
    val presentableText: String? = sourceDeclaration.computePresentableText()
    val name: String? = sourceDeclaration.computeName()
    val fqNameTypeString: String = sourceDeclaration::class.qualifiedName ?: sourceDeclaration.javaClass.name

    val sourceCode: String = try {
        sourceDeclaration.text ?: "<!-- Source code not available (text is null) -->"
    } catch (e: Exception) {
        "<!-- Source code not available (compiled/error: ${e.message}) -->"
    }

    LOG.info("kotlinFqName?.asString(): ${kotlinFqName?.asString()}")
    LOG.info("psiFilePath: $psiFilePath")
    LOG.info("packageName: $packageName")
    LOG.info("ktFqNameRelativeString: $ktFqNameRelativeString")
    LOG.info("presentableText: $presentableText")
    LOG.info("name: $name")
    LOG.info("fqNameTypeString: $fqNameTypeString")
    LOG.info("sourceCode: $sourceCode")
    LOG.info("\n\n")

    // Pack every attribute that downstream tooling may need to reconstruct a declarative slice
    return DeclarationSlice(
        psiFilePath,
        caretLocation,
        presentableText,
        name,
        ktFqNameRelativeString,
        fqNameTypeString,
        kotlinFqNameString = kotlinFqName?.asString(),
        sourceCode = sourceCode,
    )
}

/**
 * Returns true if this declaration sits inside another declaration that is already represented in the referenced-symbol
 * payload. The traversal intentionally uses raw PSI parents (instead of KtPsiUtil utilities) because we might be looking
 * at navigation PSI sourced from compiled code, where the tree can swap between light and physical elements.
 */
private fun PsiElement.hasAncestorDeclarationIn(candidates: Set<PsiElement>): Boolean {
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
 * Computes the relative `FqName` of `kotlinFqName` with respect to the package `packageFqName`
 * Example: if `kotlinFqName` is "com.example.MyClass.myMethod" and `packageFqName` is "com.example", then the result will be "MyClass.myMethod"
 * If `kotlinFqName` is null, returns null
 */
private inline fun computeRelativeFqName(
    kotlinFqName: FqName?, packageFqName: FqName
): String? {
    return kotlinFqName?.tail(packageFqName)?.asString()
}

private inline fun PsiElement.computePresentableText(): String? {
    return (this as? NavigatablePsiElement)?.presentation?.presentableText
}

private inline fun PsiElement.computeName(): String? {
    return (this as? NavigatablePsiElement)?.name
}

private fun resolveCaretLocation(
    project: Project, psiFile: PsiFile, offset: Int
): CaretLocation {
    val document: Document? = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: psiFile.virtualFile?.let { virtualFile: VirtualFile ->
        FileDocumentManager.getInstance().getDocument(virtualFile)
    }

    if (document != null && offset in 0..document.textLength) {
        val lineIndex: Int = document.getLineNumber(offset)
        val columnIndex: Int = offset - document.getLineStartOffset(lineIndex)
        return CaretLocation(
            offset = offset, line = lineIndex + 1, column = columnIndex + 1
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
private fun TargetSymbolContext.toLogString(): String {
    val sb = StringBuilder()
    sb.appendLine()
    sb.appendLine("============ Target PSI Type: ${declarationSlice.fqNameTypeString} ============")
    sb.appendLine("Target kotlinFqNameString: ${declarationSlice.kotlinFqNameString ?: "<anonymous>"}")
    sb.appendLine("Target ktFqNameRelativeString: ${declarationSlice.ktFqNameRelativeString ?: "<anonymous>"}")
    sb.appendLine("Target psiFilePath: ${declarationSlice.psiFilePath}")
    sb.appendLine("Target presentableText: ${declarationSlice.presentableText ?: "<anonymous>"}")
    sb.appendLine("Target name: ${declarationSlice.name ?: "<anonymous>"}")
    sb.appendLine("Target caret: offset=${declarationSlice.caretLocation.offset}, line=${declarationSlice.caretLocation.line}, column=${declarationSlice.caretLocation.column}")
    sb.appendLine("Target Symbol Kind: $symbolKind")
    sb.appendLine("Package: ${packageDirective ?: "<none>"}")
    if (importsList.isNotEmpty()) {
        sb.appendLine("Imports:")
        importsList.forEach { sb.appendLine("  $it") }
    } else {
        sb.appendLine("Imports: <none>")
    }
    sb.appendLine()
    sb.appendLine("Target Declaration Source Code:")
    sb.appendLine(declarationSlice.sourceCode)
    sb.appendLine()
    appendReferencedSection(sb, "Referenced Types", referencedTypes)
    appendReferencedSection(sb, "Referenced Functions", referencedFunctions)
    if (referenceLimitReached) {
        sb.appendLine("Reference limit reached; output truncated.")
    }
    sb.appendLine()
    sb.appendLine("==============================================================")
    return sb.toString()
}

// Pretty-prints referenced declarations in logs while keeping verbosity in check.
private fun TargetSymbolContext.appendReferencedSection(
    sb: StringBuilder, label: String, references: List<ReferencedDeclaration>, maxEntries: Int = 100
) {
    if (references.isEmpty()) {
        sb.appendLine("$label: <none>")
        return
    }

    sb.appendLine("$label (${references.size}):")
    references.take(maxEntries).forEach { ref: ReferencedDeclaration ->
        val usageSummary: String = ref.usageKinds.takeIf { it.isNotEmpty() }?.joinToString { usage -> usage.toClassificationString() } ?: "unknown"
        val displayName: String = ref.declarationSlice.kotlinFqNameString ?: ref.declarationSlice.presentableText ?: ref.declarationSlice.name ?: "<anonymous>"
        sb.appendLine("  - $displayName [$usageSummary]")
        sb.appendLine("  ktFqNameRelativeString: ${ref.declarationSlice.ktFqNameRelativeString}")
        sb.appendLine("  psiFilePath: ${ref.declarationSlice.psiFilePath}")
        sb.appendLine()
    }
    if (references.size > maxEntries) {
        sb.appendLine("  ... (${references.size - maxEntries} more)")
    }
}
