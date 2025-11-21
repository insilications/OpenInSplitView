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
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiUtil
import org.insilications.openinsplit.debug
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.idea.refactoring.project
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.tail
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UClassLiteralExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.USuperExpression
import org.jetbrains.uast.UTypeReferenceExpression
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.UElement
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

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
    val declarationSlice: DeclarationSlice,
    val usageKinds: Set<UsageKind>
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
    val typeSlices: List<ReferencedDeclaration>,
    val functionSlices: List<ReferencedDeclaration>,
    val limitReached: Boolean
)

private val SYMBOL_USAGE_LOG: Logger = Logger.getInstance("org.insilications.openinsplit.SymbolUsageCollector")

/* ============================= CONTEXT BUILDERS ============================= */

private fun buildSymbolContext(
    project: Project, targetSymbol: PsiElement, maxRefs: Int = 5000
) {
    val targetPsiFile: PsiFile = targetSymbol.containingFile
    val packageDirective: String? = getPackageDirective(targetPsiFile)
    val importsList: List<String> = getImportsList(targetPsiFile)

    val symbolKind: SymbolKind = targetSymbol.detectSymbolKind() ?: run {
        LOG.warn("Unsupported target symbol: ${targetSymbol::class.qualifiedName}")
        return
    }

    val targetSlice: DeclarationSlice = targetSymbol.toDeclarationSlice(project)
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

private fun PsiElement.detectSymbolKind(): SymbolKind? {
    val (sourceDeclaration) = preferSourceDeclaration()
    return when (sourceDeclaration) {
        is KtFunction -> SymbolKind.FUNCTION
        is PsiMethod -> SymbolKind.FUNCTION
        is PsiFunctionalExpression -> SymbolKind.FUNCTION
        is PsiLambdaExpression -> SymbolKind.FUNCTION
        is KtClassOrObject -> SymbolKind.CLASS
        is PsiClass -> SymbolKind.CLASS
        else -> null
    }
}

private fun collectReferencedDeclarations(
    project: Project,
    targetSymbol: PsiElement,
    symbolKind: SymbolKind,
    maxRefs: Int
): ReferencedCollections {
    val uRoot: UElement = targetSymbol.toUDeclarationRoot(symbolKind)
        ?: targetSymbol.navigationElement?.toUDeclarationRoot(symbolKind)
        ?: return ReferencedCollections(emptyList(), emptyList(), limitReached = false)

    val collector = SymbolUsageCollector(project, targetSymbol, maxRefs)
    uRoot.accept(collector)
    return collector.buildResult(project)
}

private fun PsiElement.toUDeclarationRoot(symbolKind: SymbolKind): UElement? = when (symbolKind) {
    SymbolKind.FUNCTION -> this.toUElementOfType<UMethod>()
        ?: this.toUElementOfType<ULambdaExpression>()
    SymbolKind.CLASS -> this.toUElementOfType<UClass>()
}

private class SymbolUsageCollector(
    private val project: Project,
    targetPsi: PsiElement,
    private val maxRefs: Int
) : AbstractUastVisitor() {

    private data class CollectedUsage(
        val pointer: SmartPsiElementPointer<PsiElement>,
        val usageKinds: MutableSet<UsageKind>
    )

    private val pointerManager: SmartPointerManager = SmartPointerManager.getInstance(project)
    private val targetDeclaration: PsiElement = targetPsi.preferSourceDeclaration().first
    private val typeUsages: LinkedHashMap<String, CollectedUsage> = LinkedHashMap()
    private val functionUsages: LinkedHashMap<String, CollectedUsage> = LinkedHashMap()
    private var hasReachedLimit: Boolean = false

    fun buildResult(project: Project): ReferencedCollections {
        val typeSlices: List<ReferencedDeclaration> = typeUsages.values.mapNotNull { it.toReferencedDeclaration(project) }
        val functionSlices: List<ReferencedDeclaration> = functionUsages.values.mapNotNull { it.toReferencedDeclaration(project) }
        return ReferencedCollections(typeSlices, functionSlices, hasReachedLimit)
    }

    private fun CollectedUsage.toReferencedDeclaration(project: Project): ReferencedDeclaration? {
        val resolved: PsiElement = pointer.element ?: return null
        return ReferencedDeclaration(
            declarationSlice = resolved.toDeclarationSlice(project),
            usageKinds = usageKinds.toSet()
        )
    }

    private fun shouldStopTraversal(): Boolean = hasReachedLimit

    override fun visitElement(node: UElement): Boolean {
        if (shouldStopTraversal()) return false
        return super.visitElement(node)
    }

    override fun visitTypeReferenceExpression(node: UTypeReferenceExpression): Boolean {
        if (shouldStopTraversal()) return true
        val resolved: PsiElement? = node.resolvePsiClass()
            ?: node.sourcePsi?.let { resolveClassifierWithAnalysis(it) }
        recordType(resolved, UsageKind.TYPE_REFERENCE)
        return super.visitTypeReferenceExpression(node)
    }

    override fun visitClassLiteralExpression(node: UClassLiteralExpression): Boolean {
        if (shouldStopTraversal()) return true
        val resolved: PsiElement? = node.type?.let { PsiUtil.resolveClassInType(it) }
            ?: node.sourcePsi?.let { resolveClassifierWithAnalysis(it) }
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
        val resolved: PsiElement? = node.resolve() ?: node.javaPsi?.nameReferenceElement?.resolve()
        recordType(resolved, UsageKind.ANNOTATION)
        return super.visitAnnotation(node)
    }

    override fun visitCallExpression(node: UCallExpression): Boolean {
        if (shouldStopTraversal()) return true
        val usageKind: UsageKind = if (node.kind == UastCallKind.CONSTRUCTOR_CALL) {
            UsageKind.CONSTRUCTOR_CALL
        } else {
            UsageKind.CALL
        }

        val resolvedCallable: PsiElement? = node.resolve()
        recordFunction(resolvedCallable, usageKind)

        if (node.kind == UastCallKind.CONSTRUCTOR_CALL) {
            val constructorOwner: PsiElement? = (resolvedCallable as? PsiMethod)?.containingClass
                ?: node.classReference?.resolve()
                ?: node.sourcePsi?.let { resolveClassifierWithAnalysis(it) }
            recordType(constructorOwner, UsageKind.CONSTRUCTOR_CALL)
        }

        return super.visitCallExpression(node)
    }

    override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
        if (shouldStopTraversal()) return true
        if (node.uastParent is UCallExpression) {
            return super.visitSimpleNameReferenceExpression(node)
        }
        val resolved: PsiElement? = node.resolve()
        when (resolved) {
            is PsiMethod -> recordFunction(resolved, UsageKind.CALL)
            is PsiClass -> recordType(resolved, UsageKind.TYPE_REFERENCE)
        }
        return super.visitSimpleNameReferenceExpression(node)
    }

    private fun recordType(element: PsiElement?, usageKind: UsageKind) {
        record(element, usageKind, typeUsages)
    }

    private fun recordFunction(element: PsiElement?, usageKind: UsageKind) {
        record(element, usageKind, functionUsages)
    }

    private fun record(
        element: PsiElement?,
        usageKind: UsageKind,
        bucket: LinkedHashMap<String, CollectedUsage>
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
            pointer = pointerManager.createSmartPsiElementPointer(normalized),
            usageKinds = linkedSetOf(usageKind)
        )
    }
}

