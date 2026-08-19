package com.example.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.db.PageEntity
import com.example.ui.home.SelectedImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipHelper {

    private val SUPPORTED_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp")

    /**
     * Natural alphanumeric comparator so "page_2.png" sorts before "page_10.png".
     */
    val naturalOrderComparator = Comparator<String> { s1, s2 ->
        val regex = Regex("(\\d+)|(\\D+)")
        val tokens1 = regex.findAll(s1).map { it.value }.toList()
        val tokens2 = regex.findAll(s2).map { it.value }.toList()

        val minSize = minOf(tokens1.size, tokens2.size)
        for (i in 0 until minSize) {
            val t1 = tokens1[i]
            val t2 = tokens2[i]
            val num1 = t1.toLongOrNull()
            val num2 = t2.toLongOrNull()

            if (num1 != null && num2 != null) {
                val cmp = num1.compareTo(num2)
                if (cmp != 0) return@Comparator cmp
            } else {
                val cmp = t1.compareTo(t2, ignoreCase = true)
                if (cmp != 0) return@Comparator cmp
            }
        }
        tokens1.size.compareTo(tokens2.size)
    }

    suspend fun importPagesFromZip(
        context: Context,
        zipUri: Uri,
        maxPages: Int = 30
    ): Result<List<SelectedImageItem>> = withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream = context.contentResolver.openInputStream(zipUri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open stream for ZIP URI."))

            val timestamp = System.currentTimeMillis()
            val extractDir = File(context.cacheDir, "zip_import_$timestamp").apply { mkdirs() }

            // Extract all candidate image files
            val extractedFiles = mutableListOf<File>()

            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val lowerName = name.lowercase()

                    // Skip hidden files, __MACOSX, and directories
                    val isHiddenOrSys = name.contains("__MACOSX") || name.startsWith(".") || name.contains("/.")
                    val isImage = SUPPORTED_EXTENSIONS.any { lowerName.endsWith(it) }

                    if (!entry.isDirectory && isImage && !isHiddenOrSys) {
                        val sanitizedName = File(name).name
                        val targetFile = File(extractDir, sanitizedName)
                        FileOutputStream(targetFile).use { out ->
                            zis.copyTo(out)
                            out.flush()
                        }
                        extractedFiles.add(targetFile)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (extractedFiles.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("No valid image files (.png, .jpg, .webp) found in the ZIP."))
            }

            // Sort extracted images using natural alphanumeric order on original file names
            val sortedFiles = extractedFiles.sortedWith { f1, f2 ->
                naturalOrderComparator.compare(f1.name, f2.name)
            }.take(maxPages)

            val selectedItems = sortedFiles.mapIndexed { index, file ->
                val uri = Uri.fromFile(file)
                SelectedImageItem(
                    uri = uri,
                    name = file.name,
                    sizeBytes = file.length()
                )
            }

            Result.success(selectedItems)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportBatchAsZip(
        context: Context,
        jobId: Long,
        pages: List<PageEntity>
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val donePages = pages.filter { it.finalImagePath.isNotBlank() && File(it.finalImagePath).exists() }
            if (donePages.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("No completed pages available to export."))
            }

            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val zipFile = File(exportDir, "AniTranslate_Batch_${jobId}_${System.currentTimeMillis()}.zip")

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (page in donePages) {
                    val finalFile = File(page.finalImagePath)
                    if (finalFile.exists()) {
                        val entryName = "page_${String.format("%03d", page.pageIndex + 1)}.png"
                        zos.putNextEntry(ZipEntry(entryName))
                        FileInputStream(finalFile).use { input -> input.copyTo(zos) }
                        zos.closeEntry()
                    }

                    // Sidecar JSON
                    val sidecarFile = File(context.cacheDir, "sidecar_${page.id}.json")
                    if (sidecarFile.exists()) {
                        val jsonEntryName = "page_${String.format("%03d", page.pageIndex + 1)}_bubbles.json"
                        zos.putNextEntry(ZipEntry(jsonEntryName))
                        FileInputStream(sidecarFile).use { input -> input.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                // Batch Manifest / Summary
                val manifestEntryName = "manifest.txt"
                zos.putNextEntry(ZipEntry(manifestEntryName))
                val manifestContent = buildString {
                    appendLine("AniTranslate Manga Batch Export")
                    appendLine("Job ID: #$jobId")
                    appendLine("Pages Exported: ${donePages.size} / ${pages.size}")
                    appendLine("Exported At: ${System.currentTimeMillis()}")
                    appendLine("=========================================")
                    donePages.forEach { p ->
                        appendLine("Page #${p.pageIndex + 1}: ${p.getBubbles().size} bubbles translated")
                    }
                }
                zos.write(manifestContent.toByteArray())
                zos.closeEntry()
            }

            RunLogger.logExportEvent(
                context = context,
                jobId = jobId,
                exportType = "ZIP",
                details = "Created ZIP archive with ${donePages.size} pages at ${zipFile.name} (${zipFile.length() / 1024} KB)"
            )

            Result.success(zipFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun createZipShareIntent(context: Context, zipFile: File, subject: String): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
