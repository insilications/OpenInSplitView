package org.insilications.openinsplit.custom

import com.intellij.driver.sdk.WaitForException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.fileLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val LOG: Logger get() = fileLogger()

fun waitForIt(
    message: String? = null,
    timeout: Duration = 5.seconds,
    interval: Duration = 1.seconds,
    errorMessage: (() -> String)? = null,
    condition: () -> Boolean,
) {
    waitForIt2(
        message = message,
        timeout = timeout,
        interval = interval,
        getter = condition,
        checker = { it })
}

private inline fun <T> waitForIt2(
    message: String? = null,
    timeout: Duration = 5.seconds,
    interval: Duration = 1.seconds,
    getter: () -> T,
    checker: (T) -> Boolean,
): T {
//    logAwaitStart(message, timeout)
    val startTime: Long = System.currentTimeMillis()
    val endTime: Long = startTime + timeout.inWholeMilliseconds
    var result: T = getter()
    while (endTime > System.currentTimeMillis() && checker(result).not()) {
        Thread.sleep(interval.inWholeMilliseconds)
        result = getter()
    }
    if (checker(result).not()) {
        throw WaitForException(
            timeout,
            errorMessage = ("Failed: $message" + if (result !is Boolean) ". Actual: $result" else "")
        ).also { LOG.warn(it) }
    } else {
//        val passedTime = (System.currentTimeMillis() - startTime).milliseconds
//        if (result !is Boolean || passedTime > 10.seconds) {
//            logAwaitFinish(message, result, passedTime)
//        }
        return result
    }
}