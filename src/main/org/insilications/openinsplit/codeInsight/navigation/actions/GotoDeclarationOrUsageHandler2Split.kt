package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.codeInsight.navigation.impl.LazyTargetWithPresentation
import com.intellij.codeInsight.navigation.impl.NavigationActionResult
import com.intellij.codeInsight.navigation.impl.NavigationActionResult.MultipleTargets
import com.intellij.codeInsight.navigation.impl.NavigationActionResult.SingleTarget
import com.intellij.find.FindBundle
import com.intellij.find.FindUsagesSettings
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil.underModalProgress
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.DumbModeBlockedFunctionality
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.SearchScope
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.list.createTargetPopup
import com.intellij.util.concurrency.annotations.RequiresBlockingContext
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.insilications.openinsplit.debug
import org.insilications.openinsplit.find.actions.ShowUsagesActionSplit.Companion.createVariantHandler
import org.insilications.openinsplit.find.actions.findShowUsagesSplit
import org.jetbrains.annotations.ApiStatus
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * "Go To Declaration Or Usages" action result
 * Mirror of the internal `GTDUActionResult` from `src/com/intellij/codeInsight/navigation/impl/gtdu.kt` in the Intellij Platform API
 */
sealed class GTDUActionResultMirror {
    /**
     * Go To Declaration
     */

    @ApiStatus.Internal
    class GTD(val navigationActionResult: NavigationActionResult) : GTDUActionResultMirror()

    /**
     * Show Usages
     */
    class SU(val targetVariants: List<Any>) : GTDUActionResultMirror() {
        init {
            require(targetVariants.isNotEmpty())
        }
    }
}

class GotoDeclarationOrUsageHandler2Split : CodeInsightActionHandler {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
        private const val DUMB_MODE_NOTIFICATION_KEY: String = "GotoDeclarationOrUsageSplit"
        private val RESOLVING_REFERENCES: String = CodeInsightBundle.message("progress.title.resolving.reference")
        private val DECLARATION_NAVIGATION_TITLE: String = CodeInsightBundle.message("declaration.navigation.title")
        private val SHOW_USAGES_AMBIGUOUS_TITLE: String = FindBundle.message("show.usages.ambiguous.title")
        private val NAVIGATION_NOT_AVAILABLE_DURING_DUMB_MODE: String =
            CodeInsightBundle.message("message.navigation.is.not.available.here.during.index.update")

        /**
         * Cache reflective lookups to avoid repeated scanning on every invocation.
         * Produces a strongly typed invoker for the reflective `gotoDeclarationOrUsages` call.
         * Failures trigger a short backoff so that transient class-loading races can recover.
         */
        @Volatile
        private var gotoDeclarationOrUsagesInvoker: GotoDeclarationOrUsagesInvoker? = null

        @Volatile
        private var nextLookupRetryAtMillis: Long = 0

        private const val LOOKUP_RETRY_BACKOFF_MS: Long = 5_000

        /**
         * Stable predefined scopes that do not depend on volatile UI state.
         * The list is intentionally narrow so we only cache scopes that map 1:1 to well-known names.
         */
        private val stableScopeSuppliers: List<(Project) -> SearchScope> = listOf(
            { ProjectScope.getProjectScope(it) },
            { GlobalSearchScope.projectScope(it) },
            { GlobalSearchScope.allScope(it) },
            { GlobalSearchScope.everythingScope(it) },
        )

        /**
         * Weakly keyed cache to avoid retaining disposed projects. Each project keeps a small map
         * of "stable" scope display names to the corresponding SearchScope instance.
         * We only populate it when the display name exactly matches one of our invariant scopes.
         */
        private val stableScopeCache: MutableMap<Project, MutableMap<String, SearchScope>> =
            Collections.synchronizedMap(WeakHashMap())

        private fun resolveStableSearchScope(project: Project, scopeName: String?): SearchScope? {
            if (scopeName.isNullOrEmpty()) return null
            val projectCache: MutableMap<String, SearchScope> = stableScopeCache.getOrPut(project) { mutableMapOf() }
            projectCache[scopeName]?.let { return it }

            val stableScope: SearchScope = stableScopeSuppliers
                .asSequence()
                .map { supplier: (Project) -> SearchScope -> supplier(project) }
                .firstOrNull { scope: SearchScope -> scope.displayName == scopeName }
                ?: return null

            projectCache[scopeName] = stableScope
            return stableScope
        }

