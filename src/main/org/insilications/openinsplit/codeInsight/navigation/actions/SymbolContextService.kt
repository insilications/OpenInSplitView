package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.declarations.KaDeclarationRenderer
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.*


@OptIn(KaExperimentalApi::class)
class SymbolContextService(private val project: Project) {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
    }

    private val renderer: KaDeclarationRenderer = KaDeclarationRendererForSource.WITH_SHORT_NAMES
    private val maxReferences = 500

    fun buildContext(request: SymbolRequestConfig): SymbolContext {
        if (DumbService.isDumb(project)) {
            return SymbolContext(
                request = request.toRequestMeta(),
                root = emptyRoot(request),
                usedSymbols = emptyList(),
                warnings = listOf("Dumb mode active: semantic resolution skipped.")
            )
        }

        val rootDecl = resolveDeclaration(request)
            ?: return SymbolContext(
                request = request.toRequestMeta(),
                root = emptyRoot(request),
                usedSymbols = emptyList(),
                warnings = listOf("Declaration not found.")
            )

        return runReadAction {
            analyze(rootDecl) {
                val rootSymbol: KaDeclarationSymbol? = (rootDecl as? KtDeclaration)?.symbol
                val rootDeclarationDto: SymbolDeclaration = symbolToDeclaration(rootDecl, rootSymbol)

                val usageAggregator = UsageAggregator()

                // Traverse body
                rootDecl.accept(object : KtTreeVisitorVoid() {
                    override fun visitCallExpression(expression: KtCallExpression) {
                        if (usageAggregator.sizeExceeded(maxReferences)) return
                        val call = expression.resolveCall()
                        val callableSym: KaCallableSymbol? = when (call) {
                            is KaFunctionCall<*> -> call.symbol
                            is KaVariableAccessCall -> call.symbol
                            is KaErrorCallInfo -> null
                            else -> null
                        }
                        callableSym?.let {
                            usageAggregator.recordUsage(
                                symbol = it,
                                usageKind = UsageKind.CALL,
                                psi = expression
                            )
                        }
                        super.visitCallExpression(expression)
                    }

                    override fun visitReferenceExpression(expression: KtReferenceExpression) {
                        if (usageAggregator.sizeExceeded(maxReferences)) return
                        val targets = expression.references
                            .mapNotNull { ref ->
                                // Kotlin-specific reference resolution (ref.resolve() may be FE1.0)
                                tryResolveSymbol(ref, expression)
                            }.flatten()

                        for (sym in targets) {
                            val usageKind = classifyReference(expression, sym)
                            usageAggregator.recordUsage(sym, usageKind, expression)
                        }
                        super.visitReferenceExpression(expression)
                    }

                    override fun visitTypeReference(typeReference: KtTypeReference) {
                        if (usageAggregator.sizeExceeded(maxReferences)) return
                        val type: KtTypeElement? = typeReference.typeElement
                        val classifierSymbol: KaClassLikeSymbol? = type?.expandedSymbol as? KaClassLikeSymbol
                        classifierSymbol?.let {
                            usageAggregator.recordUsage(it, UsageKind.TYPE_REFERENCE, typeReference)
                        }
                        super.visitTypeReference(typeReference)
                    }

                    override fun visitSuperTypeListEntry(entry: KtSuperTypeListEntry) {
                        if (usageAggregator.sizeExceeded(maxReferences)) {
                            return
                        }
                        val type: KtTypeElement? = entry.typeReference?.typeElement
                        entry.typeReference?.typeElement?.expandedSymbol?.let {
                            usageAggregator.recordUsage(it as KaClassLikeSymbol, UsageKind.SUPER_TYPE, entry)
                        }
                        super.visitSuperTypeListEntry(entry)
                    }

                    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
                        if (usageAggregator.sizeExceeded(maxReferences)) return
                        val sym = annotationEntry.getSymbol()
                        sym?.let { usageAggregator.recordUsage(it, UsageKind.ANNOTATION, annotationEntry) }
                        super.visitAnnotationEntry(annotationEntry)
                    }
                })

                val usedSymbolsDtos = usageAggregator.buildDtos(this, rootDecl, ::symbolToDeclaration)

                SymbolContext(
                    request = request.toRequestMeta(),
                    root = rootDeclarationDto,
                    usedSymbols = usedSymbolsDtos,
                    warnings = usageAggregator.warnings(),
                    depthLimitReached = false // recursion not implemented here
                )
            }
        }
    }

    private fun tryResolveSymbol(ref: KtReference, expr: KtReferenceExpression): List<KaSymbol> =
        analyze(expr) {
            // Fallback strategy: For K2 references
            val symbol: KaDeclarationSymbol? = (expr as? KtDeclaration)?.symbol
            symbol?.let { listOf(it) } ?: emptyList()
        }

    private fun classifyReference(expr: KtReferenceExpression, symbol: KaSymbol): UsageKind =
        when (symbol) {
            is KaCallableSymbol -> UsageKind.PROPERTY_ACCESS_GET // refine later (getter vs setter vs call)
            is KaClassLikeSymbol -> UsageKind.TYPE_REFERENCE
            else -> UsageKind.PROPERTY_ACCESS_GET
        }

    private fun symbolToDeclaration(psi: PsiElement, symbol: KaSymbol?): SymbolDeclaration {
        val file: PsiFile? = psi.containingFile
        val vFile: VirtualFile? = file?.virtualFile
        val doc: Document? = file?.let { PsiDocumentManager.getInstance(project).getDocument(it) }

        val range: TextRange = psi.textRange
        val startLine: Int? = doc?.getLineNumber(range.startOffset)
        val endLine: Int? = doc?.getLineNumber(range.endOffset)

        val rendered: String? = if (symbol is KaDeclarationSymbol) {
            symbol.render(renderer)
        } else psi.text.split('\n').firstOrNull()

        return SymbolDeclaration(
            id = computeStableId(symbol, psi),
            kind = classifyDeclaration(symbol, psi),
            visibility = (symbol as? KaDeclarationSymbol)?.visibility?.name,
            modality = (symbol as? KaDeclarationSymbol)?.modality?.name,
            origin = mapOrigin(symbol),
            filePath = vFile?.path,
            startLine = startLine,
            endLine = endLine,
            startOffset = range.startOffset,
            endOffset = range.endOffset,
            signature = rendered,
            sourceSnippet = psi.text
        )
    }

    private fun computeStableId(symbol: KaSymbol?, psi: PsiElement): String =
        when (symbol) {
            is KaNamedSymbol -> symbol.callableId?.toString()
                ?: symbol.name.asString()

            null -> psi.textRange.startOffset.toString() + ":" + psi.textRange.endOffset
            else -> symbol.toString()
        }

    private fun classifyDeclaration(symbol: KaSymbol?, psi: PsiElement): DeclarationKind =
        when {
            symbol is KaClassSymbol && symbol.classKind.isObject -> DeclarationKind.OBJECT
            symbol is KaClassSymbol && symbol.classKind.isEnumClass -> DeclarationKind.CLASS
            symbol is KaFunctionSymbol -> DeclarationKind.FUNCTION
            symbol is KaConstructorSymbol -> DeclarationKind.CONSTRUCTOR
            symbol is KaPropertySymbol -> DeclarationKind.PROPERTY
            psi is KtNamedFunction && psi.isLocal -> DeclarationKind.LOCAL_FUNCTION
            psi is KtClass && psi.isLocal -> DeclarationKind.LOCAL_CLASS
            else -> DeclarationKind.CLASS
        }

    private fun mapOrigin(symbol: KaSymbol?): SymbolOrigin =
        when (symbol?.origin) {
            KaSymbolOrigin.SOURCE -> SymbolOrigin.PROJECT
            KaSymbolOrigin.JAVA_SOURCE, KaSymbolOrigin.JAVA_LIBRARY -> SymbolOrigin.LIBRARY
            KaSymbolOrigin.LIBRARY -> SymbolOrigin.LIBRARY
            KaSymbolOrigin.SYNTHETIC -> SymbolOrigin.SYNTHETIC
            KaSymbolOrigin.INTERSECTION_OVERRIDE,
            KaSymbolOrigin.SUBSTITUTION_OVERRIDE -> SymbolOrigin.SYNTHETIC

            null -> SymbolOrigin.PROJECT
            else -> SymbolOrigin.PROJECT
        }

    private fun resolveDeclaration(request: SymbolRequestConfig): KtDeclaration? {
        // TODO: Implement FQN resolution using indices (omitted for brevity)
        return request.psiElement as? KtDeclaration
    }

    private fun emptyRoot(request: SymbolRequestConfig) = SymbolDeclaration(
        id = request.rawInput ?: "<unknown>",
        kind = DeclarationKind.FUNCTION,
        visibility = null,
        modality = null,
        origin = SymbolOrigin.PROJECT,
        filePath = null,
        startLine = null,
        endLine = null,
        startOffset = 0,
        endOffset = 0,
        signature = null,
        sourceSnippet = ""
    )
}

