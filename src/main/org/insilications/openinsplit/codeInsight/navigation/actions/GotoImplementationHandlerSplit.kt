package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.codeInsight.navigation.GotoImplementationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.navigation.NavigationService
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.pom.Navigatable
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.insilications.openinsplit.codeInsight.navigation.impl.fetchDataContext
import org.insilications.openinsplit.codeInsight.navigation.impl.navigationOptionsRequestFocus
import org.insilications.openinsplit.codeInsight.navigation.impl.progressTitlePreparingNavigation
import org.insilications.openinsplit.debug

class GotoImplementationHandlerSplit : GotoImplementationHandler() {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
    }

    @RequiresEdt
    override fun navigateToElement(project: Project?, descriptor: Navigatable) {
        if (project == null) return

        runWithModalProgressBlocking(project, progressTitlePreparingNavigation) {
            LOG.debug { "GotoImplementationHandlerSplit - navigateToElement - descriptor is ${descriptor::class.simpleName}" }

            val dataContext: DataContext?
            // Switch to EDT for UI side-effects
            withContext(Dispatchers.EDT) {
                // We have implicit write intent lock under `Dispatchers.EDT`, which implies an **IMPLICIT** read lock too
                // Therefore, in the latest Intellij Platform API, we don't need to explictly get a read lock inside EDT
                // This will change in future releases, by shifting to using `Dispatchers.UI`, so keep an eye on it

                // Acquire DataContext on EDT
                dataContext = fetchDataContext(project)
                // History update on EDT
                IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
                // We preemptively set the current split view (window) to the adjacent split view or a new split view
                // This forces subsequent calls to Intellij Platform API's `navigate` methods to use the adjacent split view.
                // This workaround might be fragile, but it works perfectly
                getAdjacentSplitView(project) {
                    getVirtualFileFromNavigatable(descriptor)
                }
            }

            // Delegate the actual navigation to the Intellij Platform API's `navigate` overload at `platform/ide/navigation/impl/IdeNavigationService.kt`
            project.serviceAsync<NavigationService>().navigate(descriptor, navigationOptionsRequestFocus, dataContext)
        }
    }
}