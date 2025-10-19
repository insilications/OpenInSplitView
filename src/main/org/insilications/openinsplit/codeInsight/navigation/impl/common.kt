// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("NOTHING_TO_INLINE")

package org.insilications.openinsplit.codeInsight.navigation.impl


import com.intellij.codeInsight.TargetElementUtil
import com.intellij.ide.DataManager
import com.intellij.ide.IdeBundle
import com.intellij.ide.util.EditSourceUtil
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.platform.ide.navigation.NavigationOptions
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.jetbrains.annotations.ApiStatus
import java.awt.Component

@ApiStatus.Experimental
val navigationOptionsRequestFocus: NavigationOptions = NavigationOptions.requestFocus()
val progressTitlePreparingNavigation: String = IdeBundle.message("progress.title.preparing.navigation")

inline fun PsiElement.gtdTargetNavigatable(): Navigatable? {
    return TargetElementUtil.getInstance().getGotoDeclarationTarget(this, navigationElement)?.psiNavigatable()
}

inline fun PsiElement.psiNavigatable(): Navigatable? {
    return this as? Navigatable ?: EditSourceUtil.getDescriptor(this)
}

@RequiresEdt
inline fun fetchDataContext(project: Project): DataContext? {
    val component: Component? = IdeFocusManager.getInstance(project).focusOwner
    return component?.let { DataManager.getInstance().getDataContext(it) }
}
