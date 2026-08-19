package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.JobDao
import com.example.data.db.JobEntity
import com.example.data.db.PageEntity
import com.example.data.db.PageStatus
import com.example.data.models.Bubble
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseUnitTest {

    private lateinit var db: AppDatabase
    private lateinit var jobDao: JobDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        jobDao = db.jobDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun testInsertJobAndQueryPages() = runBlocking {
        val job = JobEntity(
            pageCount = 2,
            status = "RUNNING"
        )
        val jobId = jobDao.insertJob(job)
        assertTrue(jobId > 0)

        val pages = listOf(
            PageEntity(
                jobId = jobId,
                pageIndex = 0,
                originalImageUri = "content://media/1",
                originalCachePath = "/tmp/page0.png",
                status = PageStatus.QUEUED
            ),
            PageEntity(
                jobId = jobId,
                pageIndex = 1,
                originalImageUri = "content://media/2",
                originalCachePath = "/tmp/page1.png",
                status = PageStatus.QUEUED
            )
        )
        val pageIds = jobDao.insertPages(pages)
        assertEquals(2, pageIds.size)

        val retrievedJob = jobDao.getJobByIdDirect(jobId)
        assertNotNull(retrievedJob)
        assertEquals(2, retrievedJob?.pageCount)

        val retrievedPages = jobDao.getPagesForJobDirect(jobId)
        assertEquals(2, retrievedPages.size)
        assertEquals(0, retrievedPages[0].pageIndex)
        assertEquals(1, retrievedPages[1].pageIndex)
    }

    @Test
    fun testUpdatePageStatusAndBubbles() = runBlocking {
        val jobId = jobDao.insertJob(JobEntity(pageCount = 1))
        val pageId = jobDao.insertPages(listOf(
            PageEntity(
                jobId = jobId,
                pageIndex = 0,
                originalImageUri = "content://media/1",
                originalCachePath = "/tmp/page0.png",
                status = PageStatus.QUEUED
            )
        )).first()

        val sampleBubble = Bubble(
            id = 1,
            box = listOf(0.1f, 0.1f, 0.3f, 0.4f),
            text = "やあ",
            translated = "Hey"
        )
        val bubblesJson = Bubble.listToJsonString(listOf(sampleBubble))

        val originalPage = jobDao.getPageByIdDirect(pageId)
        assertNotNull(originalPage)

        val updatedPage = originalPage!!.copy(
            status = PageStatus.DONE,
            bubblesJson = bubblesJson,
            finalImagePath = "/tmp/final0.png"
        )
        jobDao.updatePage(updatedPage)

        val reloadedPage = jobDao.getPageByIdDirect(pageId)
        assertNotNull(reloadedPage)
        assertEquals(PageStatus.DONE, reloadedPage?.status)
        assertEquals("/tmp/final0.png", reloadedPage?.finalImagePath)

        val parsedBubbles = reloadedPage?.getBubbles() ?: emptyList()
        assertEquals(1, parsedBubbles.size)
        assertEquals("Hey", parsedBubbles[0].translated)
        assertEquals("やあ", parsedBubbles[0].text)
    }

    @Test
    fun testDeleteJobCascade() = runBlocking {
        val jobId = jobDao.insertJob(JobEntity(pageCount = 1))
        jobDao.insertPages(listOf(
            PageEntity(
                jobId = jobId,
                pageIndex = 0,
                originalImageUri = "content://media/1",
                originalCachePath = "/tmp/page0.png"
            )
        ))

        jobDao.deleteJob(jobId)
        val retrievedJob = jobDao.getJobByIdDirect(jobId)
        assertNull(retrievedJob)

        val remainingPages = jobDao.getPagesForJobDirect(jobId)
        assertEquals(0, remainingPages.size)
    }
}
