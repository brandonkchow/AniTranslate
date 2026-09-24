package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.PageEntity
import com.example.data.db.PageStatus
import com.example.data.slots.SlotStorage
import com.example.net.GeminiClient
import com.example.net.OpenAiCompatibleClient
import com.example.pipeline.PagePipeline
import com.example.pipeline.router.KeyRouter
import com.example.utils.RunLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExclusionSummaryRunScopedTest {

    private lateinit var application: Application
    private lateinit var pipeline: PagePipeline

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        RunLogger.clearLogs(application)
        PagePipeline.resetLoggedExclusions()

        val storage = SlotStorage.getInstance(application)
        val router = KeyRouter(storage)
        pipeline = PagePipeline(
            context = application,
            keyRouter = router,
            geminiClient = GeminiClient(),
            openAiClient = OpenAiCompatibleClient()
        )
    }

    @Test
    fun exclusionSummaryIsEmittedPerRunAndPerResumeRatherThanOncePerProcess() = runBlocking {
        val jobId = 18L
        val page1 = PageEntity(
            jobId = jobId,
            pageIndex = 0,
            originalImageUri = "file:///dummy/page_0.png",
            originalCachePath = "${application.cacheDir}/non_existent_page_0.png",
            status = PageStatus.QUEUED
        )
        val page2 = PageEntity(
            jobId = jobId,
            pageIndex = 1,
            originalImageUri = "file:///dummy/page_1.png",
            originalCachePath = "${application.cacheDir}/non_existent_page_1.png",
            status = PageStatus.QUEUED
        )

        // 1. Initial Run for Job 18
        val run1Id = RunLogger.startRun(application, jobId, 2, "Job started")
        pipeline.processPage(page1) {}

        val contentRun1AfterPage1 = RunLogger.getRunLogContent(application, run1Id)
        assertTrue(
            "Run 1 log must contain SLOT_EXCLUSIONS",
            contentRun1AfterPage1.contains("[SLOT_EXCLUSIONS]")
        )

        // Processing another page in the same run should NOT re-emit SLOT_EXCLUSIONS
        pipeline.processPage(page2) {}
        val contentRun1AfterPage2 = RunLogger.getRunLogContent(application, run1Id)
        val run1ExclusionCount = Regex("""\[SLOT_EXCLUSIONS]""").findAll(contentRun1AfterPage2).count()
        assertEquals(
            "Run 1 log must contain SLOT_EXCLUSIONS exactly once",
            1,
            run1ExclusionCount
        )

        // 2. Resume Run for the SAME Job 18 within the same process
        val run2Id = RunLogger.startRun(application, jobId, 2, "Job resumed")
        pipeline.processPage(page1) {}

        val contentRun2 = RunLogger.getRunLogContent(application, run2Id)
        val run2ExclusionCount = Regex("""\[SLOT_EXCLUSIONS]""").findAll(contentRun2).count()
        assertEquals(
            "Run 2 (resumed run) log must ALSO contain SLOT_EXCLUSIONS exactly once",
            1,
            run2ExclusionCount
        )
    }
}
