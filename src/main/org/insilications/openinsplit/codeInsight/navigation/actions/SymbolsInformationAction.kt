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
import org.insilications.openinsplit.LogToFile
import org.insilications.openinsplit.debug
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.*
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

private val SYMBOL_USAGE_LOG: Logger = Logger.getInstance("org.insilications.openinsplit")

/**
 * Action that collects and logs detailed semantic information about the symbol under the caret.
 *
 * It is marked as `DumbAware` so it can be invoked even during indexing, although the core logic
 * checks `DumbService` to avoid expensive or inaccurate resolution when indices are incomplete.
 */
class SymbolsInformationAction : DumbAwareAction() {
    companion object {
        private const val GETTING_SYMBOL_INFO = "Getting symbol information..."
    }

    // Run on a background thread to avoid freezing the UI during update checks.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val dataContext: DataContext = e.dataContext
        val project: Project = dataContext.project
        val editor: Editor? = CommonDataKeys.EDITOR.getData(dataContext)
        val psiFile: PsiFile? = CommonDataKeys.PSI_FILE.getData(dataContext)

        if (psiFile == null || editor == null) {
            SYMBOL_USAGE_LOG.debug { "No file or editor in context - File: $psiFile - Editor: $editor" }
            e.presentation.isEnabled = false
            return
        }

        // We use `runWithModalProgressBlocking` to show a progress indicator to the user.
        // Semantic analysis can be slow, especially for complex files or large hierarchies.
        runWithModalProgressBlocking(project, GETTING_SYMBOL_INFO) {
            // Early exit if indices are not ready. The Analysis API relies heavily on indices.
            if (DumbService.isDumb(project)) {
                SYMBOL_USAGE_LOG.warn("Dumb mode active; aborting semantic resolution.")
                return@runWithModalProgressBlocking
            }

            // Step 1: Find the target element (symbol) at the caret.
            // We do this in a `readAction` because it accesses the PSI/AST.
            // We do NOT use the Analysis API yet; `TargetElementUtil` is sufficient for finding the declaration.
            val targetSymbol: PsiElement? = readAction {
                val offset: Int = editor.caretModel.offset

                return@readAction TargetElementUtil.getInstance().findTargetElement(editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED, offset) ?: run {
                    SYMBOL_USAGE_LOG.info("No declaration element found at caret")
                    return@readAction null
                }
            }

            if (targetSymbol == null) {
                return@runWithModalProgressBlocking
            }

            // Step 2: Build the full context (references, types, etc.) for the found symbol.
            readAction {
                buildSymbolContext(project, targetSymbol)
            }
        }
    }
}

/* ============================= DATA MODELS ============================= */

/**
 * Categorizes the specific manner in which a symbol is used within the target's scope.
 * Used during **Data Aggregation** to distinguish between calls, type references, property access, etc.
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
    EXTENSION_RECEIVER,
    EXTENSION_CALL
}

/**
 * Coarse classification of the target symbol (Function vs Class) used to determine
 * the depth and strategy of the AST traversal.
 */
enum class SymbolKind {
    FUNCTION,
    CLASS
}

data class CaretLocation(
    val offset: Int, val line: Int, val column: Int
)

/**
 * Represents a container in the declaration's hierarchy (e.g. a Class, Interface, or Object).
 * Used during the "Tree Merger" reconstruction phase to recreate the nesting structure of the original file.
 *
 * @property signature The declaration header text (e.g. "class MyClass : Base", "companion object").
 *                     This string is used to re-open the scope in the generated summary.
 * @property kind The kind of container ("class", "interface", "object", etc.).
 */
data class StructureNode(
    val signature: String, val kind: String
)

/**
 * A comprehensive snapshot of a declaration, extracted during the **Information Extracted** phase.
 * Contains identity (FQN, names), location, the full source code text, and its structural ancestry.
 *
 * @property structurePath The list of parent containers (from outermost to immediate parent) required to
 *                         reach this declaration. Used by [renderReconstructedFile] to reconstruct the
 *                         file's nesting structure.
 */
data class DeclarationSlice(
    val psiFilePath: String,
    val caretLocation: CaretLocation,
    val presentableText: String?,
    val name: String?,
    val ktFqNameRelativeString: String?,
    val fqNameTypeString: String,
    val kotlinFqNameString: String?,
    val sourceCode: String,
    val structurePath: List<StructureNode>
)

/**
 * Represents a dependency found within the target symbol's AST.
 * Wraps the [DeclarationSlice] of the referenced symbol and the set of ways it was used.
 */
