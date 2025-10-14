// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("NOTHING_TO_INLINE")

package org.insilications.openinsplit.find.actions

import com.intellij.find.usages.api.SearchTarget
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts.PopupTitle
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import org.insilications.openinsplit.debug
import org.jetbrains.annotations.ApiStatus
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Proxy

val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")

private const val PLATFORM_RESOLVER_CLASS = "com.intellij.find.actions.ResolverKt"
private const val PLATFORM_USAGE_VARIANT_HANDLER = "com.intellij.find.actions.UsageVariantHandler"

private val platformUsageVariantHandlerClassCached: Class<*>? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    try {
        Class.forName(PLATFORM_USAGE_VARIANT_HANDLER)
    } catch (t: Throwable) {
        LOG.warn("Failed to resolve com.intellij.find.actions.UsageVariantHandler class.", t)
        null
    }
}

val platformFindShowUsagesInvokerCached: MethodHandle? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    val platformUsageVariantHandlerClass: Class<*> = platformUsageVariantHandlerClassCached ?: return@lazy null
    try {
        val resolverClass: Class<*> = Class.forName(PLATFORM_RESOLVER_CLASS)
        MethodHandles.publicLookup().findStatic(
            resolverClass,
            "findShowUsages",
            MethodType.methodType(
                Void.TYPE,
                Project::class.java,
                Editor::class.java,
                RelativePoint::class.java,
                List::class.java,
                String::class.java,
                platformUsageVariantHandlerClass,
            ),
        )
    } catch (t: Throwable) {
        LOG.warn("Failed to resolve com.intellij.find.actions.ResolverKt.findShowUsages via reflection.", t)
        null
    }
}

interface UsageVariantHandler {
    @ApiStatus.Internal
    fun handleTarget(target: SearchTarget)
    fun handlePsi(element: PsiElement)
}

inline fun findShowUsages(
    project: Project,
    editor: Editor?,
    popupPosition: RelativePoint,
    allTargets: List<Any>,
    @PopupTitle popupTitle: String,
    handler: UsageVariantHandler,
) {
    val platformFindShowUsagesInvoker: MethodHandle? = platformFindShowUsagesInvokerCached
    if (platformFindShowUsagesInvoker == null) {
        LOG.debug { "findShowUsages - Falling back – platformFindShowUsagesInvoker == null." }
        return
    }

    val platformUsageVariantHandlerProxy = createPlatformUsageVariantHandlerProxy(handler)
    if (platformUsageVariantHandlerProxy == null) {
        LOG.debug { "findShowUsages - Falling back – platformUsageVariantHandlerProxy == null." }
        return
    }

    try {
        platformFindShowUsagesInvoker.invokeWithArguments(project, editor, popupPosition, allTargets, popupTitle, platformUsageVariantHandlerProxy)
    } catch (t: Throwable) {
        LOG.warn("Failed to delegate to platform com.intellij.find.actions.ResolverKt.findShowUsages.", t)
    }
}

fun createPlatformUsageVariantHandlerProxy(handler: UsageVariantHandler): Any? {
    val platformUsageVariantHandlerClass: Class<*> = platformUsageVariantHandlerClassCached ?: return null
    return Proxy.newProxyInstance(platformUsageVariantHandlerClass.classLoader, arrayOf(platformUsageVariantHandlerClass)) { proxy, method, args ->
        when (method.name) {
            "handleTarget" -> {
                val target: SearchTarget = args?.getOrNull(0) as? SearchTarget ?: return@newProxyInstance null
                handler.handleTarget(target)
                null
            }

            "handlePsi" -> {
                val element: PsiElement = args?.getOrNull(0) as? PsiElement ?: return@newProxyInstance null
                handler.handlePsi(element)
                null
            }

            "toString" -> "UsageVariantHandlerProxy(${handler::class.java.name})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else -> method.defaultValue
        }
    }
}
