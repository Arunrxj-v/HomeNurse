package com.homenurse.ai

import com.homenurse.domain.model.AiRequest
import com.homenurse.domain.model.AiResponse

/**
 * Pluggable AI backend. HomeNurse ships [LocalGemmaAiProvider] (on-device
 * Gemma via Google AI Edge LiteRT-LM); the interface exists so the runtime
 * can be replaced without touching use cases or UI.
 *
 * Contract:
 *  * [generate] must run fully on-device for the bundled provider.
 *  * On any failure it throws [AiProviderException] — callers never receive a
 *    fabricated or partial response.
 *  * The provider performs no safety gating itself; [com.homenurse.safety.SafetyEngine]
 *    inspects both the request (before) and the response (after).
 */
interface AiProvider {

    /** True when the model file is installed, the engine initialized and tested. */
    val isReady: Boolean

    suspend fun generate(request: AiRequest): AiResponse
}

sealed class AiProviderException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** Model not downloaded/installed yet — UI should route to model setup. */
    class NotReady : AiProviderException("AI model is not ready")

    /** Model engine failed to initialize (corrupt file, unsupported device). */
    class InitializationFailed(cause: Throwable? = null) :
        AiProviderException("AI model failed to initialize", cause)

    /** Inference failed at runtime. */
    class InferenceFailed(cause: Throwable? = null) :
        AiProviderException("AI inference failed", cause)

    /** The model did not return parseable structured output. Raw text is never logged. */
    class MalformedResponse : AiProviderException("AI response could not be parsed")
}
