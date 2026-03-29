package com.astra.wakeup

import android.app.Application
import android.content.Context
import android.text.format.DateFormat
import kotlin.system.exitProcess

private const val PREFS_NAME = "astra_crash"
private const val KEY_PENDING = "pending"
private const val KEY_TIMESTAMP = "timestamp_ms"
private const val KEY_ORIGIN = "origin"
private const val KEY_THREAD = "thread"
private const val KEY_CLASS = "exception_class"
private const val KEY_MESSAGE = "exception_message"
private const val KEY_STACK = "stack_top"

data class AstraCrashReport(
    val timestampMs: Long,
    val origin: String,
    val threadName: String,
    val exceptionClass: String,
    val message: String,
    val stackTop: String,
)

object AstraCrashStore {
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(app, origin = "uncaught", threadName = thread.name, throwable = throwable)
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun record(context: Context, origin: String, throwable: Throwable, threadName: String = Thread.currentThread().name) {
        val root = generateSequence(throwable) { it.cause }.last()
        val stack = buildString {
            throwable.stackTrace.take(8).forEachIndexed { index, element ->
                if (index > 0) append('\n')
                append("at ")
                append(element.className)
                append('.')
                append(element.methodName)
                append('(')
                append(element.fileName ?: "UnknownSource")
                append(':')
                append(element.lineNumber)
                append(')')
            }
            if (root !== throwable && root.stackTrace.isNotEmpty()) {
                append("\ncaused by ")
                append(root::class.java.name)
                append(": ")
                append(root.message ?: "(no message)")
            }
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PENDING, true)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_ORIGIN, origin)
            .putString(KEY_THREAD, threadName)
            .putString(KEY_CLASS, root::class.java.name)
            .putString(KEY_MESSAGE, root.message ?: throwable.message ?: "(no message)")
            .putString(KEY_STACK, stack)
            .apply()
    }

    fun consume(context: Context): AstraCrashReport? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING, false)) return null
        val report = AstraCrashReport(
            timestampMs = prefs.getLong(KEY_TIMESTAMP, 0L),
            origin = prefs.getString(KEY_ORIGIN, "unknown") ?: "unknown",
            threadName = prefs.getString(KEY_THREAD, "unknown") ?: "unknown",
            exceptionClass = prefs.getString(KEY_CLASS, "unknown") ?: "unknown",
            message = prefs.getString(KEY_MESSAGE, "(no message)") ?: "(no message)",
            stackTop = prefs.getString(KEY_STACK, "(no stack)") ?: "(no stack)",
        )
        prefs.edit().clear().apply()
        return report
    }

    fun formatForUi(report: AstraCrashReport): String {
        val ts = if (report.timestampMs > 0L) {
            DateFormat.format("yyyy-MM-dd HH:mm:ss z", report.timestampMs).toString()
        } else {
            "unknown time"
        }
        return buildString {
            append("Captured crash:\n")
            append(report.exceptionClass)
            append("\n")
            append(report.message)
            append("\n\n")
            append("origin=")
            append(report.origin)
            append(" | thread=")
            append(report.threadName)
            append(" | at=")
            append(ts)
            append("\n\nTop stack:\n")
            append(report.stackTop.take(1600))
        }
    }
}
