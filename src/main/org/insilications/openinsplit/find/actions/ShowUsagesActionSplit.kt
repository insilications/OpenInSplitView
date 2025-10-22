package org.insilications.openinsplit.find.actions

import com.intellij.find.actions.ShowUsagesAction
import com.intellij.find.usages.api.SearchTarget
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.SearchScope
import com.intellij.ui.awt.RelativePoint
import org.insilications.openinsplit.debug
import org.insilications.openinsplit.find.actions.ShowUsagesAction.showElementUsages
import org.insilications.openinsplit.find.actions.ShowUsagesAction.startFindUsages
import org.jetbrains.annotations.ApiStatus
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Method

class ShowUsagesActionSplit {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")

        @Volatile
        private var createShowTargetUsagesActionHandlerInvoker: CreateShowTargetUsagesActionHandlerInvoker? = null

        @Volatile
        private var nextLookupRetryAtMillis: Long = 0

        private const val LOOKUP_RETRY_BACKOFF_MS: Long = 5_000

        /**
         * Lazily binds `ShowUsagesAction.createActionHandler` via reflection and memoizes the typed invoker. The volatile cache allows uncontended reuse,
         * and the synchronized block ensures only one thread performs the slow reflective lookup. A timestamp-based backoff throttles
         * repeated failures so we avoid scanning the classpath on every invocation during startup.
         */
        private fun resolveCreateShowTargetUsagesActionHandlerInvoker(): CreateShowTargetUsagesActionHandlerInvoker? {
            createShowTargetUsagesActionHandlerInvoker?.let { return it }

            val now: Long = System.currentTimeMillis()
            if (now < nextLookupRetryAtMillis) return null

            return synchronized(this) {
                createShowTargetUsagesActionHandlerInvoker?.let { return it }
                if (System.currentTimeMillis() < nextLookupRetryAtMillis) return@synchronized null

                try {
                    val method: Method = ShowUsagesAction::class.java.getDeclaredMethod(
                        "createActionHandler",
                        Project::class.java,
                        SearchScope::class.java,
                        SearchTarget::class.java,
                    ).apply { isAccessible = true }

                    val handle: MethodHandle = MethodHandles.lookup().unreflect(method).asType(
                        MethodType.methodType(
                            ShowUsagesActionHandler::class.java,
                            Project::class.java,
                            SearchScope::class.java,
                            SearchTarget::class.java,
                        ),
                    )

                    val invoker = CreateShowTargetUsagesActionHandlerInvoker { projectArg, searchScopeArg, targetArg ->
                        @Suppress("UNCHECKED_CAST") handle.invoke(projectArg, searchScopeArg, targetArg) as ShowUsagesActionHandler
                    }
                    createShowTargetUsagesActionHandlerInvoker = invoker
                    nextLookupRetryAtMillis = 0
                    invoker
                } catch (t: Throwable) {
                    LOG.warn("Failed to resolve ShowUsagesAction.createActionHandler via reflection.", t)
                    nextLookupRetryAtMillis = System.currentTimeMillis() + LOOKUP_RETRY_BACKOFF_MS
                    null
                }
            }
        }

        fun createVariantHandler(
            project: Project,
            editor: Editor,
            popupPosition: RelativePoint,
            searchScope: SearchScope,
        ): UsageVariantHandlerSplit {
            return object : UsageVariantHandlerSplit {
                override fun handleTarget(target: SearchTarget) {
                    LOG.debug { "createVariantHandler - handleTarget" }
                    showUsages(
                        project, searchScope, target,
                        ShowUsagesParameters.initial(project, editor, popupPosition),
                    )
                }

                override fun handlePsi(element: PsiElement) {
                    LOG.debug { "createVariantHandler - handlePsi" }
                    startFindUsages(element, popupPosition, editor)
                }
            }
        }

        @ApiStatus.Experimental
        fun showUsages(project: Project, searchScope: SearchScope, target: SearchTarget, parameters: ShowUsagesParameters) {
            val invoker: CreateShowTargetUsagesActionHandlerInvoker = resolveCreateShowTargetUsagesActionHandlerInvoker() ?: return
            val showTargetUsagesActionHandler: ShowUsagesActionHandler = try {
                invoker.invoke(project, searchScope, target)
            } catch (t: Throwable) {
                LOG.warn("Failed to invoke ShowUsagesAction.createActionHandler", t)
                return
            }
            showElementUsages(parameters, showTargetUsagesActionHandler)
        }

        @ApiStatus.Experimental
        fun interface CreateShowTargetUsagesActionHandlerInvoker {
            fun invoke(project: Project, searchScope: SearchScope, target: SearchTarget): ShowUsagesActionHandler
        }
    }
}
