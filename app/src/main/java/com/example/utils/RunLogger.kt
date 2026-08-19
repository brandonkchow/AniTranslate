package com.example.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class RunLogEntry(
    val id: String,
    val jobId: Long,
    val timestamp: Long,
    val formattedDate: String,
    val pageCount: Int,
    val status: String,
    val preview: String,
    val filePath: String,
    val sizeBytes: Long
)

object RunLogger {

    private const val MAX_RUNS_RETAINED = 3
    private const val LOGS_DIR_NAME = "run_logs"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val timeOnlyFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // In-memory mapping of active run ID per jobId
    private val activeRuns = ConcurrentHashMap<Long, String>()

    private fun getLogsDir(context: Context): File {
        val dir = File(context.filesDir, LOGS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Ensures only the last 3 runs remain in storage.
     */
    @Synchronized
    fun pruneLogs(context: Context) {
        try {
            val dir = getLogsDir(context)
            val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".log") } ?: return
            if (files.size > MAX_RUNS_RETAINED) {
                // Sort newest first
                val sorted = files.sortedByDescending { it.lastModified() }
                // Delete anything beyond the top 3
                for (i in MAX_RUNS_RETAINED until sorted.size) {
                    sorted[i].delete()
                }
            }
        } catch (e: Exception) {
            // Ignore failure in pruning
        }
    }

    @Synchronized
    fun startRun(context: Context, jobId: Long, pageCount: Int, info: String = ""): String {
        val timestamp = System.currentTimeMillis()
        val runId = "run_${timestamp}_job_$jobId"
        activeRuns[jobId] = runId

        val dir = getLogsDir(context)
        val logFile = File(dir, "$runId.log")

        val initialContent = StringBuilder()
            .append("=====================================================\n")
            .append("AniTranslate Execution & Export Run Log\n")
            .append("Run ID:     ").append(runId).append("\n")
            .append("Job ID:     #").append(jobId).append("\n")
            .append("Start Time: ").append(dateFormat.format(Date(timestamp))).append("\n")
            .append("Page Count: ").append(pageCount).append("\n")
            .append("Status:     RUNNING\n")
            if (info.isNotBlank()) initialContent.append("Info:       ").append(info).append("\n")
            initialContent.append("=====================================================\n\n")
            .append("[${timeOnlyFormat.format(Date(timestamp))}] [INIT] Run started for Job #$jobId ($pageCount pages)\n")

        try {
            logFile.writeText(initialContent.toString())
            pruneLogs(context)
        } catch (e: Exception) {
            // Ignore
        }

        return runId
    }

    @Synchronized
    fun log(context: Context, jobId: Long, tag: String, message: String) {
        val runId = activeRuns[jobId] ?: run {
            // If runId doesn't exist, search for matching latest log for this job
            val dir = getLogsDir(context)
            val files = dir.listFiles { file -> file.name.contains("_job_$jobId.log") }
            files?.maxByOrNull { it.lastModified() }?.nameWithoutExtension
        } ?: return

        appendLog(context, runId, tag, message)
    }

    @Synchronized
    fun logPageEvent(context: Context, jobId: Long, pageIndex: Int, stage: String, details: String) {
        val tag = "PAGE_${pageIndex + 1}_$stage"
        log(context, jobId, tag, details)
    }

    @Synchronized
    fun logExportEvent(context: Context, jobId: Long, exportType: String, details: String) {
        log(context, jobId, "EXPORT", "[$exportType] $details")
    }

    @Synchronized
    fun finishRun(context: Context, jobId: Long, status: String, summary: String) {
        val runId = activeRuns[jobId] ?: return
        val now = System.currentTimeMillis()
        val tag = "FINISH"
        val message = "Run finished with status: $status | $summary"
        appendLog(context, runId, tag, message)

        // Also update the header status if possible
        try {
            val dir = getLogsDir(context)
            val logFile = File(dir, "$runId.log")
            if (logFile.exists()) {
                val currentText = logFile.readText()
                val updatedText = currentText
                    .replaceFirst("Status:     RUNNING", "Status:     $status")
                    .plus("\n[${timeOnlyFormat.format(Date(now))}] [COMPLETE] $summary\n")
                logFile.writeText(updatedText)
            }
            pruneLogs(context)
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Synchronized
    private fun appendLog(context: Context, runId: String, tag: String, message: String) {
        try {
            val dir = getLogsDir(context)
            val logFile = File(dir, "$runId.log")
            val now = System.currentTimeMillis()
            val entry = "[${timeOnlyFormat.format(Date(now))}] [$tag] $message\n"
            logFile.appendText(entry)
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Returns the list of available run logs (at most 3).
     */
    @Synchronized
    fun getRecentRuns(context: Context): List<RunLogEntry> {
        pruneLogs(context)
        val dir = getLogsDir(context)
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".log") } ?: return emptyList()

        return files.sortedByDescending { it.lastModified() }.take(MAX_RUNS_RETAINED).map { file ->
            val content = try { file.readText() } catch (e: Exception) { "" }
            val lines = content.lines()

            var jobId = 0L
            var status = "COMPLETED"
            var pageCount = 0
            var startTime = file.lastModified()

            for (line in lines.take(15)) {
                when {
                    line.startsWith("Job ID:     #") -> {
                        jobId = line.removePrefix("Job ID:     #").trim().toLongOrNull() ?: 0L
                    }
                    line.startsWith("Status:     ") -> {
                        status = line.removePrefix("Status:     ").trim()
                    }
                    line.startsWith("Page Count: ") -> {
                        pageCount = line.removePrefix("Page Count: ").trim().toIntOrNull() ?: 0
                    }
                }
            }

            val previewLines = lines.takeLast(6).filter { it.isNotBlank() }.joinToString("\n")

            RunLogEntry(
                id = file.nameWithoutExtension,
                jobId = jobId,
                timestamp = startTime,
                formattedDate = dateFormat.format(Date(file.lastModified())),
                pageCount = pageCount,
                status = status,
                preview = if (previewLines.isNotBlank()) previewLines else "No log preview available.",
                filePath = file.absolutePath,
                sizeBytes = file.length()
            )
        }
    }

    @Synchronized
    fun getRunLogContent(context: Context, logId: String): String {
        return try {
            val dir = getLogsDir(context)
            val file = File(dir, "$logId.log")
            if (file.exists()) file.readText() else "Log file not found."
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
    }

    @Synchronized
    fun clearLogs(context: Context) {
        try {
            val dir = getLogsDir(context)
            dir.listFiles()?.forEach { it.delete() }
            activeRuns.clear()
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun createShareIntentForLog(context: Context, logId: String): Intent? {
        val dir = getLogsDir(context)
        val file = File(dir, "$logId.log")
        if (!file.exists()) return null

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AniTranslate Log: $logId")
            putExtra(Intent.EXTRA_TEXT, file.readText())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
