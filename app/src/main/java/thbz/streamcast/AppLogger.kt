package thbz.streamcast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String
)

object AppLogger {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    @Synchronized
    internal fun addLegacyLog(entry: LogEntry) {
        val currentList = _logs.value
        val newList = if (currentList.size >= 200) {
            currentList.drop(currentList.size - 199) + entry
        } else {
            currentList + entry
        }
        _logs.value = newList
    }

    internal fun clearLegacy() {
        _logs.value = emptyList()
    }

    fun d(tag: String, message: String) {
        LoggingModule.d(tag, message)
    }

    fun v(tag: String, message: String) {
        LoggingModule.v(tag, message)
    }

    fun i(tag: String, message: String) {
        LoggingModule.i(tag, message)
    }

    fun w(tag: String, message: String) {
        LoggingModule.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        LoggingModule.e(tag, message, throwable)
    }

    fun clear() {
        LoggingModule.clear()
    }
}