data class ReferencedDeclaration(
    val declarationSlice: DeclarationSlice, val usageKinds: Set<UsageKind>
)

/**
 * Groups collected referenced symbols by their defining source file.
 * This structure supports the "File-Based Grouping" strategy in the Data Aggregation layer,
 * ensuring all symbols are attributed to their *defining* file, not the file where they are used.
 *
 * @property referencedTypes Collected classes, interfaces, objects, and structural types.
 * @property referencedFunctions Collected function calls, constructor calls, and method references.
 */
data class ReferencedFile(
    val packageDirective: String?,
    val importsList: List<String>,
    val referencedTypes: List<ReferencedDeclaration>,
    val referencedFunctions: List<ReferencedDeclaration>,
)

/**
 * The root container for all extracted information about the target symbol.
 *
 * @property declarationSlice The comprehensive snapshot of the target symbol itself (Identity, Location, Context, Content).
 * @property files A map of all files (target + referenced) involved in the context, preserving insertion order.
 */
data class TargetSymbolContext(
    val declarationSlice: DeclarationSlice, val symbolKind: SymbolKind, val files: LinkedHashMap<String, ReferencedFile>, val referenceLimitReached: Boolean
)

private data class ReferencedCollections(
    val referencedFiles: LinkedHashMap<String, ReferencedFile>, val limitReached: Boolean
)

/* ============================= CONTEXT BUILDERS ============================= */

/**
 * Orchestrates the gathering of information for the target symbol.
 * It determines the symbol kind, converts the target to a slice, and triggers the recursive reference collection.
 */
private fun buildSymbolContext(
    project: Project, targetSymbol: PsiElement, maxRefs: Int = 5000
) {
    // Determine if we are looking at a Function or a Class to decide the scope of traversal.
    val symbolKind: SymbolKind = targetSymbol.detectSymbolKind() ?: run {
        SYMBOL_USAGE_LOG.warn("Unsupported target symbol: ${targetSymbol::class.qualifiedName}")
        return
    }

    val targetSlice: DeclarationSlice = targetSymbol.toDeclarationSlice(project)

    // Collect references (types, calls, etc.) used WITHIN the target symbol's scope.
    val referencedCollections: ReferencedCollections = collectReferencedDeclarations(project, targetSymbol, symbolKind, maxRefs)

    // Merge Target File into the Referenced Files map
    // We want the target file to be the FIRST entry in the map for clarity.
    val mergedFiles = LinkedHashMap<String, ReferencedFile>()
    val targetPath: String = targetSlice.psiFilePath
    val existingTargetFile: ReferencedFile? = referencedCollections.referencedFiles[targetPath]

    val isTargetType: Boolean = symbolKind == SymbolKind.CLASS
    // Wrap target slice. We use empty usage kinds as it's the definition, not a usage.
    val targetRefDecl = ReferencedDeclaration(targetSlice, emptySet())

    val targetFileEntry: ReferencedFile = if (existingTargetFile != null) {
        ReferencedFile(
            packageDirective = existingTargetFile.packageDirective,
            importsList = existingTargetFile.importsList,
            referencedTypes = if (isTargetType) existingTargetFile.referencedTypes + targetRefDecl else existingTargetFile.referencedTypes,
            referencedFunctions = if (!isTargetType) existingTargetFile.referencedFunctions + targetRefDecl else existingTargetFile.referencedFunctions
        )
    } else {
        val targetPsiFile: PsiFile = targetSymbol.containingFile
        ReferencedFile(
            packageDirective = getPackageDirective(targetPsiFile),
            importsList = getImportsList(targetPsiFile),
            referencedTypes = if (isTargetType) listOf(targetRefDecl) else emptyList(),
            referencedFunctions = if (!isTargetType) listOf(targetRefDecl) else emptyList()
        )
    }

    // 1. Add Target File first
    mergedFiles[targetPath] = targetFileEntry

    // 2. Add remaining referenced files
    for ((path, file) in referencedCollections.referencedFiles) {
        if (path != targetPath) {
            mergedFiles[path] = file
        }
    }

    val targetContext = TargetSymbolContext(
        declarationSlice = targetSlice, symbolKind = symbolKind, files = mergedFiles, referenceLimitReached = referencedCollections.limitReached
    )
    LogToFile.info(targetContext.toLogString())
}

