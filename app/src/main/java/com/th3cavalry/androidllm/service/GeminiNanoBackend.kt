package com.th3cavalry.androidllm.service

import android.content.Context
import com.google.ai.edge.aicore.DownloadCallback
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device inference backend backed by **Google AI Edge / Gemini Nano**
 * (`com.google.ai.edge.aicore:aicore:0.0.1-exp02`).
 *
 * Gemini Nano runs entirely via Android's system AICore service — no model file to
 * download or manage. The model is already installed on supported devices by the OS.
 *
 * ### Device requirements
 * - **Pixel 9 / Pixel 9 Pro / Pixel 9 Pro XL / Pixel 9 Pro Fold** or later
 * - Android 15 (API 35) or higher with AICore pre-installed
 * - Devices without AICore will see [isReady] = false and an informative error message
 *
 * ### What works
 * - Zero-configuration: no model file to pick or copy
 * - Runs entirely on-device with no internet connection required
 * - GPU/NPU accelerated on supported Pixel hardware
 *
 * ### Limitations
 * - Single-turn text generation only (AICore does not expose multi-turn conversation state)
 * - The SDK is experimental (`exp02`); the API surface may change in future releases
 * - Context window is limited by Gemini Nano's architecture (~2 k tokens input)
 *
 * Lifecycle: call [initialize] once; check [isReady]; call [generate]; call [close] when done.
 *
 * @param context Application context (not stored beyond init to avoid leaks).
 */
class GeminiNanoBackend(private val context: Context) : InferenceBackend {

    override val displayName: String = "Gemini Nano (AI Edge)"
    override val modelFileHint: String =
        "No model file required — uses the system Gemini Nano model built into the OS.\n" +
        "Requires: Pixel 9 or newer, Android 15 (API 35)+."

    private var model: GenerativeModel? = null
    private var ready = false
    private var lastError: String? = null

    override fun isReady(): Boolean = ready

    /**
     * Initialises the AI Edge connection and pre-warms the inference engine.
     *
     * [modelPath] is ignored for this backend — Gemini Nano is a system model.
     * [maxTokens] and [temperature] are applied via [GenerationConfig].
     *
     * Returns [Result.failure] with an explanatory [IllegalStateException] if AICore is not
     * available on this device (e.g. non-Pixel hardware or older OS version).
     */
    override suspend fun initialize(
        modelPath: String,
        maxTokens: Int,
        temperature: Float
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val genConfig = generationConfig {
                context      = this@GeminiNanoBackend.context
                maxOutputTokens = maxTokens.coerceIn(64, 1024)
                this.temperature = temperature.coerceIn(0f, 2f)
                topK         = 40
            }

            // DownloadConfig with a no-op callback — the system model is already present
            // on supported devices and does not need an explicit download step here.
            val dlConfig = DownloadConfig(object : DownloadCallback {
                override fun onDownloadStarted(bytesToDownload: Long) {}
                override fun onDownloadProgress(bytesDownloaded: Long) {}
                override fun onDownloadCompleted() {}
                override fun onDownloadFailed(reason: String, e: GenerativeAIException) {
                    lastError = "AICore model download failed: $reason – ${e.message}"
                }
            })

            val m = GenerativeModel(genConfig, dlConfig)
            // prepareInferenceEngine() is a suspend function — called directly
            m.prepareInferenceEngine()
            model = m
            ready = true
            Result.success(Unit)
        } catch (e: Exception) {
            lastError = e.message
            ready = false
            Result.failure(
                IllegalStateException(
                    "Gemini Nano (AI Edge) is not available on this device. " +
                        "It requires a Pixel 9 or newer running Android 15+. " +
                        "Cause: ${e.message}",
                    e
                )
            )
        }
    }

    /**
     * Generates a response using Gemini Nano.
     *
     * [generate] is a suspend function that runs blocking inference on [Dispatchers.IO].
     * For token-by-token streaming, consider calling [generateContentStream] on the underlying
     * [model] directly from a coroutine scope.
     */
    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val m = model ?: return@withContext "[Gemini Nano not initialized]"
        try {
            m.generateContent(prompt).text ?: ""
        } catch (e: Exception) {
            "[Gemini Nano error: ${e.message}]"
        }
    }

    override fun close() {
        try { model?.close() } catch (_: Exception) {}
        model = null
        ready = false
    }
}
