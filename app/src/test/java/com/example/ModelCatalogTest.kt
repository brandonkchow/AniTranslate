package com.example

import com.example.data.slots.ApiProvider
import com.example.data.slots.ApiSlot
import com.example.data.slots.CatalogModel
import com.example.data.slots.ModelCatalogMigration
import com.example.data.slots.ModelCatalogParser
import com.example.data.slots.ModelCatalogStore
import com.example.data.slots.ModelFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Pure JVM tests for ModelCatalog parsing, filter traps, deterministic ranking,
 * local store persistence, and retirement migration.
 * Runs with NO network and NO Android runtime.
 */
class ModelCatalogTest {

    @Test
    fun suffixVsPricingDisagreement() {
        val json = """
            {
              "data": [
                {
                  "id": "vendor/paid-model:free",
                  "name": "Paid Model with misleading suffix",
                  "pricing": {
                    "prompt": "0.002",
                    "completion": "0.005"
                  },
                  "context_length": 32768,
                  "architecture": {
                    "input_modalities": ["text"],
                    "output_modalities": ["text"]
                  },
                  "supported_parameters": ["response_format"]
                },
                {
                  "id": "stealth/space-bunny-alpha",
                  "name": "Free Model without suffix",
                  "pricing": {
                    "prompt": "0",
                    "completion": "0"
                  },
                  "context_length": 32768,
                  "architecture": {
                    "input_modalities": ["text"],
                    "output_modalities": ["text"]
                  },
                  "supported_parameters": ["response_format"]
                }
              ]
            }
        """.trimIndent()

        val parsed = ModelCatalogParser.parseOpenRouter(json)
        assertEquals(2, parsed.size)

        val paidWithSuffix = parsed.first { it.id == "vendor/paid-model:free" }
        assertFalse("Model with non-zero pricing must not be marked free despite :free suffix", paidWithSuffix.free)

        val freeWithoutSuffix = parsed.first { it.id == "stealth/space-bunny-alpha" }
        assertTrue("Model with zero pricing must be marked free even without :free suffix", freeWithoutSuffix.free)

        val usable = ModelFilter.usable(parsed, needVision = false)
        assertEquals(1, usable.size)
        assertEquals("stealth/space-bunny-alpha", usable[0].id)
    }

    @Test
    fun lyriaStyleAudioExclusion() {
        val json = """
            {
              "data": [
                {
                  "id": "google/lyria-3-clip-preview",
                  "name": "Google Lyria 3 Clip Preview",
                  "pricing": {
                    "prompt": "0",
                    "completion": "0"
                  },
                  "context_length": 32768,
                  "architecture": {
                    "input_modalities": ["text"],
                    "output_modalities": ["text", "audio"]
                  },
                  "supported_parameters": ["response_format"]
                },
                {
                  "id": "google/gemma-4-31b-it:free",
                  "name": "Google Gemma 4 31B",
                  "pricing": {
                    "prompt": "0",
                    "completion": "0"
                  },
                  "context_length": 131072,
                  "architecture": {
                    "input_modalities": ["text"],
                    "output_modalities": ["text"]
                  },
                  "supported_parameters": ["response_format"]
                }
              ]
            }
        """.trimIndent()

        val parsed = ModelCatalogParser.parseOpenRouter(json)
        val lyria = parsed.first { it.id == "google/lyria-3-clip-preview" }
        assertFalse("Lyria produces audio and must not be marked text-only output", lyria.textOnlyOutput)

        val gemma = parsed.first { it.id == "google/gemma-4-31b-it:free" }
        assertTrue("Gemma produces text only", gemma.textOnlyOutput)

        val usable = ModelFilter.usable(parsed, needVision = false)
        assertEquals("Audio generator must be excluded by ModelFilter.usable", 1, usable.size)
        assertEquals("google/gemma-4-31b-it:free", usable[0].id)
    }

    @Test
    fun classifierAndModerationExclusion() {
        val models = listOf(
            CatalogModel(
                id = "nvidia/nemotron-3.5-content-safety:free",
                displayName = "Nemotron Content Safety",
                free = true,
                acceptsImage = false,
                textOnlyOutput = true,
                supportsJson = true,
                contextLength = 16384,
                providerId = "openrouter"
            ),
            CatalogModel(
                id = "meta-llama/llama-guard-3-8b",
                displayName = "Llama Guard 3 8B",
                free = true,
                acceptsImage = false,
                textOnlyOutput = true,
                supportsJson = true,
                contextLength = 16384,
                providerId = "openrouter"
            ),
            CatalogModel(
                id = "openai/omni-moderation-latest",
                displayName = "Omni Moderation",
                free = true,
                acceptsImage = false,
                textOnlyOutput = true,
                supportsJson = true,
                contextLength = 16384,
                providerId = "openrouter"
            ),
            CatalogModel(
                id = "google/gemma-4-31b-it:free",
                displayName = "Gemma 4 31B",
                free = true,
                acceptsImage = false,
                textOnlyOutput = true,
                supportsJson = true,
                contextLength = 131072,
                providerId = "openrouter"
            )
        )

        val usable = ModelFilter.usable(models, needVision = false)
        assertEquals(1, usable.size)
        assertEquals("google/gemma-4-31b-it:free", usable[0].id)
    }