        /**
         * Resolve the platform's `gotoDeclarationOrUsages` MethodHandle exactly once and reuse it.
         * The `@Volatile` `gotoDeclarationOrUsagesInvoker` keeps the fast path lock-free, while the double-checked `synchronized(this)` slow path
         * protects the reflective scan. When resolution fails we advance `nextLookupRetryAtMillis`, providing a short backoff window so repeated
         * invocations do not hammer class loading during startup races.
         */
        private fun resolveGotoDeclarationOrUsagesInvoker(): GotoDeclarationOrUsagesInvoker? {
            gotoDeclarationOrUsagesInvoker?.let { return it }

            val now: Long = System.currentTimeMillis()
            if (now < nextLookupRetryAtMillis) return null

            return synchronized(this) {
                gotoDeclarationOrUsagesInvoker?.let { return it }
                if (System.currentTimeMillis() < nextLookupRetryAtMillis) return@synchronized null

                try {
                    val handlerClass: Class<GotoDeclarationOrUsageHandler2> = GotoDeclarationOrUsageHandler2::class.java
                    val companionField: Field = handlerClass.getDeclaredField("Companion").apply { isAccessible = true }
                    // `com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2.Companion.gotoDeclarationOrUsages` is
                    // a regular (non `@JvmStatic`) function declared inside a companion object.
                    // It compiles as an instance companion member method on the generated Companion class.
                    // Instance methods are not static, they need a receiver, the companion object instance, when invoked via reflection.
                    // If the companion function were annotated with `@JvmStatic`, the compiler would emit a static method on the outer class.
                    // That static method could then be invoked without a receiver via reflection.
                    val companionInstance: Any = companionField.get(null)
                    val companionClass: Class<*> = companionInstance.javaClass
                    val intType: Class<Int> = Int::class.javaPrimitiveType ?: Integer.TYPE
                    val method: Method = companionClass.getDeclaredMethod(
                        "gotoDeclarationOrUsages",
                        Project::class.java,
                        Editor::class.java,
                        PsiFile::class.java,
                        intType,
                    ).apply { isAccessible = true }

                    val handle: MethodHandle = MethodHandles.lookup().unreflect(method).bindTo(companionInstance).asType(
                        MethodType.methodType(
                            Any::class.java,
                            Project::class.java,
                            Editor::class.java,
                            PsiFile::class.java,
                            intType,
                        ),
                    )

                    val invoker = GotoDeclarationOrUsagesInvoker { projectArg: Project, editorArg: Editor, fileArg: PsiFile, offsetArg: Int ->
                        handle.invoke(projectArg, editorArg, fileArg, offsetArg)
                    }
                    gotoDeclarationOrUsagesInvoker = invoker
                    nextLookupRetryAtMillis = 0
                    invoker
                } catch (t: Throwable) {
                    @Suppress("LongLine")
                    LOG.warn(
                        "Failed to resolve com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2.Companion.gotoDeclarationOrUsages via reflection",
                        t,
                    )
                    nextLookupRetryAtMillis = System.currentTimeMillis() + LOOKUP_RETRY_BACKOFF_MS
                    null
                }
            }
        }

        // Per-class caches: `MethodHandle` must be invoked on an instance of its declaring class.
        private val actionDataResultMethodCache = ConcurrentHashMap<Class<*>, MethodHandle>()
        private val resultNavGetterCache = ConcurrentHashMap<Class<*>, MethodHandle>()
        private val resultTargetVariantsGetterCache = ConcurrentHashMap<Class<*>, MethodHandle>()