class UsageAggregator {
    private val map = LinkedHashMap<KaSymbolPointer<*>, MutableUsedSymbolBuilder>()
    private val warningsList: MutableList<String> = mutableListOf<String>()
    private var count = 0

    fun sizeExceeded(limit: Int): Boolean {
        if (count > limit) {
            return true
        }
        return false
    }

    fun recordUsage(symbol: KaSymbol, usageKind: UsageKind, psi: PsiElement) {
        val ptr: KaSymbolPointer<KaSymbol> = symbol.createPointer()
        val builder: MutableUsedSymbolBuilder = map.getOrPut(ptr) { MutableUsedSymbolBuilder(symbol, usageKind) }
        builder.addOccurrence(psi)
        count++
    }

    @OptIn(KaImplementationDetail::class)
    fun buildDtos(
        session: KaSession, root: PsiElement,
        declBuilder: (PsiElement, KaSymbol?) -> SymbolDeclaration
    ): List<UsedSymbol> =
        map.entries.map { (ptr: KaSymbolPointer<*>, b: MutableUsedSymbolBuilder) ->
            val restored: KaSymbol? = ptr.restoreSymbol()
            val declPsi: PsiElement? = restored?.psi
            val decl: SymbolDeclaration? = if (declPsi is KtDeclaration) declBuilder(declPsi, restored) else null
            b.toDto(decl, ptr)
        }

