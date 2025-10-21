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
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")

private const val PLATFORM_RESOLVER_CLASS = "com.intellij.find.actions.ResolverKt"
private const val PLATFORM_USAGE_VARIANT_HANDLER = "com.intellij.find.actions.UsageVariantHandler"

// Ensure we do not spam reflection after a platform lookup failure; retry after this delay.
private const val LOOKUP_RETRY_BACKOFF_MS: Long = 5_000

private val platformUsageVariantHandlerClassCached: Class<*>? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    try {
        Class.forName(PLATFORM_USAGE_VARIANT_HANDLER)
    } catch (t: Throwable) {
        LOG.warn("Failed to resolve com.intellij.find.actions.UsageVariantHandler class.", t)
        null
    }
}

// Guard for the slow-path reflective lookup. Reads rely on `@Volatile` so the hot path stays lock-free.
private val findShowUsagesInvokerLock = Any()

@Volatile
private var findShowUsagesInvoker: FindShowUsagesInvoker? = null

@Volatile
private var nextFindShowUsagesLookupRetryAtMillis: Long = 0

/**
 * Memoized adapter around the platform's `ResolverKt.findShowUsages` static entry point.
 * We keep the signature Kotlin-friendly while treating the final parameter as `Any` so we can
 * pass the JDK proxy without depending on the internal `UsageVariantHandler` class.
 */
fun interface FindShowUsagesInvoker {
    fun invoke(
        project: Project,
        editor: Editor?,
        popupPosition: RelativePoint,
        allTargets: List<Any>,
        popupTitle: String,
        handler: Any,
    )
}

interface UsageVariantHandlerSplit {
    @ApiStatus.Internal
    fun handleTarget(target: SearchTarget)
    fun handlePsi(element: PsiElement)
}

inline fun findShowUsagesSplit(
    project: Project,
    editor: Editor?,
    popupPosition: RelativePoint,
    allTargets: List<Any>,
    @PopupTitle popupTitle: String,
    handler: UsageVariantHandlerSplit,
) {
    // Reflection can misfire during early startup; defer to our retrying resolver so this action
    // automatically recovers once the platform class is ready.
    val platformFindShowUsagesInvoker: FindShowUsagesInvoker = resolveFindShowUsagesInvoker() ?: run {
        LOG.debug { "findShowUsages - falling back; invoker unavailable." }
        return
    }

    val platformUsageVariantHandlerProxy = createPlatformUsageVariantHandlerProxy(handler)
    if (platformUsageVariantHandlerProxy == null) {
        LOG.debug { "findShowUsages - Falling back – platformUsageVariantHandlerProxy == null." }
        return
    }

    try {
        platformFindShowUsagesInvoker.invoke(project, editor, popupPosition, allTargets, popupTitle, platformUsageVariantHandlerProxy)
    } catch (t: Throwable) {
        LOG.warn("Failed to delegate to the platform's 'com.intellij.find.actions.ResolverKt.findShowUsages'", t)
    }
}

// Each platform implementation shares the same handler Method instances; cache them so the
// InvocationHandler can branch by identity instead of re-checking strings.
private val usageVariantHandlerProxyMethodsCache = ConcurrentHashMap<Class<*>, UsageVariantHandlerProxyMethods>()

private val OBJECT_EQUALS_METHOD: Method = Any::class.java.getMethod("equals", Any::class.java)
private val OBJECT_HASHCODE_METHOD: Method = Any::class.java.getMethod("hashCode")
private val OBJECT_TOSTRING_METHOD: Method = Any::class.java.getMethod("toString")

