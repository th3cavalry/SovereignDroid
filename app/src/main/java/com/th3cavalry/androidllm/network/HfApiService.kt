package com.th3cavalry.androidllm.network

import com.th3cavalry.androidllm.data.HfModelDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the public **Hugging Face Hub REST API**.
 *
 * Documentation: https://huggingface.co/docs/hub/en/api
 *
 * Base URL: `https://huggingface.co/`
 *
 * Authentication: pass an HF access token in the `Authorization: Bearer <token>` header
 * (added globally in [RetrofitClient.buildHfApi]) to access gated models.
 */
interface HfApiService {

    /**
     * Lists models from a specific author/organisation, ordered by download count.
     *
     * Example URL:
     *   `https://huggingface.co/api/models?author=litert-community&pipeline_tag=text-generation`
     *
     * Responses are **not** paginated by default; use [limit] to control result count.
     */
    @GET("api/models")
    suspend fun listModels(
        @Query("author")        author: String = "litert-community",
        @Query("pipeline_tag") pipelineTag: String = "text-generation",
        @Query("sort")          sort: String = "downloads",
        @Query("direction")     direction: Int = -1,
        @Query("limit")         limit: Int = 50
    ): List<HfModelDto>

    /**
     * Fetches detailed model metadata including the **siblings** (file) list with sizes.
     *
     * Example URL:
     *   `https://huggingface.co/api/models/litert-community/Gemma3-1B-IT?blobs=true`
     *
     * The [modelId] parameter includes the namespace separator `/` and must be URL-encoded;
     * Retrofit's `encoded = true` preserves the slash correctly.
     */
    @GET("api/models/{modelId}")
    suspend fun getModelDetail(
        @Path("modelId", encoded = true) modelId: String,
        @Query("blobs") blobs: Boolean = true
    ): HfModelDto
}
