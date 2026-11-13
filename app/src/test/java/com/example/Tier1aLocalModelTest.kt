package com.example

import com.example.data.slots.ApiProvider
import com.example.net.OpenAiCompatibleClient
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/**
 * Tier 1a mechanical proof: the app's own OpenAI-compatible client against a
 * local model server on the host (llama.cpp). Skipped unless TIER1A_BASE_URL
 * is set, so normal test runs never wait on a local model.
 * Run: TIER1A_BASE_URL=http://127.0.0.1:18080/v1 ./gradlew :app:testDebugUnitTest --tests "*Tier1aLocalModelTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Tier1aLocalModelTest {

    @Test
    fun detectBubbles_onPage001_viaLocalModel() = runBlocking {
        val baseUrl = System.getenv("TIER1A_BASE_URL")
        assumeTrue("TIER1A_BASE_URL not set; Tier 1a proof skipped", !baseUrl.isNullOrBlank())

        val page = File("../tests/fixtures/manga/page-001-coffee-shop.jpg")
        assert(page.exists()) { "fixture missing: ${page.absolutePath}" }
        val base64 = Base64.getEncoder().encodeToString(page.readBytes())

        val client = OpenAiCompatibleClient()
        val result = client.detectBubbles(
            baseUrl = baseUrl!!,
            apiKey = "local-no-key",
            model = "qwen2.5-vl-3b",
            imageBase64 = base64,
            provider = ApiProvider.CUSTOM
        )
        println("TIER1A detect result: $result")
        val bubbles = result.getOrThrow()
        bubbles.forEach { println("TIER1A bubble #${it.id} [${it.type}] v=${it.vertical} box=${it.box} text=${it.text}") }
        assert(bubbles.isNotEmpty()) { "no bubbles detected on page-001" }
        assert(bubbles.all { it.box.size == 4 }) { "every bubble must carry a 4-coordinate box" }
    }
}