fun createPlatformUsageVariantHandlerProxy(handler: UsageVariantHandlerSplit): Any? {
    val platformUsageVariantHandlerClass: Class<*> = platformUsageVariantHandlerClassCached ?: return null
    val proxyMethods: UsageVariantHandlerProxyMethods = usageVariantHandlerProxyMethodsCache.getOrPut(platformUsageVariantHandlerClass) {
        // Discover the single-argument callback methods once. This executes at most the first time
        // the proxy is created for a given classloader; afterwards we reuse the cached `Method`s.
        val handleTarget: Method = platformUsageVariantHandlerClass.methods.firstOrNull { it.name == "handleTarget" && it.parameterCount == 1 } ?: return null
        val handlePsi: Method = platformUsageVariantHandlerClass.methods.firstOrNull { it.name == "handlePsi" && it.parameterCount == 1 } ?: return null
        UsageVariantHandlerProxyMethods(handleTarget, handlePsi)
    }

    return Proxy.newProxyInstance(platformUsageVariantHandlerClass.classLoader, arrayOf(platformUsageVariantHandlerClass)) { proxy, method, args ->
        when (method) {
            proxyMethods.handleTarget -> {
                // Defensive guard: the JDK may pass null args if the signature mismatches; we also
                // double-check the element type to avoid ClassCastException surfacing to users.
                val arguments: Array<out Any?> = args ?: return@newProxyInstance null
                val target: SearchTarget = arguments.firstOrNull() as? SearchTarget ?: return@newProxyInstance null
                handler.handleTarget(target)
                null
            }

            proxyMethods.handlePsi -> {
                // Same as above but for the PsiElement path.
                val arguments: Array<out Any?> = args ?: return@newProxyInstance null
                val element: PsiElement = arguments.firstOrNull() as? PsiElement ?: return@newProxyInstance null
                handler.handlePsi(element)
                null
            }

            OBJECT_TOSTRING_METHOD -> "UsageVariantHandlerProxy(${handler::class.java.name})"
            OBJECT_HASHCODE_METHOD -> System.identityHashCode(proxy)
            OBJECT_EQUALS_METHOD -> proxy === args?.firstOrNull()
            else -> method.defaultValue
        }
    }
}

// Mirrors the retry/backoff strategy used by the GTDU resolver in `resolveGotoDeclarationOrUsagesInvoker` from `GotoDeclarationOrUsageHandler2Split.kt`
// This strategy avoids repeated reflective scans under PSI locks. The happy path returns immediately thanks to the `@Volatile` cache.
fun resolveFindShowUsagesInvoker(): FindShowUsagesInvoker? {
    findShowUsagesInvoker?.let { return it }

    val now: Long = System.currentTimeMillis()
    if (now < nextFindShowUsagesLookupRetryAtMillis) return null

    return synchronized(findShowUsagesInvokerLock) {
        findShowUsagesInvoker?.let { return it }
        if (System.currentTimeMillis() < nextFindShowUsagesLookupRetryAtMillis) return@synchronized null

        val platformUsageVariantHandlerClass: Class<*> = platformUsageVariantHandlerClassCached ?: return@synchronized null
        try {
            val resolverClass: Class<*> = Class.forName(PLATFORM_RESOLVER_CLASS)
            // Locate `ResolverKt.findShowUsages` with the exact parameter list generated by the
            // Kotlin compiler. Any signature drift will throw here and trigger the retry backoff.
            val rawMethodHandle: MethodHandle = MethodHandles.publicLookup().findStatic(
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
            val typedMethodHandle: MethodHandle = rawMethodHandle.asType(
                MethodType.methodType(
                    Void.TYPE,
                    Project::class.java,
                    Editor::class.java,
                    RelativePoint::class.java,
                    List::class.java,
                    String::class.java,
                    Any::class.java,
                ),
            )

            val invoker = FindShowUsagesInvoker { project, editor, popupPosition, allTargets, popupTitle, handler ->
                // MethodHandle#invoke keeps call-site polymorphism intact and avoids the array
                // allocation that InvokeWithArguments would pay on every invocation.
                typedMethodHandle.invoke(project, editor, popupPosition, allTargets, popupTitle, handler)
            }
            findShowUsagesInvoker = invoker
            nextFindShowUsagesLookupRetryAtMillis = 0
            invoker
        } catch (t: Throwable) {
            // Log once, then delay subsequent lookup attempts so we do not hammer classloading.
            LOG.warn("Failed to resolve com.intellij.find.actions.ResolverKt.findShowUsages via reflection.", t)
            nextFindShowUsagesLookupRetryAtMillis = System.currentTimeMillis() + LOOKUP_RETRY_BACKOFF_MS
            null
        }
    }
}

// Simple holder for the reflective callbacks we dispatch on; keeping them together keeps the
// ConcurrentHashMap value small and intention-revealing.
private data class UsageVariantHandlerProxyMethods(
    val handleTarget: Method,
    val handlePsi: Method,
)