        /**
         * Reflectively call `com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2.Companion.gotoDeclarationOrUsages`
         * and parse its result, returning a `GTDUActionResultMirror`. The code path behind `gotoDeclarationOrUsages` relies
         * on a web of internal/private functions and types, so reimplementing it is expensive. We are forced to use reflection.
         */
        private fun gotoDeclarationOrUsages_HACK(project: Project, editor: Editor, file: PsiFile, offset: Int): GTDUActionResultMirror? {
            // 1) Pre-resolve the reflective invoker for the private companion non `@JvmStatic` method outside the modal read section
            // so the initial lookup no longer runs under the PSI read lock:
            // GotoDeclarationOrUsageHandler2.Companion.gotoDeclarationOrUsages(Project, Editor, PsiFile, Int): GTDUActionData?
            val gotoDeclarationOrUsagesInvoker: GotoDeclarationOrUsagesInvoker = resolveGotoDeclarationOrUsagesInvoker() ?: return null

            val actionResult: GTDUActionResultMirror? = underModalProgress(
                project,
                progressTitle = RESOLVING_REFERENCES,
            ) {
                val actionData: Any = try {
                    gotoDeclarationOrUsagesInvoker.invoke(project, editor, file, offset)
                } catch (t: Throwable) {
                    LOG.warn("Failed to invoke gotoDeclarationOrUsages", t)
                    return@underModalProgress null
                } ?: return@underModalProgress null

                // 2) Resolve the internal GTDUActionData.result(): GTDUActionResult?
                val actionDataClass: Class<Any> = actionData.javaClass
                val resultMethod: MethodHandle =
                    actionDataResultMethodCache[actionDataClass] ?: actionDataClass.methods.firstOrNull { it.name == "result" && it.parameterCount == 0 }
                        ?.also { it.isAccessible = true }?.let { MethodHandles.lookup().unreflect(it) }
                        ?.also { actionDataResultMethodCache[actionDataClass] = it } ?: return@underModalProgress null
                // Call GTDUActionData.result(): GTDUActionResult?
                val rawResult = try {
                    resultMethod.invoke(actionData)
                } catch (t: Throwable) {
                    LOG.warn("Failed to invoke GTDUActionData.result()", t)
                    return@underModalProgress null
                } ?: return@underModalProgress null

                // 3) Distinguish the `GTDUActionResult.GTD` vs `GTDUActionResult.SU` classes via Java-style getters
                // `GTDUActionResult.GTD` holds the `navigationActionResult` property of type `NavigationActionResult`
                // `GTDUActionResult.SU` holds the `targetVariants` property, a `List<TargetVariant>`
                val resultClass: Class<Any> = rawResult.javaClass
                val navMethod: MethodHandle? =
                    resultNavGetterCache[resultClass] ?: resultClass.methods.firstOrNull { it.name == "getNavigationActionResult" && it.parameterCount == 0 }
                        ?.also { it.isAccessible = true }?.let { MethodHandles.lookup().unreflect(it) }?.also { resultNavGetterCache[resultClass] = it }
                val tvMethod: MethodHandle? =
                    resultTargetVariantsGetterCache[resultClass] ?: resultClass.methods.firstOrNull { it.name == "getTargetVariants" && it.parameterCount == 0 }
                        ?.also { it.isAccessible = true }?.let { MethodHandles.lookup().unreflect(it) }
                        ?.also { resultTargetVariantsGetterCache[resultClass] = it }

                when {
                    navMethod != null -> {
                        // Get the `navigationActionResult` property value of type `NavigationActionResult`
                        val navigationActionResult = try {
                            navMethod.invoke(rawResult)
                        } catch (t: Throwable) {
                            LOG.warn("Failed to obtain navigationActionResult", t)
                            return@underModalProgress null
                        } as? NavigationActionResult ?: return@underModalProgress null
                        GTDUActionResultMirror.GTD(navigationActionResult)
                    }

                    tvMethod != null -> {
                        // Get the `targetVariants` property value, a `List<TargetVariant>`
                        @Suppress("UNCHECKED_CAST")
                        val variants = try {
                            tvMethod.invoke(rawResult) as? List<Any?>
                        } catch (t: Throwable) {
                            LOG.warn("Failed to obtain targetVariants", t)
                            return@underModalProgress null
                        } ?: return@underModalProgress null
                        if (variants.isEmpty()) return@underModalProgress null
                        for (item in variants) {
                            if (item == null) return@underModalProgress null
                        }

                        @Suppress("UNCHECKED_CAST")
                        val nonNullVariants = variants as List<Any>

                        GTDUActionResultMirror.SU(nonNullVariants) // non-empty
                    }

                    else -> null
                }
            }

            return actionResult
        }

        fun interface GotoDeclarationOrUsagesInvoker {
            fun invoke(project: Project, editor: Editor, file: PsiFile, offset: Int): Any?
        }
    }

    override fun startInWriteAction(): Boolean = false

    /**
     * This is where the main work of the "GotoDeclarationActionSplit" action happens, via our current
     * `GotoDeclarationOrUsageHandler2Split` handler class.
     *
     * This function is eventually called by `GotoDeclarationActionSplit` and its ancestor `GotoDeclarationAction`
     * It tries to navigate to the declarations or usages of the symbol at the caret position
     */
    @RequiresBlockingContext
    @RequiresEdt
    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