    @Test
    fun openRouterMetaRouterExclusion() {
        val models = listOf(
            CatalogModel(
                id = "openrouter/auto",
                displayName = "OpenRouter Auto Meta Router",
                free = true,
                acceptsImage = true,
                textOnlyOutput = true,
                supportsJson = true,
                contextLength = 32768,
                providerId = "openrouter"
            ),
            CatalogModel(
                id = "openrouter/free",
                displayName = "OpenRouter Free Meta Router",
                free = true,
                acceptsImage = true,
                textOnlyOutput = true,
                supportsJson = true,
                contextLength = 32768,
                providerId = "openrouter"
            ),
            CatalogModel(
                id = "qwen/qwen-2.5-72b-instruct:free",
                displayName = "Qwen 2.5 72B",
                free = true,
                acceptsImage = false,
                textOnlyOutput = true,
                supportsJson = true,
                contextLength = 65536,
                providerId = "openrouter"
            )
        )

        val usable = ModelFilter.usable(models, needVision = false)
        assertEquals(1, usable.size)
        assertEquals("qwen/qwen-2.5-72b-instruct:free", usable[0].id)
    }

    @Test
    fun visionGating() {
        val models = listOf(
            CatalogModel(
                id = "google/gemini-2.0-flash-exp:free",
                displayName = "Gemini Flash Exp",
                free = true,
                acceptsImage = true,
                textOnlyOutput = true,
                supportsJson = true,
                contextLength = 32768,
                providerId = "openrouter"
            ),
            CatalogModel(
                id = "deepseek/deepseek-chat:free",
                displayName = "DeepSeek Chat",
                free = true,
                acceptsImage = false,
                textOnlyOutput = true,
                supportsJson = true,
                contextLength = 65536,
                providerId = "openrouter"
            )
        )

        val textOnlyUsable = ModelFilter.usable(models, needVision = false)
        assertEquals(2, textOnlyUsable.size)

        val visionUsable = ModelFilter.usable(models, needVision = true)
        assertEquals(1, visionUsable.size)
        assertEquals("google/gemini-2.0-flash-exp:free", visionUsable[0].id)
    }

    @Test
    fun jsonFirstRanking() {
        val models = listOf(
            CatalogModel(
                id = "model-without-json-huge-ctx",
                displayName = "No JSON Huge Context",
                free = true,
                acceptsImage = false,
                textOnlyOutput = true,
                supportsJson = false,
                contextLength = 1000000,
                providerId = "openrouter"
            ),
            CatalogModel(
                id = "model-with-json-small-ctx",
                displayName = "With JSON Small Context",
                free = true,
                acceptsImage = false,
                textOnlyOutput = true,
                supportsJson = true,
                contextLength = 16384,
                providerId = "openrouter"
            )
        )

        val ranked = ModelFilter.rank(models)
        assertEquals(
            "supportsJson == true must rank before supportsJson == false regardless of context length",
            "model-with-json-small-ctx",
            ranked[0].id
        )
        assertEquals("model-without-json-huge-ctx", ranked[1].id)
    }

    @Test
    fun deterministicTieBreaking() {
        val modelA = CatalogModel("vendor/model-a", "Model A", true, false, true, true, 32768, "openrouter")
        val modelB = CatalogModel("vendor/model-b", "Model B", true, false, true, true, 32768, "openrouter")
        val modelC = CatalogModel("vendor/model-c", "Model C", true, false, true, true, 65536, "openrouter")
        val modelPinned = CatalogModel("vendor/model-pinned", "Pinned", true, false, true, false, 8192, "openrouter")

        val list1 = listOf(modelA, modelB, modelC, modelPinned)
        val list2 = listOf(modelPinned, modelC, modelA, modelB)

        // Pinned model first, then supportsJson true > false, then contextLength desc, then id asc
        val ranked1 = ModelFilter.rank(list1, pinned = listOf("vendor/model-pinned"))
        val ranked2 = ModelFilter.rank(list2, pinned = listOf("vendor/model-pinned"))

        val expectedIds = listOf("vendor/model-pinned", "vendor/model-c", "vendor/model-a", "vendor/model-b")
        assertEquals(expectedIds, ranked1.map { it.id })
        assertEquals(expectedIds, ranked2.map { it.id })
    }