/**
 * Normalize the caret element into a coarse symbol kind to determine the **Traversal Scope**.
 * This is a crucial architectural decision affecting performance and depth:
 * - **FUNCTION:** Limits traversal to the function body.
 * - **CLASS:** Traverses the entire class hierarchy and member declarations.
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
    val uRoot: UElement =
        targetSymbol.toUDeclarationRoot(symbolKind) ?: targetSymbol.navigationElement?.toUDeclarationRoot(symbolKind) ?: return ReferencedCollections(
            LinkedHashMap(), limitReached = false
        )

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

/**
 * **Traversal Layer (UAST):**
 *
 * Walks the target declaration's AST once, recording all referenced types and functions while keeping
 * the output deterministic (LinkedHashMap) and bounded (maxRefs guard).
 *
 * This visitor implements the **Hybrid Resolution Strategy**:
 * 1.  **Unified Visitor:** Extends `AbstractUastVisitor` to handle standard nodes (`visitMethod`, `visitCallExpression`)
 *     uniformly across Java and Kotlin.
 * 2.  **Manual Overrides:** Implements explicit checks (e.g., in `visitLambdaExpression`) for constructs where
 *     UAST is too opaque (like Kotlin destructuring declarations).
 * 3.  **Split Resolution:** Decides the semantic resolution strategy based on the source language:
 *     - Uses **K2 Analysis API** (`analyze` session) for Kotlin elements.
 *     - Uses standard **PSI/UAST resolution** for Java elements.
 */
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
     * 3. Groups them by source file and converts them to serializable `ReferencedFile` objects.
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

        class FileBuilder(val psiFile: PsiFile) {
            val types: MutableList<ReferencedDeclaration> = mutableListOf()
            val functions: MutableList<ReferencedDeclaration> = mutableListOf()
        }

        val fileMap = LinkedHashMap<String, FileBuilder>()

        fun process(list: List<Pair<PsiElement, CollectedUsage>>, isType: Boolean) {
            for ((element: PsiElement, usage: CollectedUsage) in list) {
                if (isRedundant(element)) continue
                val psiFile: PsiFile = element.containingFile ?: continue
                val path: String = psiFile.virtualFile?.path ?: continue

                val builder: FileBuilder = fileMap.getOrPut(path) { FileBuilder(psiFile) }
                val declaration: ReferencedDeclaration = usage.toReferencedDeclaration(project, element)

                if (isType) builder.types.add(declaration) else builder.functions.add(declaration)
            }
        }

        process(resolvedTypes, isType = true)
        process(resolvedFunctions, isType = false)

        val referencedFiles = LinkedHashMap<String, ReferencedFile>()
        for ((path: String, builder: FileBuilder) in fileMap) {
            referencedFiles[path] = ReferencedFile(
                packageDirective = getPackageDirective(builder.psiFile),
                importsList = getImportsList(builder.psiFile),
                referencedTypes = builder.types,
                referencedFunctions = builder.functions
            )
        }

        return ReferencedCollections(referencedFiles, hasReachedLimit)
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
        // **Manual Override (Traversal Layer):**
        // UAST does NOT traverse Kotlin destructuring declarations in lambda parameters
        // (e.g., `{ (requestor, _) -> ... }`). These are structurally different in the PSI
        // and often opaque to UAST's parameter list view.
        //
        // **Constraint - UAST Limitations:**
        // This is a concrete example where manual PSI inspection is mandatory. We must manually
        // inspect the underlying `KtParameter` and `KtDestructuringDeclarationEntry` to capture
        // types referenced inside.
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

        val sourcePsi: PsiElement? = node.sourcePsi

        // **Extension Receiver (Kotlin):**
        // Explicitly extract the receiver type (e.g., `fun String.foo()`). This is a "Structural Type"
        // dependency that UAST might miss or miscategorize, so we use K2 to resolve the expanded symbol.
        if (sourcePsi is KtFunction) {
            sourcePsi.runAnalysisSafely {
                (sourcePsi.symbol as? KaFunctionSymbol)?.receiverParameter?.returnType?.expandedSymbol?.psi
            }?.let { recordType(it, UsageKind.EXTENSION_RECEIVER) }
        }

        // Check return type
        val resolved: PsiElement? = if (sourcePsi is KtDeclaration) {
            sourcePsi.runAnalysisSafely {
                (sourcePsi.symbol as? KaFunctionSymbol)?.returnType?.expandedSymbol?.psi
            }
        } else {
            node.returnType?.let { PsiUtil.resolveClassInType(it) } ?: node.returnTypeReference?.resolvePsiClass()
        }

        // **Implicit Type Filter:**
        // Explicitly filter out implicit `kotlin.Unit` return types.
        // This significantly reduces noise in the collected data, as `Unit` is ubiquitous, often inferred,
        // and rarely provides semantic value for this type of analysis.
        val isImplicitUnit: Boolean =
            resolved is KtClassOrObject && resolved.fqName?.asString() == "kotlin.Unit" && (sourcePsi is KtFunction && !sourcePsi.hasDeclaredReturnType())

        if (!isImplicitUnit) {
            recordType(resolved, UsageKind.TYPE_REFERENCE)
        }
        return super.visitMethod(node)
    }

    override fun visitVariable(node: UVariable): Boolean {
        if (shouldStopTraversal()) return true

        val sourcePsi: PsiElement? = node.sourcePsi

        if (sourcePsi is KtProperty) {
            // Extension Receiver
            // Explicitly extract the receiver type for properties (e.g., `val String.lastChar: Char`).
            // This is a "Structural Type" dependency that requires K2 resolution.
            sourcePsi.runAnalysisSafely {
                sourcePsi.symbol.receiverParameter?.returnType?.expandedSymbol?.psi
            }?.let { recordType(it, UsageKind.EXTENSION_RECEIVER) }

            // Delegated Property
            if (sourcePsi.hasDelegate()) {
                sourcePsi.runAnalysisSafely {
                    sourcePsi.delegate?.expression?.resolveToCall()?.let { call: KaCallInfo ->
                        call.successfulFunctionCallOrNull()?.symbol ?: call.successfulConstructorCallOrNull()?.symbol
                    }?.psi
                }?.let { recordFunction(it, UsageKind.DELEGATED_PROPERTY) }
            }
        }

        // Check variable type
        val resolved: PsiElement? = if (sourcePsi is KtDeclaration) {
            sourcePsi.runAnalysisSafely {
                (sourcePsi.symbol as? KaVariableSymbol)?.returnType?.expandedSymbol?.psi
            }
        } else {
            node.type.let { PsiUtil.resolveClassInType(it) } ?: node.typeReference?.resolvePsiClass()
        }
        recordType(resolved, UsageKind.TYPE_REFERENCE)
        return super.visitVariable(node)
    }

    override fun visitTypeReferenceExpression(node: UTypeReferenceExpression): Boolean {
        if (shouldStopTraversal()) return true
        val sourcePsi: PsiElement? = node.sourcePsi
        val resolved: PsiElement? = if (sourcePsi is KtElement) {
            resolveClassifierWithAnalysis(sourcePsi)
        } else {
            node.resolvePsiClass()
        }
        recordType(resolved, UsageKind.TYPE_REFERENCE)
        return super.visitTypeReferenceExpression(node)
    }

    override fun visitClassLiteralExpression(node: UClassLiteralExpression): Boolean {
        if (shouldStopTraversal()) return true

        val sourcePsi: PsiElement? = node.sourcePsi
        val resolved: PsiElement? = if (sourcePsi is KtElement) {
            resolveClassifierWithAnalysis(sourcePsi)
        } else {
            node.type?.let { PsiUtil.resolveClassInType(it) }
        }
        recordType(resolved, UsageKind.TYPE_REFERENCE)
        return super.visitClassLiteralExpression(node)
    }

    override fun visitSuperExpression(node: USuperExpression): Boolean {
        if (shouldStopTraversal()) return true

        val sourcePsi: PsiElement? = node.sourcePsi
        val resolved: PsiElement? = if (sourcePsi is KtElement) {
            resolveClassifierWithAnalysis(sourcePsi)
        } else {
            node.resolve()
        }
        recordType(resolved, UsageKind.SUPER_TYPE)
        return super.visitSuperExpression(node)
    }

    override fun visitAnnotation(node: UAnnotation): Boolean {
        if (shouldStopTraversal()) return true

        // 1. Check for purely synthetic elements (no source at all)
        val sourcePsi: PsiElement = node.sourcePsi ?: return true

        val resolved: PsiElement? = if (sourcePsi is KtElement) {
            // 2. Check for "Anchored" synthetic elements
            // If it's Kotlin, it MUST be a `KtAnnotationEntry`
            // If UAST points to a `KtParameter` or `KtFunction`, it's a synthetic descriptor annotation
            if (sourcePsi !is KtAnnotationEntry) {
                return true
            }
            resolveClassifierWithAnalysis(sourcePsi)
        } else {
            node.resolve() ?: node.javaPsi?.nameReferenceElement?.resolve()
        }
        recordType(resolved, UsageKind.ANNOTATION)
        return super.visitAnnotation(node)
    }

    override fun visitCallExpression(node: UCallExpression): Boolean {
        if (shouldStopTraversal()) return true

        val initialKind: UsageKind = if (node.kind == UastCallKind.CONSTRUCTOR_CALL) {
            UsageKind.CONSTRUCTOR_CALL
        } else {
            UsageKind.CALL
        }

        val sourcePsi: PsiElement? = node.sourcePsi
        // **Semantic Resolution Layer (Hybrid Strategy):**
        // Base the strategy on the CALL SITE language with a type check.
        val (resolvedCallable: PsiElement?, finalKind: UsageKind) = if (sourcePsi is KtElement) {
            // [STRATEGY: K2 ANALYSIS]
            // === K2 PATH (Kotlin) ===
            // **CRITICAL WARNING:** Standard UAST resolution (`node.resolve()`) is **never trusted** for Kotlin.
            // It often returns "Light Classes" (Java-views) that lose Kotlin-specific semantics (suspend, inline, aliases).
            //
            // We use `analyze(element)` to enter the K2 session and `resolveToCall()` for high-fidelity resolution.
            sourcePsi.runAnalysisSafely {
                // Resolve the call using K2 semantics
                val callInfo: KaCallInfo? = sourcePsi.resolveToCall()

                val functionCall: KaFunctionCall<out KaFunctionSymbol>? = callInfo?.successfulFunctionCallOrNull() ?: callInfo?.singleFunctionCallOrNull()
                val constructorCall: KaFunctionCall<KaConstructorSymbol>? =
                    callInfo?.successfulConstructorCallOrNull() ?: callInfo?.singleConstructorCallOrNull()

                // Get the target symbol. We hierarchically check for successful calls to ensure precision.
                // `successfulFunctionCallOrNull` ensures we match a valid function signature.
                // `successfulConstructorCallOrNull` handles object instantiation.
                val symbol: KaFunctionSymbol? = functionCall?.symbol ?: constructorCall?.symbol

                val kind: UsageKind = if (symbol != null && symbol.isExtension) UsageKind.EXTENSION_CALL else initialKind
                symbol?.psi to kind
            } ?: (null to initialKind)
        } else {
            // === JAVA/UAST PATH ===
            // For Java files, standard UAST `node.resolve()` works correctly and returns the real `PsiMethod`.
            node.resolve() to initialKind
        }

        recordFunction(resolvedCallable, finalKind)

        // If it's a constructor call, we also want to record the Type being instantiated.
        if (node.kind == UastCallKind.CONSTRUCTOR_CALL) {
            val constructorOwner: PsiElement? = when (resolvedCallable) {
                is PsiMethod -> resolvedCallable.containingClass
                is KtConstructor<*> -> resolvedCallable.getContainingClassOrObject()
                is KtClassOrObject -> resolvedCallable
                else -> null
            } ?: node.classReference?.resolve() ?: node.sourcePsi?.let { resolveClassifierWithAnalysis(it) }
            recordType(constructorOwner, UsageKind.CONSTRUCTOR_CALL)
        }

        return super.visitCallExpression(node)
    }

    override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
        if (shouldStopTraversal()) return true

        // Avoid double counting calls (which are handled in visitCallExpression)
        if (node.uastParent is UCallExpression) {
            return super.visitSimpleNameReferenceExpression(node)
        }

        val sourcePsi: PsiElement? = node.sourcePsi
        val resolved: List<PsiElement?>? = if (sourcePsi is KtElement) {
            // [STRATEGY: K2 ANALYSIS]
            resolveReferenceWithAnalysis(sourcePsi)
        } else {
            node.resolve()?.let { listOf(it) }
        }

        for (element: PsiElement? in resolved.orEmpty()) {
            when (element) {
                is PsiMethod -> recordFunction(element, UsageKind.CALL)
                is KtFunction -> recordFunction(element, UsageKind.CALL)
                is PsiClass -> recordType(element, UsageKind.TYPE_REFERENCE)
                is KtClassOrObject -> recordType(element, UsageKind.TYPE_REFERENCE)
                is PsiField -> {
                    val typeClass: PsiClass? = PsiUtil.resolveClassInType(element.type)
                    recordType(typeClass, UsageKind.TYPE_REFERENCE)
                    val kind: UsageKind = if (isLValue(node)) UsageKind.PROPERTY_ACCESS_SET else UsageKind.PROPERTY_ACCESS_GET
                    recordFunction(element, kind)
                }

                is KtProperty -> {
                    val typeClass: PsiElement? = element.runAnalysisSafely {
                        element.symbol.returnType.expandedSymbol?.psi
                    }
                    recordType(typeClass, UsageKind.TYPE_REFERENCE)
                    val kind: UsageKind = if (isLValue(node)) UsageKind.PROPERTY_ACCESS_SET else UsageKind.PROPERTY_ACCESS_GET
                    recordFunction(element, kind)
                }

                is KtParameter -> {
                    if (element.hasValOrVar()) {
                        val typeClass: PsiElement? = element.runAnalysisSafely {
                            element.symbol.returnType.expandedSymbol?.psi
                        }
                        recordType(typeClass, UsageKind.TYPE_REFERENCE)
                        val kind: UsageKind = if (isLValue(node)) UsageKind.PROPERTY_ACCESS_SET else UsageKind.PROPERTY_ACCESS_GET
                        recordFunction(element, kind)
                    }
                }
            }
        }
        return super.visitSimpleNameReferenceExpression(node)
    }

    override fun visitBinaryExpression(node: UBinaryExpression): Boolean {
        if (shouldStopTraversal()) return true
        val sourcePsi: PsiElement? = node.sourcePsi
        val resolved: PsiElement? = if (sourcePsi is KtElement) {
            // [STRATEGY: K2 ANALYSIS]
            sourcePsi.runAnalysisSafely {
                sourcePsi.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.psi
            }
        } else {
            node.resolveOperator()
        }
        recordFunction(resolved, UsageKind.OPERATOR_CALL)
        return super.visitBinaryExpression(node)
    }

    override fun visitUnaryExpression(node: UUnaryExpression): Boolean {
        if (shouldStopTraversal()) return true
        val sourcePsi: PsiElement? = node.sourcePsi
        val resolved: PsiElement? = if (sourcePsi is KtElement) {
            // [STRATEGY: K2 ANALYSIS]
            sourcePsi.runAnalysisSafely {
                sourcePsi.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.psi
            }
        } else {
            node.resolveOperator()
        }
        recordFunction(resolved, UsageKind.OPERATOR_CALL)
        return super.visitUnaryExpression(node)
    }

    override fun visitArrayAccessExpression(node: UArrayAccessExpression): Boolean {
        if (shouldStopTraversal()) return true
        val sourcePsi: PsiElement? = node.sourcePsi
        if (sourcePsi is KtElement) {
            // [STRATEGY: K2 ANALYSIS]
            val resolved: PsiElement? = sourcePsi.runAnalysisSafely {
                sourcePsi.resolveToCall()?.successfulFunctionCallOrNull()?.symbol?.psi
            }
            recordFunction(resolved, UsageKind.OPERATOR_CALL)
        }
        return super.visitArrayAccessExpression(node)
    }

    private fun isLValue(node: USimpleNameReferenceExpression): Boolean {
        val parent: UElement? = node.uastParent
        if (parent is UBinaryExpression && parent.leftOperand == node) {
            val op: UastBinaryOperator = parent.operator
            if (op == UastBinaryOperator.ASSIGN) return true
        }
        return false
    }

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
 * Attempts to resolve a Kotlin reference to its target referenced declarations using the Analysis API
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
    }
}

