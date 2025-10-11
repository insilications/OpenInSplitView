@file:Suppress("NOTHING_TO_INLINE")

package org.insilications.openinsplit

import com.intellij.openapi.diagnostic.Logger

val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")

inline fun Logger.debug(lazyMessage: () -> String) {
    if (isDebugEnabled) {
        debug(lazyMessage())
    }
}