    @Test
    fun retirementMigrationNoCatalogNoOp() {
        val initialSlots = listOf(
            ApiSlot(
                id = "slot-1",
                provider = ApiProvider.OPENROUTER,
                model = "some-custom-model",
                isModelRetired = false
            ),
            ApiSlot(
                id = "slot-2",
                provider = ApiProvider.WORKSTATION,
                model = "manga-image-translator",
                isModelRetired = false
            )
        )

        // Strict no-op when catalog is empty
        val (migratedEmpty, modifiedEmpty) = ModelCatalogMigration.applyRetirementMigration(initialSlots, emptyList())
        assertFalse("Migration must be a no-op when no catalog is available", modifiedEmpty)
        assertEquals(initialSlots, migratedEmpty)

        // When catalog is present with OpenRouter models, but does not contain 'some-custom-model'
        val catalog = listOf(
            CatalogModel(
                id = "google/gemma-4-31b-it:free",
                displayName = "Gemma 4 31B",
                free = true,
                acceptsImage = true,
                textOnlyOutput = true,
                supportsJson = true,
                contextLength = 131072,
                providerId = "openrouter"
            )
        )

        val (migrated, modified) = ModelCatalogMigration.applyRetirementMigration(initialSlots, catalog)
        assertTrue("Slot whose model is missing from catalog must trigger migration flag", modified)

        val openRouterSlot = migrated.first { it.id == "slot-1" }
        assertTrue("Stored OpenRouter model absent from catalog must be marked retired", openRouterSlot.isModelRetired)
        assertEquals("User's chosen model must NEVER be silently overwritten", "some-custom-model", openRouterSlot.model)

        val workstationSlot = migrated.first { it.id == "slot-2" }
        assertFalse("Workstation (provider not in catalog) must not be marked retired", workstationSlot.isModelRetired)
    }