/**
 * A more specialized resolver for classifiers (classes, interfaces, objects) and types using the **K2 Analysis API**.
 *
 * **Why is this needed?**
 * UAST is often unable to resolve complex Kotlin constructs to their canonical declarations.
 * This function addresses specific limitations:
 * 1.  **Type Aliases:** UAST might return the alias itself; we use `expandedSymbol` to find the underlying class.
 * 2.  **Class Literals:** UAST sees `KClass<T>`; we need to unwrap `T`.
 * 3.  **Constructors:** UAST might not link the constructor call back to the containing class reliably in all cases.
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
                val callInfo: KaCallInfo? = ktElement.resolveToCall()
                val symbol: KaFunctionSymbol? = callInfo?.successfulFunctionCallOrNull()?.symbol ?: callInfo?.successfulConstructorCallOrNull()?.symbol

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
                val symbol: KaSymbol? = ktElement.mainReference.resolveToSymbol()
                // We only care if it resolves to a Class or TypeAlias.
                if (symbol is KaClassSymbol || symbol is KaTypeAliasSymbol) symbol.psi else null
            }

            // Case 6: Annotations (e.g., `@MyAnnotation`)
            is KtAnnotationEntry -> {
                // Annotations are constructor calls.
                val callInfo: KaCallInfo? = ktElement.resolveToCall()
                val symbol: KaConstructorSymbol? = callInfo?.successfulConstructorCallOrNull()?.symbol ?: callInfo?.singleConstructorCallOrNull()?.symbol
                symbol?.containingSymbol?.psi
            }

            else -> null
        }
    }
}

/**
 * Safe execution wrapper for the Kotlin Analysis API.
 *
 * **Primary Gatekeeper:** This function is the single entry point for K2 analysis (`analyze(element)`).
 * It provides the required `KaSession` scope for all semantic operations.
 *
 * **Safety:** It catches generic `Throwable` to handle potential Analysis API instability or PSI invalidation
 * (e.g. `PsiInvalidElementAccessException`), ensuring the action fails gracefully (returning null)
 * rather than crashing the IDE.
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

    val structurePath: List<StructureNode> = sourceDeclaration.computeStructurePath()

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
        structurePath = structurePath
    )
}

/**
 * Computes the structural ancestry of this PSI element.
 *
 * It traverses up the PSI tree from the current element to the file root, collecting "Container" nodes
 * (Classes, Objects, Interfaces) along the way.
 *
 * @return A list of [StructureNode]s ordered from **Outermost** (root) to **Innermost** (immediate parent).
 *         This order is critical for the top-down reconstruction in [renderReconstructedFile].
 */
