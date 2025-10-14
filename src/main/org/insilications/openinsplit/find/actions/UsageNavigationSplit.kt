// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.insilications.openinsplit.find.actions

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.ide.navigation.NavigationService
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.insilications.openinsplit.codeInsight.navigation.actions.getAdjacentSplitView
import org.insilications.openinsplit.codeInsight.navigation.impl.navigationOptionsRequestFocus
import org.insilications.openinsplit.debug
import org.jetbrains.annotations.ApiStatus

@Service(Service.Level.PROJECT)
@ApiStatus.Internal
class UsageNavigationSplit(private val project: Project, private val cs: CoroutineScope) {
    companion object {
        @JvmStatic
        fun getInstance(project: Project): UsageNavigationSplit = project.getService(UsageNavigationSplit::class.java)

        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
    }

    fun navigateToUsageAndHint(
        usage: Usage,
        onReady: Runnable,
        editor: Editor?,
    ) {
        cs.launch(Dispatchers.EDT) {
            // We have implicit write intent lock under `Dispatchers.EDT`, which implies an **IMPLICIT** read lock too
            // Therefore, in the latest Intellij Platform API, we don't need to explictly get a read lock inside EDT
            // This will change in future releases, by shifting to using `Dispatchers.UI`, so keep an eye on it

            val dataContext: DataContext? = editor?.let {
                DataManager.getInstance().getDataContext(it.component)
            }

            // History update on EDT
            IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
            if (usage is UsageInfo2UsageAdapter) {
                // We preemptively set the current split view (window) to the adjacent split view or a new split view
                // This forces subsequent calls to Intellij Platform API's `navigate` methods to use the adjacent split view.
                // This workaround might be fragile, but it works perfectly
                getAdjacentSplitView(project, usage.file)
                LOG.debug { "0 navigateAndHint - usage is ${usage::class.simpleName}" }
            } else {
                getAdjacentSplitView(project, null)
                LOG.debug { "1 navigateAndHint - usage is ${usage::class.simpleName}" }
            }

            // Delegate the actual navigation to the Intellij Platform API's `navigate` overload at `platform/ide/navigation/impl/IdeNavigationService.kt`
            NavigationService.getInstance(project).navigate(usage, navigationOptionsRequestFocus, dataContext)
            writeIntentReadAction {
                onReady.run()
            }
        }
    }

    fun navigateToUsageInfo(info: UsageInfo, dataContext: DataContext?) {
        cs.launch {
            // The coroutine context is `Dispatchers.Default`
            val (request: NavigationRequest?, file: VirtualFile?) = readAction {
                val file: VirtualFile = info.virtualFile ?: return@readAction null to null
                NavigationRequest.sourceNavigationRequest(info.project, file, info.navigationOffset) to file

            }

            if (request == null) {
                LOG.warn("navigateUsageInfo - Failed to create navigation request")
                return@launch
            }

            withContext(Dispatchers.EDT) {
                // We have implicit write intent lock under `Dispatchers.EDT`, which implies an **IMPLICIT** read lock too
                // Therefore, in the latest Intellij Platform API, we don't need to explictly get a read lock inside EDT
                // This will change in future releases, by shifting to using `Dispatchers.UI`, so keep an eye on it

                // History update on EDT
                IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
                // We preemptively set the current split view (window) to the adjacent split view or a new split view
                // This forces subsequent calls to Intellij Platform API's `navigate` methods to use the adjacent split view.
                // This workaround might be fragile, but it works perfectly
                getAdjacentSplitView(project, file)
            }

            // Delegate the actual navigation to the Intellij Platform API's `navigate` overload at `platform/ide/navigation/impl/IdeNavigationService.kt`
            NavigationService.getInstance(project).navigate(
                request,
                navigationOptionsRequestFocus,
                dataContext,
            )
        }
    }
}