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
import org.jetbrains.uast.UTypeReferenceExpression

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

            SYMBOL_USAGE_LOG.info("Analyzing...")

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
 * A comprehensive snapshot of a declaration, extracted during the **Information Extracted** phase.
 * Contains identity (FQN, names), location, the full source code text.
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

private data class ReferencedCollections(
    val referencedFiles: LinkedHashMap<String, ReferencedFile>, val limitReached: Boolean
)

/* ============================= CONTEXT BUILDERS ============================= */

private fun buildSymbolContext(
    project: Project, targetSymbol: PsiElement, maxRefs: Int = 5000
) {
    val targetSlice: DeclarationSlice = targetSymbol.toDeclarationSlice(project)

    LogToFile.info(targetSlice.toLogString())
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

    // Pack every attribute that downstream tooling may need to reconstruct a declarative slice
    return DeclarationSlice(
        psiFilePath,
        caretLocation,
        presentableText,
        name,
        ktFqNameRelativeString,
        fqNameTypeString,
        kotlinFqNameString = kotlinFqName?.asString(),
        sourceCode = sourceCode
    )
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

private fun DeclarationSlice.toLogString(): String {
    val sb = StringBuilder(96708)

    sb.appendLine(sourceCode)

    return sb.toString()
}