private fun PsiElement.computeStructurePath(): List<StructureNode> {
    val path: MutableList<StructureNode> = mutableListOf<StructureNode>()
    var current: PsiElement? = this.parent
    while (current != null) {
        // Stop when we hit the file level; we only care about declarations inside the file.
        if (current is PsiFile) break

        val node: StructureNode? = when (current) {
            is KtClassOrObject -> {
                val header: String = getContainerHeader(current)
                val kind: String = if (current is KtObjectDeclaration) "object" else "class"
                StructureNode(header, kind)
            }

            is PsiClass -> {
                val header: String = getContainerHeader(current)
                val kind: String = if (current.isInterface) "interface" else "class"
                StructureNode(header, kind)
            }

            else -> null
        }

        if (node != null) {
            path.add(node)
        }
        current = current.parent
    }
    // We traversed Inside -> Out (Parent -> Grandparent), so reverse to get Out -> In order (Grandparent -> Parent)
    return path.reversed()
}

/**
 * Extracts the "Header" or "Signature" of a container element.
 *
 * The header is defined as the text of the declaration **up to** the opening brace of its body.
 * - Example: `class MyClass : Base { ... }` -> `class MyClass : Base`
 * - Example: `companion object { ... }` -> `companion object`
 *
 * This allows us to reconstruct the container's shell without including its original content.
 */
