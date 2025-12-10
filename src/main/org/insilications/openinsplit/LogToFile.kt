package org.insilications.openinsplit

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.RollingFileHandler
import java.nio.file.Path
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.Logger.getLogger

object LogToFile {
    private val handler: RollingFileHandler
    private val julLogger: Logger

    init {
        // Create log file path in the IDE's log directory
        val logPath: Path = Path.of(PathManager.getLogPath()).resolve("symbols.log")

        handler = RollingFileHandler(
            logPath = logPath, limit = Long.MAX_VALUE, count = 1, append = true
        )

        // Custom formatter that outputs only the message
        handler.formatter = object : Formatter() {
            override fun format(record: LogRecord): String {
                return "${record.message}"
            }
        }
        handler.level = Level.ALL

        // Create a dedicated java.util.logging. Logger
        julLogger = getLogger("org.insilications.openinsplit")
        julLogger.addHandler(handler)
        julLogger.level = Level.ALL
        // Don't propagate to parent loggers
        julLogger.useParentHandlers = false
    }

    fun info(message: String) {
        julLogger.info(message)
    }

    fun debug(message: String) {
        julLogger.fine(message)
    }

    fun warn(message: String) {
        julLogger.warning(message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        julLogger.log(Level.SEVERE, message, throwable)
    }

    /**
     * Logs with a custom level
     */
    fun log(level: Level, message: String, throwable: Throwable? = null) {
        julLogger.log(level, message, throwable)
    }

    /**
     * Flushes any buffered log entries to disk
     */
    fun flush() {
        handler.flush()
    }

    /**
     * Returns the path to the log file
     */
    fun getLogFilePath(): Path {
        return handler.logPath
    }
}