    fun warnings(): List<String> = warningsList
}

private class MutableUsedSymbolBuilder(
    val firstSymbol: KaSymbol,
    val primaryUsageKind: UsageKind
) {
    private val occurrences = mutableListOf<PsiElement>()

    fun addOccurrence(psi: PsiElement) {
        occurrences += psi
    }

    fun toDto(declaration: SymbolDeclaration?, pointer: KaSymbolPointer<*>): UsedSymbol {
        val usageOccurrences = occurrences.map { occ ->
            val file: PsiFile = occ.containingFile
            val doc: Document? = PsiDocumentManager.getInstance(file.project).getDocument(file)
            val range: TextRange = occ.textRange
            val startLine: Int? = doc?.getLineNumber(range.startOffset)
            val endLine: Int? = doc?.getLineNumber(range.endOffset)
            UsageOccurrence(
                inRootOffsetStart = range.startOffset,
                inRootOffsetEnd = range.endOffset,
                inRootLineStart = startLine,
                inRootLineEnd = endLine,
                snippet = occ.text.take(200)
            )
        }
        return UsedSymbol(
            declaration = declaration,
            usageKind = primaryUsageKind,
            usageOccurrences = usageOccurrences,
            resolvedVia = ResolutionMechanism.REFERENCE_EXPRESSION,
            pointerStableId = pointer.toString()
        )
    }
}

data class SymbolRequestConfig(
    val rawInput: String?,
    val psiElement: PsiElement?
) {
    fun toRequestMeta(): SymbolRequest = SymbolRequest(
        rawInput = rawInput ?: "",
        resolutionKind = if (psiElement != null) ResolutionKind.PSI_DIRECT else ResolutionKind.FQN,
        fqName = rawInput,
        filePath = (psiElement?.containingFile?.virtualFile?.path),
        offset = psiElement?.textOffset
    )
}