private fun getContainerHeader(element: PsiElement): String {
    val text: String = element.text
    val bodyStartOffset: Int = when (element) {
        is KtClassOrObject -> element.body?.startOffsetInParent ?: -1
        is PsiClass -> element.lBrace?.startOffsetInParent ?: -1
        else -> -1
    }

    return if (bodyStartOffset > 0) {
        text.substring(0, bodyStartOffset).trim()
    } else {
        // Fallback for cases without a body (e.g. abstract methods, interfaces without braces, or parsing errors)
        // We take everything before the first '{'
        text.substringBefore('{').trim()
    }
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
    UsageKind.EXTENSION_CALL -> "extension_call"
}

//private fun TargetSymbolContext.toLogString(): String {
//    val sb = StringBuilder(96708)
//    if (!files.isEmpty()) {
//        val targetFile: ReferencedFile? = files[declarationSlice.psiFilePath]
//        if (targetFile != null) {
//            // Collect all slices for this file (Types + Functions)
//            val allSlices: List<DeclarationSlice> =
//                targetFile.referencedTypes.map { it.declarationSlice } + targetFile.referencedFunctions.map { it.declarationSlice }
//
//            val reconstructedContent: String = renderReconstructedFile(
//                targetFile.packageDirective, targetFile.importsList, allSlices
//            )
//
//            sb.append(reconstructedContent)
//        }
//    }
//    return sb.toString()
//}

