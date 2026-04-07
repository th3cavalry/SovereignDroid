package com.th3cavalry.androidllm.service

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wraps the MediaPipe LLM Inference API for fully on-device model execution.
 *
 * Supported model format: LiteRT (.task) files.
 * Recommended starter models (pre-converted .task files available on Kaggle):
 *   - Gemma 2B-IT  (needs ~4 GB device RAM, GPU-accelerated)
 *   - Gemma 7B-IT  (needs ~8 GB device RAM)
 *   - Phi-2        (smaller, ~2 GB)
 *   - Falcon 1B
 *
 * How to get a model:
 *   1. Visit https://www.kaggle.com/models/google/gemma and download the
 *      "gemma-2b-it-gpu-int4.bin" or similar .task file.
 *   2. Copy it to the device (ADB or in-app file picker).
 *   3. Set the path in Settings → On-Device Model.
 *
 * Lifecycle: call [initialize] before [generate]; call [close] when done.
 */
class OnDeviceInferenceService(private val context: Context) : InferenceBackend {

    override val displayName = "MediaPipe (.task files)"
    override val modelFileHint =
        "Supports .task format. Recommended: Gemma 2B-IT INT4 (~1.4 GB), Phi-2 (~1.6 GB), " +
            "Falcon 1B (~1 GB). Download .task files from kaggle.com/models/google/gemma"

    private var llmInference: LlmInference? = null

    companion object {
        /** Top-K sampling: limits vocabulary to the K most likely tokens each step. */
        private const val SAMPLING_TOP_K = 40
    }

    /**
     * Loads the model from [modelPath] (absolute file-system path to a .task file).
     * Must be called before [generate]. Safe to call again to hot-swap models.
     *
     * @return [Result.success] if the model loaded; [Result.failure] with the exception otherwise.
     */
    override suspend fun initialize(
        modelPath: String,
        maxTokens: Int,
        temperature: Float
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(modelPath.isNotBlank()) { "Model path must not be blank." }
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .setMaxTopK(SAMPLING_TOP_K)
                .build()
            // Release any previously loaded model before loading a new one
            llmInference?.close()
            llmInference = LlmInference.createFromOptions(context, options)
        }
    }

    /** Returns true if a model has been successfully loaded. */
    override fun isReady(): Boolean = llmInference != null

    /**
     * Generates a response for [prompt] on the device (blocking, run on IO dispatcher).
     *
     * @throws IllegalStateException if [initialize] was not called or failed.
     */
    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        llmInference?.generateResponse(prompt)
            ?: error("On-device model is not loaded. Configure a model path in Settings.")
    }

    /** Releases the model and frees GPU/CPU memory. */
    override fun close() {
        llmInference?.close()
        llmInference = null
    }
}
