package com.th3cavalry.androidllm.network

import com.th3cavalry.androidllm.network.dto.ChatRequest
import com.th3cavalry.androidllm.network.dto.ChatResponse
import com.th3cavalry.androidllm.network.dto.ModelsResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface LLMApi {

    @POST("chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): Response<ChatResponse>

    @GET("models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<ModelsResponse>
}
