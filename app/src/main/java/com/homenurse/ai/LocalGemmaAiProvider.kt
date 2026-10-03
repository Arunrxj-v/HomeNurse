package com.homenurse.ai

import com.homenurse.domain.model.AiRequest
import com.homenurse.domain.model.AiResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Local Gemma implementation of [AiProvider] running on Google AI Edge
 * LiteRT-LM — fully on-device, no network.
 *
 * Flow: build prompt (confirmed context only) → blocking native generation on
 * a dedicated thread (cancellable via [InferenceEngine.cancelActive]) →
 * strictly parse the JSON reply. Unparseable output throws
 * [AiProviderException.MalformedResponse] instead of guessing — raw model
 * text is never logged.
 */
class LocalGemmaAiProvider(
    private val modelManager: ModelManager,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        decodeEnumsCaseInsensitive = true
    },
) : AiProvider {

    override val isReady: Boolean
        get() = modelManager.isReady()

    override suspend fun generate(request: AiRequest): AiResponse {
        val engine = modelManager.engineOrNull() ?: throw AiProviderException.NotReady()
        val prompt = PromptBuilder.buildPrompt(request)

        val raw = try {
            runCancellableGeneration(engine, prompt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AiProviderException) {
            throw error
        } catch (error: Exception) {
            throw AiProviderException.InferenceFailed(error)
        }

        return parse(raw) ?: throw AiProviderException.MalformedResponse()
    }

    private suspend fun runCancellableGeneration(engine: InferenceEngine, prompt: String): String =
        suspendCancellableCoroutine { continuation ->
            val worker = Thread({
                try {
                    val result = engine.generate(
                        prompt = prompt,
                        systemInstruction = PromptBuilder.SYSTEM_INSTRUCTION,
                        maxTokens = MAX_OUTPUT_TOKENS,
                    )
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }, "gemma-inference")
            continuation.invokeOnCancellation { engine.cancelActive() }
            worker.start()
        }

    /** Strict parse: `null` = refuse to use the model's output (tested directly). */
    internal fun parse(raw: String): AiResponse? {
        val candidate = extractJsonObject(raw) ?: return null
        val response = try {
            json.decodeFromString(AiResponse.serializer(), candidate)
        } catch (error: Exception) {
            return null
        }
        if (response.summary.isBlank() || response.explanation.isBlank()) return null
        return response
    }

    /**
     * Extracts the first balanced top-level JSON object, tolerating prose the
     * model may add around it (```json fences, leading words).
     */
    internal fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until raw.length) {
            val char = raw[index]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                char == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, index + 1)
                }
            }
        }
        return null
    }

    companion object {
        const val MAX_OUTPUT_TOKENS = 900
    }
}
