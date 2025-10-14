// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("NOTHING_TO_INLINE")

package org.insilications.openinsplit.codeInsight.navigation.actions

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.TargetElementUtil.targetElementFromLookupElement
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupEx
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.navigation.impl.NavigationRequestor
import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.ide.util.EditSourceUtil
import com.intellij.lang.LanguageNamesValidation
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.registry.RegistryManager
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.impl.RawNavigationRequest
import com.intellij.platform.backend.navigation.impl.SourceNavigationRequest
import com.intellij.platform.ide.navigation.NavigationService
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.concurrency.annotations.RequiresBlockingContext
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.insilications.openinsplit.codeInsight.navigation.impl.fetchDataContext
import org.insilications.openinsplit.codeInsight.navigation.impl.gtdTargetNavigatable
import org.insilications.openinsplit.codeInsight.navigation.impl.navigationOptionsRequestFocus
import org.insilications.openinsplit.codeInsight.navigation.impl.progressTitlePreparingNavigation
import org.insilications.openinsplit.debug
import org.jetbrains.annotations.ApiStatus
import java.awt.AWTEvent
import java.awt.event.MouseEvent
import javax.swing.SwingConstants

val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")

@PublishedApi
internal val LOOKUP_TARGET_POINTER_KEY: Key<SmartPsiElementPointer<PsiElement>> = Key.create("org.insilications.openinsplit.lookupTargetPointer")

@PublishedApi
internal val SHOW_ERROR_REGISTRY_VALUE: RegistryValue by lazy(LazyThreadSafetyMode.PUBLICATION) {
    RegistryManager.getInstance().get("ide.gtd.show.error")
}

data class KeywordCheck(
    val psiFile: PsiFile,
    val documentModStamp: Long,
    val offset: Int,
    val isKeyword: Boolean,
)

@PublishedApi
internal val KEYWORD_CHECK_KEY: Key<KeywordCheck> = Key.create("org.insilications.openinsplit.keywordUnderCaretCache")

/**
 * This function is used to preemptively set the current split view (window) to the adjacent split view or a new split view.
 * This forces the calls to `navigate` methods to use that adjacent split view. This workaround might be fragile, but it works perfectly.
 *
 * If `nextEditorWindow` equals `activeEditorWindow`, then there is no split view immediately to the right of the active tab's split view.
 * In this case, we create a new split view immediately to the right of the active tab's split view, with `focusNew` parameter set to `true`.
 *
 * If `nextEditorWindow` is different from `activeEditorWindow`, then there is a split view immediately to the right of the active tab's split view.
 * So we set `nextEditorWindow` as the current window using the `setAsCurrentWindow` method with the `requestFocus` parameter set to `true`.
 *
 * @param project
 * @param getFile A suspend lambda that returns `VirtualFile?`, so that the new split view immediately opens the file that contains the target element.
 */
@RequiresEdt
suspend inline fun getAdjacentSplitView(
    project: Project,
    getFile: suspend () -> VirtualFile?,
) {
    val fileEditorManager: FileEditorManagerEx = FileEditorManagerEx.getInstanceExAsync(project)
    val activeEditorWindow: EditorWindow = fileEditorManager.currentWindow ?: return
    val nextEditorWindow: EditorWindow? = fileEditorManager.getNextWindow(activeEditorWindow)

    if (nextEditorWindow == activeEditorWindow) {
        // Create a new split view immediately to the right of `activeEditorWindow` and focus on it.
        // If the `file` is `null`, then the new split view will have the same file as `activeEditorWindow`.
        activeEditorWindow.split(SwingConstants.VERTICAL, true, getFile(), true)
        LOG.debug { "nextEditorWindow == activeEditorWindow" }
    } else if (nextEditorWindow != null) {
        nextEditorWindow.setAsCurrentWindow(true)
        LOG.debug { "nextEditorWindow != activeEditorWindow - nextEditorWindow != null" }
    } else {
        LOG.debug { "nextEditorWindow != activeEditorWindow - nextEditorWindow == null" }
    }
}

