package com.example.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.data.db.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MediaStoreExporter {

    suspend fun exportPageToPictures(
        context: Context,
        page: PageEntity,
        fileNamePrefix: String = "anitranslate_page"
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val sourcePath = page.finalImagePath.ifBlank { page.originalCachePath }
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                return@withContext Result.failure(IllegalStateException("Final image file does not exist."))
            }

            val filename = "${fileNamePrefix}_${page.jobId}_${page.pageIndex + 1}_${System.currentTimeMillis()}.png"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AniTranslate")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val uri = resolver.insert(collectionUri, contentValues)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create MediaStore entry."))

            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Failed to open output stream for MediaStore."))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            RunLogger.logExportEvent(
                context = context,
                jobId = page.jobId,
                exportType = "MEDIASTORE",
                details = "Saved page #${page.pageIndex + 1} to Pictures/AniTranslate ($filename)"
            )

            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportBatchToPictures(
        context: Context,
        pages: List<PageEntity>
    ): Result<Int> = withContext(Dispatchers.IO) {
        val donePages = pages.filter { it.finalImagePath.isNotBlank() && File(it.finalImagePath).exists() }
        if (donePages.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No completed pages found to export."))
        }

        var savedCount = 0
        val jobId = pages.firstOrNull()?.jobId ?: 0L
        for (page in donePages) {
            val result = exportPageToPictures(context, page)
            if (result.isSuccess) savedCount++
        }

        RunLogger.logExportEvent(
            context = context,
            jobId = jobId,
            exportType = "MEDIASTORE_BATCH",
            details = "Batch exported $savedCount / ${donePages.size} pages to Pictures/AniTranslate"
        )

        Result.success(savedCount)
    }

    suspend fun createZipAndShare(
        context: Context,
        jobId: Long,
        pages: List<PageEntity>
    ): Result<Intent> = withContext(Dispatchers.IO) {
        val zipResult = ZipHelper.exportBatchAsZip(context, jobId, pages)
        if (zipResult.isFailure) {
            return@withContext Result.failure(zipResult.exceptionOrNull() ?: Exception("ZIP export failed"))
        }

        val zipFile = zipResult.getOrThrow()
        val shareIntent = ZipHelper.createZipShareIntent(
            context = context,
            zipFile = zipFile,
            subject = "AniTranslate Manga Translation Batch #$jobId"
        )

        Result.success(Intent.createChooser(shareIntent, "Share AniTranslate Batch ZIP"))
    }
}