private fun UTypeReferenceExpression.resolvePsiClass(): PsiElement? = type?.let { PsiUtil.resolveClassInType(it) }

private fun PsiElement.computeStableStorageKey(): String? {
    val psiFile: PsiFile? = containingFile
    val virtualFilePath: String? = psiFile?.virtualFile?.path
    val offset: Int? = textRange?.startOffset?.takeIf { it >= 0 }
        ?: textOffset.takeIf { it >= 0 }
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

private fun resolveClassifierWithAnalysis(element: PsiElement): PsiElement? {
    val typeReference: KtTypeReference = element as? KtTypeReference ?: return null
    return typeReference.runAnalysisSafely {
        typeReference.type.expandedSymbol?.psi
    }
}

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

    when (sourceDeclaration) {
        is KtDeclaration -> {
            val ktFile: KtFile = sourceDeclaration.containingKtFile
            return if (!ktFile.isCompiled) {
                sourceDeclaration to ktFile
            } else {
                this to this.containingFile
            }
        }

        is PsiClass -> {
            val psiFile: PsiFile = sourceDeclaration.containingFile
            return if (psiFile !is PsiCompiledFile) {
                sourceDeclaration to psiFile
            } else {
                this to this.containingFile
            }
        }

        else -> return this to this.containingFile
    }
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

    // Pack every attribute that downstream tooling may need to reconstruct a declarative slice
    return DeclarationSlice(
        psiFilePath,
        caretLocation,
        presentableText,
        name,
        ktFqNameRelativeString,
        fqNameTypeString,
        kotlinFqNameString = kotlinFqName?.asString(),
        sourceCode = sourceDeclaration.text,
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
    sb.appendLine("Target Qualified Name: ${declarationSlice.kotlinFqNameString ?: "<anonymous>"}")
    sb.appendLine("Target ktFqNameRelativeString: ${declarationSlice.ktFqNameRelativeString ?: "<anonymous>"}")
    sb.appendLine("Target ktFilePath: ${declarationSlice.psiFilePath}")
    sb.appendLine("Target presentableText: ${declarationSlice.presentableText ?: "<anonymous>"}")
    sb.appendLine("Target ktNamedDeclName: ${declarationSlice.name ?: "<anonymous>"}")
    sb.appendLine("Target caret: offset=${declarationSlice.caretLocation.offset}, line=${declarationSlice.caretLocation.line}, column=${declarationSlice.caretLocation.column}")
    sb.appendLine("Symbol Kind: $symbolKind")
    sb.appendLine("Package: ${packageDirective ?: "<none>"}")
    if (importsList.isNotEmpty()) {
        sb.appendLine("Imports:")
        importsList.forEach { sb.appendLine("  $it") }
    } else {
        sb.appendLine("Imports: <none>")
    }
    appendReferencedSection(sb, "Referenced Types", referencedTypes)
    appendReferencedSection(sb, "Referenced Functions", referencedFunctions)
    if (referenceLimitReached) {
        sb.appendLine("Reference limit reached; output truncated.")
    }
    sb.appendLine()
    sb.appendLine("Target Declaration Source Code:")
    sb.appendLine(declarationSlice.sourceCode)

    sb.appendLine("==============================================================")
    return sb.toString()
}

private fun TargetSymbolContext.appendReferencedSection(
    sb: StringBuilder,
    label: String,
    references: List<ReferencedDeclaration>,
    maxEntries: Int = 25
) {
    if (references.isEmpty()) {
        sb.appendLine("$label: <none>")
        return
    }

    sb.appendLine("$label (${references.size}):")
    references.take(maxEntries).forEach { ref ->
        val usageSummary: String = ref.usageKinds.takeIf { it.isNotEmpty() }
            ?.joinToString { usage -> usage.toClassificationString() } ?: "unknown"
        val displayName: String = ref.declarationSlice.kotlinFqNameString
            ?: ref.declarationSlice.presentableText
            ?: ref.declarationSlice.name
            ?: "<anonymous>"
        sb.appendLine("  - $displayName [$usageSummary] @ ${ref.declarationSlice.psiFilePath}")
    }
    if (references.size > maxEntries) {
        sb.appendLine("  ... (${references.size - maxEntries} more)")
    }
}
