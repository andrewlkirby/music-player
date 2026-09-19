package com.musicplayer.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to internal storage before handing off to the
 * platform's default handler, so a crash that happens away from a debugger
 * (e.g. driving with the phone connected over Bluetooth) leaves behind a log
 * that can be shared afterward instead of just "the app closed".
 */
class CrashHandler private constructor(
    private val context: Context,
    private val logDir: File,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            writeCrashLog(thread, throwable)
        } catch (_: Throwable) {
            // Logging must never be the reason a crash goes unreported.
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            "unknown"
        }
        val header = buildString {
            appendLine("Time: ${Date()}")
            appendLine("App version: $versionName")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine()
        }
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        File(logDir, "crash_$timestamp.txt").writeText(header + stackTrace)
        pruneOldLogs()
    }

    private fun pruneOldLogs() {
        logDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_LOGS)
            ?.forEach { it.delete() }
    }

    companion object {
        private const val MAX_LOGS = 10

        fun install(context: Context) {
            val appContext = context.applicationContext
            val logDir = logDir(appContext).apply { mkdirs() }
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(appContext, logDir, defaultHandler))
        }

        fun logDir(context: Context): File = File(context.filesDir, "crash_logs")

        fun latestLog(context: Context): File? =
            logDir(context).listFiles()?.maxByOrNull { it.lastModified() }

        /** Launches a share sheet for the most recent crash log, or does nothing if there isn't one. */
        fun shareLatestLog(context: Context) {
            val log = latestLog(context) ?: return
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", log)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Music Player crash log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share crash log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
