package com.th3cavalry.androidllm.service

/**
 * Common interface implemented by every local (on-device) inference backend.
 *
 * Implementations:
 *  - [OnDeviceInferenceService] — MediaPipe LLM Inference API (.task files)
 *  - [LiteRtLmBackend]          — Google LiteRT-LM API (.litertlm files)
 *
 * Remote inference (OpenAI-compatible API, Ollama, etc.) is handled separately
 * by [LLMService] and does not go through this interface.
 *
 * All suspending methods dispatch to [kotlinx.coroutines.Dispatchers.IO] internally.
 */
interface InferenceBackend {

    /** Short name shown in the Settings backend-selector UI. */
    val displayName: String

    /**
     * Human-readable hint about the supported model format and recommended models,
     * shown below the model-path field in Settings.
     */
    val modelFileHint: String

    /**
     * Loads the model from [modelPath] (absolute file-system path).
     * Safe to call again to hot-swap to a different model; any previously
     * loaded model is released first.
     *
     * @return [Result.success] on success; [Result.failure] containing the
     *   exception if the model could not be loaded.
     */
    suspend fun initialize(modelPath: String, maxTokens: Int, temperature: Float): Result<Unit>

    /** Returns `true` if a model has been successfully loaded via [initialize]. */
    fun isReady(): Boolean

    /**
     * Generates a response for [prompt] using the loaded model.
     *
     * @throws IllegalStateException if [initialize] has not been called or failed.
     */
    suspend fun generate(prompt: String): String

    /** Releases the loaded model and frees GPU/CPU resources. */
    fun close()
}