//        ThreadingAssertions.assertEventDispatchThread()

        if (navigateToLookupItem(project, editor)) {
            LOG.debug { "navigateToLookupItem" }
            return
        }

        if (EditorUtil.isCaretInVirtualSpace(editor)) {
            LOG.debug { "EditorUtil.isCaretInVirtualSpace" }
            return
        }

        val offset: Int = editor.caretModel.offset
        try {
            when (val actionResult: GTDUActionResultMirror? = gotoDeclarationOrUsages_HACK(project, editor, file, offset)) {
                // The result of our action must be of type "Go To Declaration"
                is GTDUActionResultMirror.GTD -> {
                    LOG.debug { "GTDUActionResultMirror.GTD - gotoDeclarationOnly" }
                    gotoDeclarationOnly(project, editor, actionResult.navigationActionResult)
                }

                // The result of our action must be of type "Show Usages"
                is GTDUActionResultMirror.SU -> {
                    LOG.debug { "GTDUActionResultMirror.SU - showUsages" }
                    showUsages(project, editor, file, actionResult.targetVariants)
                }

                // No viable result. Nowhere to go.
                null -> {
                    LOG.debug { "notifyNowhereToGo" }
                    notifyNowhereToGo(project, editor, file, offset)
                }
            }
        } catch (_: IndexNotReadyException) {
            DumbService.getInstance(project).showDumbModeNotificationForFunctionalityWithCoalescing(
                NAVIGATION_NOT_AVAILABLE_DURING_DUMB_MODE,
                DumbModeBlockedFunctionality.GotoDeclarationOrUsage,
                DUMB_MODE_NOTIFICATION_KEY,
            )
        }
    }

    /**
     * Handle the "Go To Declaration" action result by either navigating to a single target
     * or displaying a popup with multiple target variants available for navigation.
     */
    @RequiresBlockingContext
    @RequiresEdt
    private fun gotoDeclarationOnly(
        project: Project,
        editor: Editor,
        actionResult: NavigationActionResult,
    ) {
        when (actionResult) {
            is SingleTarget -> {
                // Just trigger our custom navigation function for the single navigatable target
                navigateToRequestor(project, actionResult.requestor, editor)
                LOG.debug { "gotoDeclarationOnly - SingleTarget" }
            }

            is MultipleTargets -> {
                // Create popup for the user to select a navigatable target
                val popup: JBPopup = createTargetPopup(
                    DECLARATION_NAVIGATION_TITLE,
                    actionResult.targets, LazyTargetWithPresentation::presentation,
                ) { (requestor, _, _) ->
                    // We are inside the processor of the created popup. It is called when the user selects a navigatable target from the popup
                    // Trigger our custom navigation function for the selected navigatable target
                    navigateToRequestor(project, requestor, editor)
                }
                popup.showInBestPositionFor(editor)
                LOG.debug { "gotoDeclarationOnly - MultipleTargets" }
            }
        }
    }

    /**
     * Handle the "Show Usages" action result by either navigating to a single target
     * or displaying a popup with multiple target variants available for navigation.
     */
    private fun showUsages(
        project: Project,
        editor: Editor,
        file: PsiFile,
        targetVariants: List<Any>,
    ) {

        // Build DataContext for scope resolution
        val dataContext: DataContext = SimpleDataContext.builder().add(CommonDataKeys.PSI_FILE, file).add(CommonDataKeys.EDITOR, editor)
            .add(PlatformCoreDataKeys.CONTEXT_COMPONENT, editor.contentComponent).build()

        try {
            val popupPosition: RelativePoint = JBPopupFactory.getInstance().guessBestPopupLocation(editor)
            val defaultScopeName: String? = FindUsagesSettings.getInstance().defaultScopeName

            val searchScope: SearchScope = resolveStableSearchScope(project, defaultScopeName)
                ?: FindUsagesOptions.findScopeByName(
                    project,
                    dataContext,
                    defaultScopeName,
                )

            findShowUsagesSplit(
                project, editor, popupPosition, targetVariants, SHOW_USAGES_AMBIGUOUS_TITLE,
                createVariantHandler(
                    project, editor, popupPosition,
                    searchScope,
                ),
            )
        } catch (_: IndexNotReadyException) {
            DumbService.getInstance(project).showDumbModeNotificationForFunctionalityWithCoalescing(
                NAVIGATION_NOT_AVAILABLE_DURING_DUMB_MODE,
                DumbModeBlockedFunctionality.GotoDeclarationOrUsage,
                DUMB_MODE_NOTIFICATION_KEY,
            )
        }
    }
}
