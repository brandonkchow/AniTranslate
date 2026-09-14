package com.example

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.PageEntity
import com.example.data.db.PageStatus
import com.example.utils.RunLogger
import com.example.utils.ZipHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunLoggerAndZipTest {

    private lateinit var application: Application

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        RunLogger.clearLogs(application)
    }

    @Test
    fun testRunLoggerPrunesToLastThreeRuns() {
        // Create 5 runs
        for (i in 1..5) {
            val jobId = i.toLong()
            RunLogger.startRun(application, jobId, 3, "Start run $i")
            RunLogger.logPageEvent(application, jobId, 0, "DETECT", "Detection completed for page 1")
            RunLogger.finishRun(application, jobId, "COMPLETED", "3 done, 0 failed")
            // Short delay to guarantee distinct timestamps
            Thread.sleep(20)
        }

        val runs = RunLogger.getRecentRuns(application)
        assertEquals("Should retain only the last 3 runs in storage", 3, runs.size)
        // Latest runs should be 5, 4, 3
        assertEquals(5L, runs[0].jobId)
        assertEquals(4L, runs[1].jobId)
        assertEquals(3L, runs[2].jobId)
    }

    @Test
    fun testRunLoggerLogContentFormat() {
        val jobId = 42L
        RunLogger.startRun(application, jobId, 2, "Test job start")
        RunLogger.logPageEvent(application, jobId, 0, "DETECT_START", "Calling Gemini for OCR")
        RunLogger.logExportEvent(application, jobId, "ZIP_EXPORT", "Exported 2 pages to ZIP")
        RunLogger.finishRun(application, jobId, "COMPLETED", "All pages translated")

        val runs = RunLogger.getRecentRuns(application)
        assertEquals(1, runs.size)

        val content = RunLogger.getRunLogContent(application, runs[0].id)
        assertTrue("Log should contain AniTranslate header", content.contains("AniTranslate Execution & Export Run Log"))
        assertTrue("Log should contain Job ID 42", content.contains("Job ID:     #42"))
        assertTrue("Log should record DETECT_START", content.contains("DETECT_START"))
        assertTrue("Log should record ZIP_EXPORT", content.contains("ZIP_EXPORT"))
        assertTrue("Log should record COMPLETED", content.contains("COMPLETED"))
    }

    @Test
    fun testZipImportFromZipArchive() = runBlocking {
        // Create a test ZIP archive containing 3 numbered manga images
        val testZipFile = File(application.cacheDir, "test_manga_chapter.zip")
        ZipOutputStream(FileOutputStream(testZipFile)).use { zos ->
            val pageNames = listOf("page_03.png", "page_01.png", "page_02.png")
            for (name in pageNames) {
                zos.putNextEntry(ZipEntry(name))
                // Write dummy bitmap PNG data
                val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
                bmp.compress(Bitmap.CompressFormat.PNG, 100, zos)
                zos.closeEntry()
            }
        }

        val uri = Uri.fromFile(testZipFile)
        val result = ZipHelper.importPagesFromZip(application, uri, maxPages = 10)
        assertTrue("Import from zip should succeed", result.isSuccess)

        val pages = result.getOrNull()
        assertNotNull(pages)
        assertEquals("Should extract exactly 3 pages", 3, pages!!.size)
        // Check natural sort order: page_01 should be first, then page_02, then page_03
        assertEquals("page_01.png", pages[0].name)
        assertEquals("page_02.png", pages[1].name)
        assertEquals("page_03.png", pages[2].name)
    }

    @Test
    fun testZipBatchExport() = runBlocking {
        // Create dummy final image files
        val img1 = File(application.cacheDir, "final_1.png").apply {
            writeBytes(ByteArray(100) { 1 })
        }
        val img2 = File(application.cacheDir, "final_2.png").apply {
            writeBytes(ByteArray(100) { 2 })
        }

        val pages = listOf(
            PageEntity(
                id = 101L,
                jobId = 77L,
                pageIndex = 0,
                originalImageUri = "file://${img1.absolutePath}",
                originalCachePath = img1.absolutePath,
                finalImagePath = img1.absolutePath,
                status = PageStatus.DONE,
                bubblesJson = "[]"
            ),
            PageEntity(
                id = 102L,
                jobId = 77L,
                pageIndex = 1,
                originalImageUri = "file://${img2.absolutePath}",
                originalCachePath = img2.absolutePath,
                finalImagePath = img2.absolutePath,
                status = PageStatus.DONE,
                bubblesJson = "[]"
            )
        )

        val exportResult = ZipHelper.exportBatchAsZip(application, 77L, pages)
        assertTrue("Batch export to ZIP should succeed", exportResult.isSuccess)

        val exportedZip = exportResult.getOrNull()
        assertNotNull(exportedZip)
        assertTrue("Exported ZIP file must exist", exportedZip!!.exists())
        assertTrue("Exported ZIP file must be > 0 bytes", exportedZip.length() > 0)
    }
}