/**
 * This function is used to preemptively set the current split view (window) to the adjacent split view or a new split view.
 * This forces the calls to `navigate` methods to use that adjacent split view. This workaround might be fragile, but it works perfectly.
 *
 * If `nextEditorWindow` equals `activeEditorWindow`, then there is no split view immediately to the right of the active tab's split view.
 * In this case, we create a new split view immediately to the right of the active tab's split view, with `focusNew` parameter set to `true`.
 *
 * If `nextEditorWindow` is different from `activeEditorWindow`, then there is a split view immediately to the right of the active tab's split view.
 * So we set `nextEditorWindow` as the current window using the `setAsCurrentWindow` method with the `requestFocus` parameter set to `true`.
 *
 * @param project
 * @param file A `VirtualFile?`, so that the new split view immediately opens the file that contains the target element.
 */
@RequiresEdt
suspend inline fun getAdjacentSplitView(
    project: Project,
    file: VirtualFile?,
) {
    val fileEditorManager: FileEditorManagerEx = FileEditorManagerEx.getInstanceExAsync(project)
    val activeEditorWindow: EditorWindow = fileEditorManager.currentWindow ?: return
    val nextEditorWindow: EditorWindow? = fileEditorManager.getNextWindow(activeEditorWindow)

    if (nextEditorWindow == activeEditorWindow) {
        // Create a new split view immediately to the right of `activeEditorWindow` and focus on it.
        // If the `file` is `null`, then the new split view will have the same file as `activeEditorWindow`.
        activeEditorWindow.split(SwingConstants.VERTICAL, true, file, true)
        LOG.debug { "nextEditorWindow == activeEditorWindow" }
    } else if (nextEditorWindow != null) {
        nextEditorWindow.setAsCurrentWindow(true)
        LOG.debug { "nextEditorWindow != activeEditorWindow - nextEditorWindow != null" }
    } else {
        LOG.debug { "nextEditorWindow != activeEditorWindow - nextEditorWindow == null" }
    }
}

@ApiStatus.Internal
@RequiresBlockingContext
@RequiresEdt
inline fun navigateToLookupItem(project: Project, editor: Editor): Boolean {
    val activeLookup: LookupEx = LookupManager.getInstance(project).activeLookup ?: return false
    val currentItem: LookupElement = activeLookup.currentItem ?: return false
    if (!currentItem.isValid()) {
        currentItem.putUserData(LOOKUP_TARGET_POINTER_KEY, null)
        return false
    }

    val pointerResult: Pair<SmartPsiElementPointer<PsiElement>, Boolean>? = ApplicationManager.getApplication().runReadAction(
        Computable<Pair<SmartPsiElementPointer<PsiElement>, Boolean>?> {
            val cachedPointer: SmartPsiElementPointer<PsiElement>? = currentItem.getUserData(LOOKUP_TARGET_POINTER_KEY)
            val cachedElement: PsiElement? = cachedPointer?.element
            if (cachedElement != null && cachedElement.isValid) {
                cachedPointer to false
            } else {
                val target: PsiElement = targetElementFromLookupElement(currentItem) ?: return@Computable null
                if (!target.isValid) return@Computable null
                SmartPointerManager.getInstance(project).createSmartPsiElementPointer(target) to true
            }
        },
    )

    val pointer: SmartPsiElementPointer<PsiElement>? = pointerResult?.first
    if (pointer == null) {
        currentItem.putUserData(LOOKUP_TARGET_POINTER_KEY, null)
        return false
    }
    if (pointerResult.second) {
        currentItem.putUserData(LOOKUP_TARGET_POINTER_KEY, pointer)
    }

    navigateToRequestor(
        project,
        {
            val element: PsiElement = pointer.element ?: return@navigateToRequestor null
            element.gtdTargetNavigatable()?.navigationRequest()
        },
        editor,
    )
    return true
}