private fun TargetSymbolContext.toLogString(): String {
    val sb = StringBuilder(96708)

    if (!files.isEmpty()) {
        files.forEach { (path: String, refFile: ReferencedFile) ->
            sb.appendLine("Source File: $path")
            sb.appendLine()

            // Collect all slices for this file (Types + Functions)
            val allSlices: List<DeclarationSlice> =
                refFile.referencedTypes.map { it.declarationSlice } + refFile.referencedFunctions.map { it.declarationSlice }

            val reconstructedContent: String = renderReconstructedFile(
                refFile.packageDirective, refFile.importsList, allSlices
            )

            sb.append(reconstructedContent)
            sb.appendLine("----------------------------------------------------------------") // File separator
        }
    }

    if (referenceLimitReached) {
        sb.appendLine("Reference limit reached; output truncated.")
    }
    return sb.toString()
}

/**
 * Reconstructs a syntactically valid source file view from a list of isolated declaration slices.
 *
 * **The Problem:** collected symbols are isolated "leaf" nodes (e.g., a single function).
 * To provide useful context to an LLM, we must present them within their original class hierarchy
 * (parents, companion objects, etc.) so that scope and visibility are clear.
 *
 * **The Solution (Tree Merger Algorithm):**
 * This function takes a flat list of slices and "merges" their ancestry paths to reconstruct
 * the skeleton of the file.
 *
 * 1.  **Sort:** Slices are sorted by their original `textOffset`. This restores the natural "reading order"
 *     of the file, ensuring classes and methods appear where they belong.
 * 2.  **Traverse & Diff:** We iterate through the sorted slices. For each slice, we compare its
 *     `structurePath` (ancestry) with the `currentPath` (the stack of currently open scopes).
 * 3.  **Close Scopes:** If the new path diverges from the old one (e.g., we moved from `ClassA` to `ClassB`),
 *     we close the scopes that are no longer valid (`ClassA`).
 * 4.  **Open Scopes:** We then open the new scopes required by the new path (`ClassB`) that weren't already open.
 * 5.  **Render:** Finally, we print the source code of the slice itself, indented to the correct depth.
 *
 * @param packageDirective The package declaration of the file.
 * @param importsList The list of imports to include at the top.
 * @param slices The list of [DeclarationSlice]s to render.
 * @return A string containing the reconstructed source code.
 */
