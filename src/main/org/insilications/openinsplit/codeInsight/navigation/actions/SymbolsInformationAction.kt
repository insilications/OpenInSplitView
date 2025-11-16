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
import com.intellij.openapi.util.NlsSafe
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
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.tail
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
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
            LOG.debug { "No file or editor in context - File: $file - Editor: $editor" }
            e.presentation.isEnabled = false
            return
        }

        runWithModalProgressBlocking(project, GETTING_SYMBOL_INFO) {
            if (DumbService.isDumb(project)) {
                LOG.warn("Dumb mode active; aborting semantic resolution.")
                return@runWithModalProgressBlocking
            }

            val targetSymbol: PsiElement? = readAction {
                val offset: Int = editor.caretModel.offset
                val targetSymbol: PsiElement =
                    TargetElementUtil.getInstance().findTargetElement(editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED, offset) ?: run {
                        LOG.info("No declaration element at caret")
                        return@readAction null
                    }

                if (targetSymbol is KtNamedDeclaration) {
                    LOG.debug { "Target symbol is KtNamedDeclaration - targetSymbol.fqName: ${targetSymbol.fqName()}" }
                }
                if (targetSymbol is KtDeclaration) {
                    LOG.debug { "Target symbol is KtDeclaration: ${targetSymbol::class.qualifiedName} - kotlinFqName: ${targetSymbol.kotlinFqName}" }
                    val kotlinFqName: FqName? = targetSymbol.kotlinFqName
                    if (kotlinFqName != null) {
                        LOG.debug { "FqName.ROOT: ${FqName.ROOT}" }
                        LOG.debug { "kotlinFqName.isRoot: ${kotlinFqName.isRoot}" }
                        LOG.debug { "kotlinFqName.shortName: ${kotlinFqName.shortName()}" }
                        LOG.debug { "kotlinFqName.shortNameOrSpecial: ${kotlinFqName.shortNameOrSpecial()}" }
                        LOG.debug { "kotlinFqName.parent: ${kotlinFqName.parent()}" }
                        val segments: List<Name> = kotlinFqName.pathSegments()
                        for (child: Name in segments) {
                            LOG.debug { "  Child: ${child::class.qualifiedName} - child.asString: ${child.asString()}" }
                        }
                    }
                } else {
                    LOG.debug { "Target symbol is not KtDeclaration: ${targetSymbol::class.qualifiedName} - kotlinFqName: ${targetSymbol.kotlinFqName}" }
                }

                return@readAction targetSymbol
            }

            if (targetSymbol == null) {
                return@runWithModalProgressBlocking
            }

            LOG.debug { "Analyzing..." }
            val decl: KtDeclaration = targetSymbol as? KtDeclaration ?: return@runWithModalProgressBlocking

            readAction {
                analyze(decl) {
                    val payload: SymbolContextPayload = buildSymbolContext(project, decl)
                    deliverSymbolContext(targetSymbol, payload)
                }
            }

        }
    }

    private fun deliverSymbolContext(targetSymbol: PsiElement, payload: SymbolContextPayload) {
        val targetPsiType: String = targetSymbol::class.qualifiedName ?: targetSymbol.javaClass.name
        LOG.info(payload.toLogString(targetPsiType))
    }


    private tailrec fun KtNamedDeclaration.parentForFqName(): KtNamedDeclaration? {
        val parent: KtNamedDeclaration = getStrictParentOfType<KtNamedDeclaration>() ?: return null
        if (parent is KtProperty && parent.isLocal) return parent.parentForFqName()
        return parent
    }

    private fun KtNamedDeclaration.name(): Name = nameAsName ?: Name.special("<no name provided>")

    private fun KtNamedDeclaration.fqName(): FqNameUnsafe {
        containingClassOrObject?.let { it: KtClassOrObject ->
            if (it is KtObjectDeclaration && it.isCompanion()) {
                LOG.debug { "PORRA1" }
                return it.fqName().child(name())
            }
            LOG.debug { "PORRA2" }
            return FqNameUnsafe("${it.name()}.${name()}")
        }

        val internalSegments: List<@NlsSafe String> = generateSequence(this) { it.parentForFqName() }
            .filterIsInstance<KtNamedDeclaration>()
            .map { it: KtNamedDeclaration -> it.name ?: "<no name provided>" }
            .toList()
            .asReversed()
        val packageSegments = containingKtFile.packageFqName.pathSegments()
        LOG.debug { "PORRA3" }
        return FqNameUnsafe((packageSegments + internalSegments).joinToString("."))
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
    val filePath: String,
    val ktFilePath: String,
    val caret: CaretLocation,
    val qualifiedName: String?,
    val ktFqName: String?,
    val presentableText: String?,
    val ktNamedDeclName: String?,
    val ktFqNameRelativeString: String?,
    val symbolOriginString: String
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

    root.accept(/* visitor = */ object : KtTreeVisitorVoid() {
        override fun visitDeclaration(dcl: KtDeclaration) {
            val sb = StringBuilder()
            sb.appendLine()
            sb.appendLine("============ Visiting declaration: ${dcl::class.simpleName} - ${dcl::class.qualifiedName} - textRange: ${dcl.textRange} ============")
            sb.appendLine(dcl.text)
            sb.appendLine("==============================================================")
            LOG.info(sb.toString())
            super.visitDeclaration(dcl)
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
    targetDeclaration: KtDeclaration,
    maxRefs: Int = 2000
): SymbolContextPayload {
    val targetKtFile: KtFile = targetDeclaration.containingKtFile
    val packageDirectiveText: String? = targetKtFile.packageDirective?.text
    val importTexts: List<String> = targetKtFile.importList?.imports?.map { it.text } ?: emptyList()
    val symbolOrigin: KaSymbolOrigin = targetDeclaration.symbol.origin

    val targetSlice: DeclarationSlice = targetDeclaration.toDeclarationSlice(project, symbolOrigin)
    val targetContext = TargetSymbolContext(
        packageDirective = packageDirectiveText,
        imports = importTexts,
        declarationSlice = targetSlice
    )

    val usages: List<ResolvedUsage> = collectResolvedUsagesInDeclaration(targetDeclaration, maxRefs)
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
        val symbolOrigin: KaSymbolOrigin = symbol.origin


        val declarationPsi: KtDeclaration = symbol.locateDeclarationPsi() ?: continue
        val declarationSlice: DeclarationSlice = declarationPsi.toDeclarationSlice(project, symbolOrigin)
        if (!symbol.isProjectSourceSymbol()) {
            LOG.info(declarationSlice.toLogString())
            continue
        }
        val bucket: MutableReferencedSymbolAggregation = aggregations.getOrPut(declarationPsi) {
            MutableReferencedSymbolAggregation(
                declarationSlice = declarationSlice,
                usageKinds = LinkedHashSet()
            )
        }
        bucket.usageKinds += usage.usageKind.toClassificationString()
    }

    return aggregations.values.map { aggregation: MutableReferencedSymbolAggregation ->
        ReferencedSymbolContext(
            declarationSlice = aggregation.declarationSlice,
            usageClassifications = aggregation.usageKinds.toList()
        )
    }
}

private fun KaSymbol.isProjectSourceSymbol(): Boolean = when (origin) {
    KaSymbolOrigin.SOURCE,
    KaSymbolOrigin.SOURCE_MEMBER_GENERATED,
    KaSymbolOrigin.JAVA_SOURCE -> true

    else -> false
}

private fun KaSymbol.locateDeclarationPsi(): KtDeclaration? {
    // Returns the symbol's PsiElement if its type is PSI and KaSymbol.origin is KaSymbolOrigin.SOURCE or KaSymbolOrigin.JAVA_SOURCE, and null otherwise.
    if (origin != KaSymbolOrigin.SOURCE && origin != KaSymbolOrigin.JAVA_SOURCE && origin != KaSymbolOrigin.LIBRARY && origin != KaSymbolOrigin.JAVA_LIBRARY) {
        return null
    }
    val sourcePsi: PsiElement = this.psi ?: return null
//    val sourcePsi: KtElement = sourcePsiSafe<KtElement>() ?: return null
    return when (sourcePsi) {
        is KtDeclaration -> sourcePsi
        else -> sourcePsi.getParentOfType(strict = true)
    }
}

private fun KtDeclaration.toDeclarationSlice(project: Project, symbolOrigin: KaSymbolOrigin): DeclarationSlice {
    val ktFile: KtFile = containingKtFile
    val ktFilePath: String = containingKtFile.virtualFilePath
    val packageFqName: FqName = ktFile.packageFqName
    val psiFile: PsiFile = containingFile
    val filePath: String = psiFile.virtualFile?.path ?: psiFile.name
    val caretLocation: CaretLocation = resolveCaretLocation(project, psiFile, textOffset)
    val qualifiedName: String? = computeQualifiedName()
    val kqFqName: FqName? = kotlinFqName
    val ktFqNameRelativeString: String? = kqFqName?.tail(packageFqName)?.asString()
    val ktFqNameString: String? = kqFqName?.asString()
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
    return DeclarationSlice(
        sourceCode = text,
        filePath = filePath,
        ktFilePath = ktFilePath,
        caret = caretLocation,
        qualifiedName = qualifiedName,
        ktFqName = ktFqNameString,
        presentableText = presentableText,
        ktNamedDeclName = ktNamedDeclName,
        ktFqNameRelativeString = ktFqNameRelativeString,
        symbolOriginString = symbolOriginString
    )
}

private fun KtDeclaration.computeQualifiedName(): String? {
//    return kotlinFqName?.asString() ?: (this as? KtNamedDeclaration)?.name
    return kotlinFqName?.asString() ?: (this as? KtNamedDeclaration)?.presentation?.presentableText ?: (this as? KtNamedDeclaration)?.name
}

private fun KtDeclaration.computePresentableText(): String? {
    return (this as? KtNamedDeclaration)?.presentation?.presentableText
}

private fun KtDeclaration.computeKtNamedDeclName(): String? {
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

private fun UsageKind.toClassificationString(): String = when (this) {
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

private fun DeclarationSlice.toLogString(): String {
    val sb = StringBuilder()
    sb.appendLine()
    sb.appendLine("---- Referenced symbol NOT ADDED ----")
    sb.appendLine("File: $filePath")
    sb.appendLine("ktFilePath: $ktFilePath")
    sb.appendLine("Qualified name: ${qualifiedName ?: "<anonymous>"}")
    sb.appendLine("symbolOriginString: $symbolOriginString")
    sb.appendLine("ktFqNameRelativeString: ${ktFqNameRelativeString ?: "<anonymous>"}")
    sb.appendLine("ktFqName name: ${ktFqName ?: "<anonymous>"}")
    sb.appendLine("presentableText name: ${presentableText ?: "<anonymous>"}")
    sb.appendLine("ktNamedDeclName name: ${ktNamedDeclName ?: "<anonymous>"}")
    sb.appendLine("Caret: offset=${caret.offset}, line=${caret.line}, column=${caret.column}")
    sb.appendLine(sourceCode)
    return sb.toString()
}

private fun SymbolContextPayload.toLogString(targetPsiType: String): String {
    val sb = StringBuilder()
    sb.appendLine()
    sb.appendLine("============ Target PSI type: $targetPsiType - Referenced Symbols: ${referencedSymbols.size} ============")
    sb.appendLine("Target file: ${target.declarationSlice.filePath}")
    sb.appendLine("Target ktFilePath: ${target.declarationSlice.ktFilePath}")
    sb.appendLine("Target qualified name: ${target.declarationSlice.qualifiedName ?: "<anonymous>"}")
    sb.appendLine("Target symbolOriginString: ${target.declarationSlice.symbolOriginString}")
    sb.appendLine("Target ktFqNameRelativeString: ${target.declarationSlice.ktFqNameRelativeString ?: "<anonymous>"}")
    sb.appendLine("Target ktFqName name: ${target.declarationSlice.ktFqName ?: "<anonymous>"}")
    sb.appendLine("Target presentableText name: ${target.declarationSlice.presentableText ?: "<anonymous>"}")
    sb.appendLine("Target ktNamedDeclName name: ${target.declarationSlice.ktNamedDeclName ?: "<anonymous>"}")
    sb.appendLine("Target caret: offset=${target.declarationSlice.caret.offset}, line=${target.declarationSlice.caret.line}, column=${target.declarationSlice.caret.column}")
    sb.appendLine("Package: ${target.packageDirective ?: "<none>"}")
    if (target.imports.isNotEmpty()) {
        sb.appendLine("Imports:")
        target.imports.forEach { sb.appendLine("  $it") }
    } else {
        sb.appendLine("Imports: <none>")
    }
    sb.appendLine("Target declaration:")
    sb.appendLine(target.declarationSlice.sourceCode)

    referencedSymbols.forEachIndexed { index: Int, referenced: ReferencedSymbolContext ->
        sb.appendLine()
        sb.appendLine("---- Referenced symbol #${index + 1} ----")
        sb.appendLine("File: ${referenced.declarationSlice.filePath}")
        sb.appendLine("ktFilePath: ${referenced.declarationSlice.ktFilePath}")
        sb.appendLine("Qualified name: ${referenced.declarationSlice.qualifiedName ?: "<anonymous>"}")
        sb.appendLine("symbolOriginString: ${referenced.declarationSlice.symbolOriginString}")
        sb.appendLine("ktFqNameRelativeString: ${referenced.declarationSlice.ktFqNameRelativeString ?: "<anonymous>"}")
        sb.appendLine("ktFqName name: ${referenced.declarationSlice.ktFqName ?: "<anonymous>"}")
        sb.appendLine("presentableText name: ${referenced.declarationSlice.presentableText ?: "<anonymous>"}")
        sb.appendLine("ktNamedDeclName name: ${referenced.declarationSlice.ktNamedDeclName ?: "<anonymous>"}")
        sb.appendLine("Caret: offset=${referenced.declarationSlice.caret.offset}, line=${referenced.declarationSlice.caret.line}, column=${referenced.declarationSlice.caret.column}")
        sb.appendLine("Usage classifications: ${referenced.usageClassifications.joinToString()}")
        sb.appendLine("Declaration:")
        sb.appendLine(referenced.declarationSlice.sourceCode)
    }

    sb.appendLine("==============================================================")
    return sb.toString()
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