/**
 * Retrieves a navigation request from the provided [requestor] and navigates to it.
 */
@ApiStatus.Internal
@RequiresBlockingContext
@RequiresEdt
inline fun navigateToRequestor(project: Project, requestor: NavigationRequestor, editor: Editor) {
    runWithModalProgressBlocking(project, progressTitlePreparingNavigation) {
        LOG.debug { "navigateToRequestor - requestor is ${requestor::class.simpleName}" }

        val request: NavigationRequest = ProgressManager.getInstance().computePrioritized(
            ThrowableComputable<NavigationRequest?, RuntimeException> {
                ApplicationManager.getApplication().runReadAction(Computable<NavigationRequest?> { requestor.navigationRequest() })
            },
        ) ?: LOG.warn("navigateToRequestor - Failed to create navigation request").let { return@runWithModalProgressBlocking }

        val dataContext: DataContext
        // Switch to EDT for UI side-effects
        withContext(Dispatchers.EDT) {
            // We have implicit write intent lock under `Dispatchers.EDT`, which implies an **IMPLICIT** read lock too
            // Therefore, in the latest Intellij Platform API, we don't need to explictly get a read lock inside EDT
            // This will change in future releases, by shifting to using `Dispatchers.UI`, so keep an eye on it

            // Acquire DataContext on EDT
            dataContext = DataManager.getInstance().getDataContext(editor.component)
            // History update on EDT
            IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
            // We preemptively set the current split view (window) to the adjacent split view or a new split view
            // This forces the calls to `navigate` methods to use that adjacent split view. This workaround might be fragile, but it works perfectly
            getAdjacentSplitView(project) {
                getVirtualFileFromNavigationRequest(request)
            }
        }

        // Delegate to the platform's `IdeNavigationService.kt` to perform actual navigation
        project.serviceAsync<NavigationService>().navigate(request, navigationOptionsRequestFocus, dataContext)
    }
}

/**
 * Retrieves a navigation request from the provided [navigatable] and navigates to it.
 */
@ApiStatus.Internal
@RequiresEdt
inline fun navigateToNavigatable(project: Project, navigatable: Navigatable, dataContext: DataContext?) {
    runWithModalProgressBlocking(project, progressTitlePreparingNavigation) {
        LOG.debug { "navigateToNavigatable - navigatable is: ${navigatable::class.simpleName}" }

        val dataContextCheck: DataContext?
        // Switch to EDT for UI side-effects
        withContext(Dispatchers.EDT) {
            // We have implicit write intent lock under `Dispatchers.EDT`, which implies an **IMPLICIT** read lock too
            // Therefore, in the latest Intellij Platform API, we don't need to explictly get a read lock inside EDT
            // This will change in future releases, by shifting to using `Dispatchers.UI`, so keep an eye on it

            // Acquire DataContext on EDT
            dataContextCheck = dataContext ?: fetchDataContext(project)
            // History update on EDT
            IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
            // We preemptively set the current split view (window) to the adjacent split view or a new split view
            // This forces the calls to `navigate` methods to use that adjacent split view. This workaround might be fragile, but it works perfectly
            getAdjacentSplitView(project) {
                getVirtualFileFromNavigatable(navigatable)
            }
        }

        // Delegate to the platform's `IdeNavigationService.kt` to perform actual navigation
        project.serviceAsync<NavigationService>().navigate(navigatable, navigationOptionsRequestFocus, dataContextCheck)
    }
}

@ApiStatus.Experimental
suspend fun getVirtualFileFromNavigationRequest(request: NavigationRequest): VirtualFile? {
    return when (request) {
        // `SharedSourceNavigationRequest` is a subclass of `SourceNavigationRequest`.
        is SourceNavigationRequest -> {
            LOG.debug { "getVirtualFileFromNavigationRequest - request is SourceNavigationRequest" }
            request.file
        }

        is RawNavigationRequest -> {
            LOG.debug { "getVirtualFileFromNavigationRequest - request is RawNavigationRequest" }
            getVirtualFileFromNavigatable(request.navigatable)
        }
        // `DirectoryNavigationRequest` is non-source, so we don't need to handle it
        // It can be handled perfectly by `NavigationService.navigate`
        else -> {
            LOG.warn("getVirtualFileFromNavigationRequest - Non-source request: ${request::class.simpleName}")
            null
        }
    }
}