private fun renderReconstructedFile(
    packageDirective: String?, importsList: List<String>, slices: List<DeclarationSlice>
): String {
    val sb = StringBuilder(96708)

    // Header
    if (packageDirective != null) {
        sb.appendLine(packageDirective)
        sb.appendLine()
    }

    if (importsList.isNotEmpty()) {
        importsList.forEach { sb.appendLine(it) }
        sb.appendLine()
    }

    // Sort by offset to ensure we traverse the file from top to bottom
    val sortedSlices: List<DeclarationSlice> = slices.sortedBy { it.caretLocation.offset }

    // The current stack of open containers (from outer to inner)
    // We store the StructureNode to check equality
    var currentPath: List<StructureNode> = emptyList()

    for (slice: DeclarationSlice in sortedSlices) {
        val nextPath: List<StructureNode> = slice.structurePath

        // 1. Determine common prefix (how many levels of nesting are shared?)
        var commonDepth = 0
        val minLen: Int = minOf(currentPath.size, nextPath.size)
        while (commonDepth < minLen && currentPath[commonDepth] == nextPath[commonDepth]) {
            commonDepth++
        }

        // 2. Close scopes that are no longer valid (from deep to shallow)
        // e.g. Current: [A, B], Next: [A, C]. Close B.
        for (i: Int in currentPath.lastIndex downTo commonDepth) {
            val indentation: String = "    ".repeat(i)
            sb.appendLine("$indentation    // ...")
            sb.appendLine("$indentation}")
        }

        // 3. Open new scopes (from shallow to deep)
        // e.g. Current: [A], Next: [A, C]. Open C.
        for (i: Int in commonDepth until nextPath.size) {
            val node: StructureNode = nextPath[i]
            val indentation: String = "    ".repeat(i)
            sb.appendLine()
            sb.appendLine("$indentation${node.signature} {")
            // Note: We don't print "..." immediately at start; only at end or between members
        }

        // 4. Print the symbol source code
        // We need to indent the entire source block to match the current nesting depth
        val contentIndentation: String = "    ".repeat(nextPath.size)
        val indentedSource: String = slice.sourceCode.prependIndent(contentIndentation)
        // Add a visual separator if this isn't the first item in a shared scope?
        // For now, just print the code.
        sb.appendLine(indentedSource)
        sb.appendLine()

        // Update stack
        currentPath = nextPath
    }

    // 5. Close any remaining open scopes
    for (i: Int in currentPath.lastIndex downTo 0) {
        val indentation: String = "    ".repeat(i)
        sb.appendLine("$indentation    // ...")
        sb.appendLine("$indentation}")
    }

    return sb.toString()
}
