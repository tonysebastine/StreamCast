package thbz.streamcast

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogCategory {
    SYSTEM,
    DISCOVERY,
    CONTROLLER,
    SERVER,
    BROWSER
}

data class StructuredLogEntry(
    val timestamp: String,
    val level: LogLevel,
    val category: LogCategory,
    val tag: String,
    val message: String,
    val threadName: String,
    val throwable: Throwable? = null
)

object LoggingModule {
    private val _logs = MutableStateFlow<List<StructuredLogEntry>>(emptyList())
    val logs: StateFlow<List<StructuredLogEntry>> = _logs.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun mapTagToCategory(tag: String): LogCategory {
        val lower = tag.lowercase(Locale.getDefault())
        return when {
            lower.contains("discovery") || lower.contains("ssdp") || lower.contains("dial") -> LogCategory.DISCOVERY
            lower.contains("controller") || lower.contains("roku") || lower.contains("viewmodel") -> LogCategory.CONTROLLER
            lower.contains("httpserver") || lower.contains("server") -> LogCategory.SERVER
            lower.contains("sniffer") || lower.contains("browser") || lower.contains("webview") -> LogCategory.BROWSER
            else -> LogCategory.SYSTEM
        }
    }

    @Synchronized
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        val threadName = Thread.currentThread().name
        val category = mapTagToCategory(tag)
        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }

        // Consistent, structured schema for console logging
        val formattedLog = "[$category] [$tag] ($threadName): $fullMessage"

        // Print structured output to Android logcat
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, formattedLog)
            LogLevel.INFO -> Log.i(tag, formattedLog)
            LogLevel.WARN -> Log.w(tag, formattedLog)
            LogLevel.ERROR -> Log.e(tag, formattedLog)
        }

        val entry = StructuredLogEntry(
            timestamp = dateFormat.format(Date()),
            level = level,
            category = category,
            tag = tag,
            message = fullMessage,
            threadName = threadName,
            throwable = throwable
        )

        // Store internally in structured list with a cap of 300 logs
        val currentList = _logs.value
        val newList = if (currentList.size >= 300) {
            currentList.drop(currentList.size - 299) + entry
        } else {
            currentList + entry
        }
        _logs.value = newList

        // Synchronize with legacy AppLogger to maintain compatibility with existing UI
        val timeForLegacy = entry.timestamp.substringAfter(" ") // Keep HH:mm:ss.SSS format for legacy UI
        val legacyTag = "${entry.category}/${entry.tag}"
        AppLogger.addLegacyLog(
            LogEntry(
                timestamp = timeForLegacy,
                level = entry.level,
                tag = legacyTag,
                message = entry.message
            )
        )
    }

    fun d(tag: String, message: String) {
        log(LogLevel.DEBUG, tag, message)
    }

    fun v(tag: String, message: String) {
        log(LogLevel.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        log(LogLevel.INFO, tag, message)
    }

    fun w(tag: String, message: String) {
        log(LogLevel.WARN, tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(LogLevel.ERROR, tag, message, throwable)
    }

    // Unified interface for retrieving all logs
    fun getLogs(): List<StructuredLogEntry> {
        return _logs.value
    }

    fun clear() {
        _logs.value = emptyList()
        AppLogger.clearLegacy()
        log(LogLevel.INFO, "System", "Centralized logs cleared.")
    }
}