suspend inline fun getVirtualFileFromNavigatable(navigatable: Navigatable): VirtualFile? {
    // 1. OpenFileDescriptor
    // This only accesses the getter `nav.file` (a VirtualFile). This is a VFS-level operation and does not require a read lock
    if (navigatable is OpenFileDescriptor) {
        LOG.debug { "0 getVirtualFileFromNavigatable - navigatable is OpenFileDescriptor" }
        return navigatable.file
    }

    // 2. PSI-based
    if (navigatable is PsiElement) {
        // Try to get a descriptor derived from PSI with the `EditSourceUtil.getDescriptor` method
        // The method makes a best-effort attempt to extract descriptor-like objects of type `Navigatable` and often yields an OpenFileDescriptor
        // This is PSI-level operation and requires a read lock
        val descriptor: Navigatable? = readAction {
            EditSourceUtil.getDescriptor(navigatable)
        }
        when (descriptor) {
            // This only accesses the getter `nav.file` (a VirtualFile). This is VFS-level operation and does not require a read lock
            is OpenFileDescriptor -> {
                LOG.debug { "1 getVirtualFileFromNavigatable - descriptor is OpenFileDescriptor" }
                return descriptor.file
            }

            is PsiElement -> {
                LOG.debug { "2 getVirtualFileFromNavigatable - descriptor is PsiElement" }
                return readAction {
                    // This is PSI-level operation and requires a read lock
                    PsiUtilCore.getVirtualFile(descriptor)
                } ?: LOG.warn("3 getVirtualFileFromNavigatable - returned null").let { return null }
            }
            // 3. Non-PSI, non-descriptor-like object of type `Navigatable` → No recoverable file
            // else -> null
        }
    }
    LOG.warn("4 getVirtualFileFromNavigatable - returned null")
    return null
}

@RequiresEdt
inline fun notifyNowhereToGo(project: Project, editor: Editor, file: PsiFile, offset: Int) {
    if (!SHOW_ERROR_REGISTRY_VALUE.asBoolean()) return
    if (isUnderDoubleClick()) return
    if (isKeywordUnderCaret(project, editor, file, offset)) return

    HintManager.getInstance().showInformationHint(editor, CodeInsightBundle.message("declaration.navigation.nowhere.to.go"))
}

inline fun isUnderDoubleClick(): Boolean {
    val event: AWTEvent = IdeEventQueue.getInstance().trueCurrentEvent
    return event is MouseEvent && event.clickCount == 2
}

inline fun KeywordCheck.matches(file: PsiFile, editor: Editor, offset: Int): Boolean {
    return psiFile == file && this.offset == offset && documentModStamp == editor.document.modificationStamp
}

inline fun isKeywordUnderCaret(project: Project, editor: Editor, file: PsiFile, offset: Int): Boolean {
    val cached = editor.getUserData(KEYWORD_CHECK_KEY)
    if (cached != null && cached.matches(file, editor, offset)) {
        return cached.isKeyword
    }

    val documentModStamp = editor.document.modificationStamp
    val computed = runReadAction {
        val elementAtCaret: PsiElement? = file.findElementAt(offset)
        val isKeyword = if (elementAtCaret != null) {
            val namesValidator = LanguageNamesValidation.INSTANCE.forLanguage(elementAtCaret.language)
            namesValidator.isKeyword(elementAtCaret.text, project)
        } else {
            false
        }
        KeywordCheck(file, documentModStamp, offset, isKeyword)
    }
    editor.putUserData(KEYWORD_CHECK_KEY, computed)
    return computed.isKeyword
}
