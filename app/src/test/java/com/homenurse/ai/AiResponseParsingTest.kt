package com.homenurse.ai

import com.homenurse.domain.model.SafetyLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Strict structured-output parsing: the model must answer with the exact
 * JSON contract; prose around it is tolerated, but a missing/blank core
 * field means the reply is REFUSED (never guessed). Enums parse
 * case-insensitively, unknown keys are ignored.
 *
 * These tests exercise the real parser with a real [ModelManager] shell but
 * a fake transport — no network, no native inference.
 */
class AiResponseParsingTest {

    private val provider = LocalGemmaAiProvider(
        modelManager = ModelManager(
            modelsDir = java.nio.file.Files.createTempDirectory("hn-parse-models").toFile(),
            engineCacheDir = java.nio.file.Files.createTempDirectory("hn-parse-cache").toFile(),
            manifest = ModelManifest(defaultModelId = "m", models = emptyList()),
            downloader = object : ModelDownloader {
                override suspend fun download(
                    url: String,
                    headers: Map<String, String>,
                    target: File,
                    onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
                ) = throw UnsupportedOperationException("no network in tests")

                override suspend fun getText(url: String, headers: Map<String, String>): String =
                    throw UnsupportedOperationException("no network in tests")
            },
        ),
    )

    // --- extractJsonObject ----------------------------------------------------

    @Test
    fun `extracts plain json object`() {
        assertEquals(
            """{"a":1}""",
            provider.extractJsonObject("""{"a":1}"""),
        )
    }

    @Test
    fun `extracts object from code fence with surrounding prose`() {
        val raw = """
            Sure! Here is the result:
            ```json
            {"summary":"ok","explanation":"fine"}
            ```
            Let me know if you need anything else.
        """.trimIndent()
        assertEquals(
            """{"summary":"ok","explanation":"fine"}""",
            provider.extractJsonObject(raw),
        )
    }

    @Test
    fun `braces inside strings do not confuse the scanner`() {
        val raw = """text {"summary":"Use {curly} braces","explanation":"done"} tail"""
        assertEquals(
            """{"summary":"Use {curly} braces","explanation":"done"}""",
            provider.extractJsonObject(raw),
        )
    }

    @Test
    fun `escaped quotes inside strings are handled`() {
        val raw = """{"summary":"He said \"hi\"","explanation":"ok"}"""
        assertEquals(raw, provider.extractJsonObject(raw))
    }

    @Test
    fun `nested objects are extracted whole`() {
        val raw = """outer {"summary":{"deep":{"x":1}},"explanation":"y"} done"""
        assertEquals(
            """{"summary":{"deep":{"x":1}},"explanation":"y"}""",
            provider.extractJsonObject(raw),
        )
    }

    @Test
    fun `missing or truncated json returns null`() {
        assertNull(provider.extractJsonObject("no json here"))
        assertNull(provider.extractJsonObject("""{"summary":"never closed"""))
        assertNull(provider.extractJsonObject(""))
    }

    // --- parse ----------------------------------------------------------------

    @Test
    fun `valid response parses into the structured contract`() {
        val response = provider.parse(
            """
            {
              "summary": "Your prescription has two medicines.",
              "explanation": "Both were confirmed by you.",
              "evidence": [{"factId": "f1", "label": "Metformin"}],
              "warnings": ["Take after food."],
              "uncertainty": "The scan was partially handwritten.",
              "requiresClinician": true,
              "safetyLevel": "caution",
              "suggestedNextSteps": ["Confirm the dose with your pharmacist."]
            }
            """.trimIndent(),
        )
        assertNotNull(response)
        assertEquals("Your prescription has two medicines.", response!!.summary)
        assertEquals("Both were confirmed by you.", response.explanation)
        assertEquals(1, response.evidence.size)
        assertEquals("f1", response.evidence.single().factId)
        assertEquals(listOf("Take after food."), response.warnings)
        assertEquals(true, response.requiresClinician)
        assertEquals(SafetyLevel.CAUTION, response.safetyLevel)
        assertEquals(1, response.suggestedNextSteps.size)
        assertEquals("The scan was partially handwritten.", response.uncertainty)
    }

    @Test
    fun `enums are parsed case insensitively and unknown keys ignored`() {
        val response = provider.parse(
            """{"summary":"s","explanation":"e","safetyLevel":"EMERGENCY","newField":123}""",
        )
        assertNotNull(response)
        assertEquals(SafetyLevel.EMERGENCY, response!!.safetyLevel)
    }

    @Test
    fun `defaults fill in optional fields`() {
        val response = provider.parse("""{"summary":"s","explanation":"e"}""")
        assertNotNull(response)
        assertTrue(response!!.evidence.isEmpty())
        assertTrue(response.warnings.isEmpty())
        assertFalse(response.requiresClinician)
        assertEquals(SafetyLevel.NORMAL, response.safetyLevel)
        assertNull(response.uncertainty)
    }

    @Test
    fun `missing explanation is refused not guessed`() {
        assertNull(provider.parse("""{"summary":"only summary"}"""))
    }

    @Test
    fun `blank summary is refused`() {
        assertNull(provider.parse("""{"summary":"   ","explanation":"e"}"""))
    }

    @Test
    fun `non json model output is refused`() {
        assertNull(provider.parse("I am sorry, I cannot answer that."))
    }

    @Test
    fun `truncated json is refused`() {
        assertNull(provider.parse("""{"summary":"never"""))
    }

    @Test
    fun `prose wrapped json still parses`() {
        val response = provider.parse(
            "Here you go: {\"summary\":\"Found it.\",\"explanation\":\"From your file.\"} Done!",
        )
        assertNotNull(response)
        assertEquals("Found it.", response!!.summary)
    }
}
