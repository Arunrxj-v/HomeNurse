package com.homenurse.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * Thin abstraction over the local inference runtime so the AI layer can be
 * unit-tested on the JVM without native libraries.
 *
 * The production implementation wraps Google AI Edge **LiteRT-LM**
 * (`com.google.ai.edge.litertlm:litertlm-android`) — signatures below were
 * verified against the published AAR (javap), not invented.
 */
interface InferenceEngine {

    /**
     * Blocking single-turn generation. [cancelActive] may be invoked from
     * another thread to abort the in-flight native call.
     */
    fun generate(
        prompt: String,
        systemInstruction: String,
        maxTokens: Int,
    ): String

    /** Abort the in-flight generation, if any (safe to call when idle). */
    fun cancelActive()

    fun close()
}

/**
 * LiteRT-LM engine adapter.
 *
 * Verified API surface:
 *  - `EngineConfig(modelPath, backend, ...)` — remaining params defaulted.
 *  - `Engine(config)` / `engine.initialize()` / `engine.close()`.
 *  - `engine.createConversation(ConversationConfig(systemInstruction, maxOutputToken, ...))`.
 *  - `conversation.sendMessage(prompt): Message` (blocking) and
 *    `conversation.cancelProcess()`.
 *  - Text parts: `message.contents.contents` filtered to `Content.Text`.
 *
 * One short-lived conversation per request: system instruction is applied per
 * request and no stale chat history can leak between queries.
 */
class LiteRtLmEngineAdapter(
    modelPath: String,
    cacheDir: File,
) : InferenceEngine {

    private val engine: Engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(),
            cacheDir = cacheDir.absolutePath,
        ),
    ).apply { initialize() }

    @Volatile
    private var activeConversation: Conversation? = null

    override fun generate(prompt: String, systemInstruction: String, maxTokens: Int): String {
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemInstruction),
                maxOutputToken = maxTokens,
            ),
        )
        activeConversation = conversation
        try {
            val message = conversation.sendMessage(prompt)
            return message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { it.text }
        } finally {
            activeConversation = null
            runCatching { conversation.close() }
        }
    }

    override fun cancelActive() {
        activeConversation?.let { conversation ->
            runCatching { conversation.cancelProcess() }
        }
    }

    override fun close() {
        activeConversation = null
        runCatching { engine.close() }
    }
}
