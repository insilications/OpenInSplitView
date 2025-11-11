package org.insilications.openinsplit.codeInsight.navigation.actions
//
//data class SymbolContext(
//    val request: SymbolRequest,
//    val root: SymbolDeclaration,
//    val usedSymbols: List<UsedSymbol>,
//    val warnings: List<String> = emptyList(),
//    val depthLimitReached: Boolean = false
//)
//
//data class SymbolRequest(
//    val rawInput: String,
//    val resolutionKind: ResolutionKind,
//    val fqName: String?,
//    val filePath: String?,
//    val offset: Int?
//)
//
//enum class ResolutionKind { CARET, FQN, PSI_DIRECT }
//
//data class SymbolDeclaration(
//    val id: String,                  // e.g. "com.example.Foo.bar" or synthetic if local
//    val kind: DeclarationKind,       // CLASS, FUNCTION, PROPERTY, CONSTRUCTOR, LOCAL_FUNCTION, etc.
//    val visibility: String?,         // public/internal/private/protected
//    val modality: String?,           // final/open/abstract/sealed/etc.
//    val origin: SymbolOrigin,        // PROJECT, LIBRARY, SYNTHETIC
//    val filePath: String?,
//    val startLine: Int?,
//    val endLine: Int?,
//    val startOffset: Int,
//    val endOffset: Int,
//    val signature: String?,          // Renderer output (KaDeclarationRendererForSource)
//    val sourceSnippet: String        // Exact slice
//)
//
//data class UsedSymbol(
//    val declaration: SymbolDeclaration?, // null if no PSI (library binary)
//    val usageKind: UsageKind,
//    val usageOccurrences: List<UsageOccurrence>,
//    val resolvedVia: ResolutionMechanism,
//    val pointerStableId: String        // pointer key or fallback id
//)
//
//data class UsageOccurrence(
//    val inRootOffsetStart: Int,
//    val inRootOffsetEnd: Int,
//    val inRootLineStart: Int?,
//    val inRootLineEnd: Int?,
//    val snippet: String                // small substring of root (e.g. ± 40 chars window)
//)
//
//enum class UsageKind {
//    CALL, PROPERTY_ACCESS_GET, PROPERTY_ACCESS_SET,
//    TYPE_REFERENCE, SUPER_TYPE, CONSTRUCTOR_CALL,
//    ANNOTATION, DELEGATED_PROPERTY, IMPORT_ALIAS,
//    OPERATOR_CALL, RECEIVER_TYPE_REFERENCE, EXTENSION_RECEIVER
//}
//
//enum class ResolutionMechanism {
//    REFERENCE_EXPRESSION, CALL_RESOLUTION, TYPE_RESOLUTION, SUPER_TYPE_RESOLUTION, ANNOTATION_RESOLUTION
//}
//
//enum class SymbolOrigin { PROJECT, LIBRARY, SYNTHETIC }
//
//enum class DeclarationKind { CLASS, OBJECT, INTERFACE, FUNCTION, CONSTRUCTOR, PROPERTY, ENUM_ENTRY, LOCAL_FUNCTION, LOCAL_CLASS }
