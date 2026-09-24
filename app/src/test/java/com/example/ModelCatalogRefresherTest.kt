package com.example

import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.CatalogModel
import com.example.data.slots.ModelCatalogRefresher
import com.example.data.slots.ModelCatalogStore
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the wiring, not just the parsers.
 *
 * The previous suite was green while the feature was dead: every test covered a pure parser or filter,
 * and none asserted that anything ever *called* the fetcher. `ModelCatalogFetcher` had no call site at
 * all, so the free-model walk silently collapsed to the pinned model. These tests fail if that
 * regresses.
 */
class ModelCatalogRefresherTest {

    private val dir = Files.createTempDirectory("model-catalog-test").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun model(id: String) = CatalogModel(
        id = id,
        displayName = id,
        free = true,
        acceptsImage = false,
        textOnlyOutput = true,
        supportsJson = true,
        contextLength = 128000,
        providerId = "openrouter"
    )

    @Test
    fun refreshActuallyInvokesTheFetcher() {
        val store = ModelCatalogStore(dir)
        var invocations = 0

        val outcome = ModelCatalogRefresher.refreshIfStale(store, emptyList(), force = true) { _, _ ->
            invocations++
            listOf(model("openrouter/free"))
        }

        assertEquals("the fetcher must actually run", 1, invocations)
        assertTrue(outcome.fetched)
        assertEquals(listOf("openrouter/free"), outcome.models.map { it.id })
        assertEquals(
            "the fetched catalogue must persist for later jobs",
            1,
            ModelCatalogStore(dir).load().size
        )
    }

    @Test
    fun aFreshCacheDoesNotHitTheNetwork() {
        ModelCatalogStore(dir).save(listOf(model("cached/model")))
        var invocations = 0

        val outcome = ModelCatalogRefresher.refreshIfStale(ModelCatalogStore(dir), emptyList()) { _, _ ->
            invocations++
            emptyList()
        }

        assertEquals(0, invocations)
        assertFalse(outcome.fetched)
        assertEquals("cached/model", outcome.models.first().id)
    }

    @Test
    fun aFailedFetchKeepsTheCacheAndNeverThrows() {
        ModelCatalogStore(dir).save(listOf(model("cached/model")))

        val outcome = ModelCatalogRefresher.refreshIfStale(
            ModelCatalogStore(dir),
            emptyList(),
            force = true
        ) { _, _ ->
            throw RuntimeException("offline")
        }

        assertFalse(outcome.fetched)
        assertEquals("cached/model", outcome.models.first().id)
        assertEquals(1, ModelCatalogStore(dir).load().size)
    }

    @Test
    fun providerKeysAreReadFromTheStoredSlots() {
        val slots = listOf(
            ApiSlot(provider = ApiProvider.GROQ, apiKey = "groq-key"),
            ApiSlot(provider = ApiProvider.GEMINI, apiKey = "gemini-key")
        )
        var seen: Pair<String, String>? = null

        ModelCatalogRefresher.refreshIfStale(ModelCatalogStore(dir), slots, force = true) { groq, gemini ->
            seen = groq to gemini
            listOf(model("openrouter/free"))
        }

        assertEquals("groq-key" to "gemini-key", seen)
    }

    @Test
    fun theCatalogueTimestampIsExposedForTheUi() {
        val store = ModelCatalogStore(dir)

        assertEquals(0L, store.lastFetchedEpochMs())

        ModelCatalogRefresher.refreshIfStale(store, emptyList(), force = true) { _, _ ->
            listOf(model("x/y"))
        }

        assertTrue(store.lastFetchedEpochMs() > 0L)
    }
}
