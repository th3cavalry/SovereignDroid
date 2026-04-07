package com.th3cavalry.androidllm.service

import android.content.Context
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device inference backend backed by the **Google LiteRT-LM API**
 * (`com.google.ai.edge.litertlm:litertlm-android`).
 *
 * LiteRT-LM is Google's successor to the MediaPipe LLM Inference API and supports
 * a wider range of models (Gemma 4, Phi-4, Llama, Qwen) in the `.litertlm` format.
 * Models are available from the `litert-community` organisation on Hugging Face:
 *   https://huggingface.co/litert-community
 *
 * Acceleration: GPU (OpenCL/OpenGL), NPU (on supported chipsets), CPU fallback.
 * Requires Android 10+ and at least 4 GB RAM for 1B+ parameter models.
 *
 * Note: Updated to `0.10.0` (stable release). The API was previously alpha-only.
 *
 * Lifecycle: call [initialize] before [generate]; call [close] when done.
 *
 * @param context Android context. Currently unused by [EngineConfig] but retained
 *   for forward compatibility — the Android-specific LiteRT-LM runtime is expected
 *   to require a context for GPU/NPU device initialisation in a future release.
 */
class LiteRtLmBackend(private val context: Context) : InferenceBackend {

    override val displayName = "LiteRT-LM (.litertlm files)"
    override val modelFileHint =
        "Supports .litertlm format (Google's new on-device LLM SDK). " +
            "Recommended models: Gemma 4, Phi-4 Mini, Llama 3.2, Qwen 2.5. " +
            "Download from huggingface.co/litert-community"

    private var engine: Engine? = null

    companion object {
        /**
         * Default max tokens limit for context. Controls how many tokens can be processed
         * in one generation step. Larger values allow longer conversations but require more RAM.
         */
        private const val DEFAULT_MAX_TOKENS = 2048
    }

    /**
     * Loads the model at [modelPath] (absolute path to a `.litertlm` file).
     * [maxTokens] sets the context window; [temperature] is not part of [EngineConfig]
     * in v0.10.0 (it can be set per-conversation via [ConversationConfig]).
     */
    override suspend fun initialize(
        modelPath: String,
        maxTokens: Int,
        temperature: Float
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(modelPath.isNotBlank()) { "Model path must not be blank." }
            // Release any previously loaded engine before loading a new one
            engine?.close()
            engine = null

            val config = EngineConfig(
                modelPath    = modelPath,
                maxNumTokens = maxTokens.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS,
                cacheDir     = context.cacheDir.absolutePath
            )
            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
        }
    }

    override fun isReady(): Boolean = engine != null

    /**
     * Generates a response for [prompt] on-device.
     * Opens a fresh [com.google.ai.edge.litertlm.Conversation] per call so that the
     * LiteRT-LM engine does not carry state between separate ReAct iterations;
     * the full prompt already contains all prior context.
     */
    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val eng = engine
            ?: error("LiteRT-LM engine is not loaded. Configure a model path in Settings.")
        val result = StringBuilder()
        // Message.of(String) creates a user message from plain text
        val message = Message.of(prompt)
        eng.createConversation().use { conversation ->
            conversation.sendMessageAsync(message).collect { chunk ->
                // Each chunk is a Message; extract text content from it
                chunk.contents.filterIsInstance<Content.Text>()
                    .forEach { result.append(it.text) }
            }
        }
        result.toString()
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
