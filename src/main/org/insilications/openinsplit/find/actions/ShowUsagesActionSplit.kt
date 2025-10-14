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

        private val createShowTargetUsagesActionHandlerCachedInvoker: ((Project, SearchScope, SearchTarget) -> ShowUsagesActionHandler)? by lazy(
            LazyThreadSafetyMode.PUBLICATION,
        ) {
            try {
                // 1. Get the private method from the ShowUsagesAction class.
                // We use `getDeclaredMethod` because the method is not public.
                val method: Method = ShowUsagesAction::class.java.getDeclaredMethod(
                    "createActionHandler",
                    Project::class.java,
                    SearchScope::class.java,
                    SearchTarget::class.java,
                ).apply {
                    // 2. Make it accessible. This is crucial for private members.
                    isAccessible = true
                }

                // 2. Create a `MethodHandle` with a specific type using `.asType(...)`.
                // This provides a runtime guarantee and acts as an assertion during initialization.
                // Additionally, the method is static, we do not need to bind it to an instance.
                // By creating an adapted handle with a specific type, you provide more information
                // to the JVM's JIT compiler, which can lead to better performance optimizations
                // compared to a completely generic handle.
                val handle: MethodHandle = MethodHandles.lookup().unreflect(method).asType(
                    MethodType.methodType(
                        ShowUsagesActionHandler::class.java,
                        Project::class.java,
                        SearchScope::class.java,
                        SearchTarget::class.java,
                    ),
                );

                // 3. Return a strongly-typed lambda that wraps the handle.
                // The result is cast to the known return type for type safety.
                { project: Project, searchScope: SearchScope, target: SearchTarget ->
                    // Call the handle. Because `invokeWithArguments` is statically typed to
                    // return `Object`, we still need a cast to satisfy the Kotlin compiler.
                    // This cast is safe because of the `.asType(...)` guarantee above.
                    handle.invokeWithArguments(project, searchScope, target) as ShowUsagesActionHandler
                }
            } catch (t: Throwable) {
                LOG.warn("Failed to resolve ShowUsagesAction.createActionHandler via reflection.", t)
                // If reflection fails for any reason, the invoker will be null.
                null
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
            val createShowTargetUsagesActionHandlerInvoker: (Project, SearchScope, SearchTarget) -> ShowUsagesActionHandler =
                createShowTargetUsagesActionHandlerCachedInvoker ?: return
            val showTargetUsagesActionHandler: ShowUsagesActionHandler = try {
                createShowTargetUsagesActionHandlerInvoker(project, searchScope, target)
            } catch (t: Throwable) {
                LOG.warn("Failed to invoke createShowTargetUsagesActionHandlerInvoker", t)
                return
            }
            showElementUsages(parameters, showTargetUsagesActionHandler)
        }
    }
}