    @Test
    fun catalogStorePersistenceAndFreshness() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "catalog_test_${UUID.randomUUID()}")
        try {
            val store = ModelCatalogStore(tempDir)
            assertFalse("Freshness on non-existent store must be false", store.isFresh())
            assertEquals(emptyList<CatalogModel>(), store.load())

            val models = listOf(
                CatalogModel(
                    id = "openai/gpt-oss-120b",
                    displayName = "GPT-OSS 120B",
                    free = true,
                    acceptsImage = false,
                    textOnlyOutput = true,
                    supportsJson = true,
                    contextLength = 131072,
                    providerId = "groq"
                )
            )

            store.save(models)
            assertTrue("Saved catalog must be fresh within 24h", store.isFresh())

            val loaded = store.load()
            assertEquals(1, loaded.size)
            assertEquals("openai/gpt-oss-120b", loaded[0].id)
            assertEquals("groq", loaded[0].providerId)

            assertFalse("Catalog must not be fresh with a TTL of 0ms", store.isFresh(ttlMs = 0L))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun parseGroqAndGeminiCatalogs() {
        val groqJson = """
            {
              "data": [
                {
                  "id": "openai/gpt-oss-120b",
                  "object": "model",
                  "active": true,
                  "context_window": 131072
                },
                {
                  "id": "retired-groq-model",
                  "object": "model",
                  "active": false,
                  "context_window": 8192
                }
              ]
            }
        """.trimIndent()

        val groqModels = ModelCatalogParser.parseGroq(groqJson)
        assertEquals("Inactive Groq model must be skipped", 1, groqModels.size)
        assertEquals("openai/gpt-oss-120b", groqModels[0].id)
        assertEquals(131072, groqModels[0].contextLength)
        assertFalse(groqModels[0].acceptsImage)

        val geminiJson = """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "inputTokenLimit": 1048576,
                  "supportedGenerationMethods": ["generateContent", "countTokens"]
                },
                {
                  "name": "models/text-embedding-004",
                  "displayName": "Text Embedding 004",
                  "inputTokenLimit": 2048,
                  "supportedGenerationMethods": ["embedContent"]
                }
              ]
            }
        """.trimIndent()

        val geminiModels = ModelCatalogParser.parseGemini(geminiJson)
        assertEquals("Embedding models without generateContent must be skipped", 1, geminiModels.size)
        assertEquals("gemini-2.5-flash", geminiModels[0].id)
        assertEquals("Gemini 2.5 Flash", geminiModels[0].displayName)
        assertTrue(geminiModels[0].acceptsImage)
    }

    @Test
    fun anonymousCloakModelsRankLast() {
        val cloak = CatalogModel(
            id = "stealth/space-bunny-alpha",
            displayName = "Cloak",
            free = true,
            acceptsImage = true,
            textOnlyOutput = true,
            supportsJson = true,
            contextLength = 1000000,
            providerId = "openrouter"
        )
        val named = CatalogModel(
            id = "vendor/named-31b:free",
            displayName = "Named",
            free = true,
            acceptsImage = true,
            textOnlyOutput = true,
            supportsJson = true,
            contextLength = 32768,
            providerId = "openrouter"
        )

        // The cloak model out-contexts the named one yet must never lead the walk.
        val ranked = ModelFilter.rank(listOf(cloak, named))
        assertEquals("vendor/named-31b:free", ranked[0].id)
        assertEquals("stealth/space-bunny-alpha", ranked[1].id)
    }

    @Test
    fun advertisedSizeBreaksTiesWithoutGating() {
        fun m(id: String) = CatalogModel(
            id = id,
            displayName = id,
            free = true,
            acceptsImage = false,
            textOnlyOutput = true,
            supportsJson = true,
            contextLength = 16384,
            providerId = "openrouter"
        )

        val ranked = ModelFilter.rank(
            listOf(m("vendor/small-2.6b:free"), m("vendor/big-120b:free"), m("vendor/unknown-size:free"))
        )
        // Explicit 120B first, then an id advertising no size, then an explicitly tiny model —
        // and all three stay in the walk: a preference, never a gate.
        assertEquals(
            listOf("vendor/big-120b:free", "vendor/unknown-size:free", "vendor/small-2.6b:free"),
            ranked.map { it.id }
        )
        assertEquals(120.0, ModelFilter.advertisedSizeB("vendor/big-120b:free"))
        assertEquals(2.6, ModelFilter.advertisedSizeB("vendor/small-2.6b:free"))
        assertFalse(
            "An id advertising no size must report unknown, not a guess",
            ModelFilter.advertisedSizeB("vendor/unknown-size:free") != null
        )
    }

    @Test
    fun textInputIsRequiredForChatModels() {
        val json = """
            {
              "data": [
                {
                  "id": "vendor/image-only-encoder",
                  "name": "Image Only",
                  "pricing": { "prompt": "0", "completion": "0" },
                  "architecture": { "input_modalities": ["image"], "output_modalities": ["text"] },
                  "supported_parameters": ["response_format"],
                  "context_length": 8192
                },
                {
                  "id": "vendor/vision-chat",
                  "name": "Vision Chat",
                  "pricing": { "prompt": "0", "completion": "0" },
                  "architecture": { "input_modalities": ["text", "image"], "output_modalities": ["text"] },
                  "supported_parameters": ["response_format"],
                  "context_length": 32768
                }
              ]
            }
        """.trimIndent()

        val parsed = ModelCatalogParser.parseOpenRouter(json)
        assertEquals("A model that cannot accept a text prompt is not a chat model", 1, parsed.size)
        assertEquals("vendor/vision-chat", parsed[0].id)
    }

    @Test
    fun nonChatGroqEndpointsAreExcluded() {
        val json = """
            {
              "data": [
                { "id": "whisper-large-v3", "active": true, "context_window": 448 },
                { "id": "playai-tts", "active": true, "context_window": 4096 },
                { "id": "openai/gpt-oss-120b", "active": true, "context_window": 131072 }
              ]
            }
        """.trimIndent()

        val parsed = ModelCatalogParser.parseGroq(json)
        assertEquals(1, parsed.size)
        assertEquals("openai/gpt-oss-120b", parsed[0].id)
    }

    @Test
    fun shippedRetiredDefaultsAreRepairedOffline() {
        val slots = listOf(
            ApiSlot(
                id = "groq-1",
                provider = ApiProvider.GROQ,
                model = "llama-3.3-70b-versatile",
                isModelRetired = true
            ),
            ApiSlot(
                id = "or-1",
                provider = ApiProvider.OPENROUTER,
                model = "my-chosen-model:free",
                isModelRetired = false
            )
        )

        val (updated, modified) = ModelCatalogMigration.applyKnownRetiredRepair(slots)

        assertTrue("Repair must report a change", modified)
        assertEquals("openai/gpt-oss-120b", updated[0].model)
        assertFalse("A repaired slot is no longer flagged retired", updated[0].isModelRetired)
        assertEquals("A hand-picked model id must never be replaced", "my-chosen-model:free", updated[1].model)
    }
}
