package com.th3cavalry.androidllm.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    fun buildOkHttp(timeoutSeconds: Long = 120): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()

    fun buildLLMApi(baseUrl: String): LLMApi {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(buildOkHttp())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LLMApi::class.java)
    }

    /**
     * Builds a [HfApiService] for the Hugging Face Hub REST API.
     *
     * @param hfToken Optional HF personal access token.  When provided it is sent as
     *   `Authorization: Bearer <token>` on every request, unlocking gated model downloads.
     */
    fun buildHfApi(hfToken: String = ""): HfApiService {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .apply {
                if (hfToken.isNotBlank()) {
                    addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Authorization", "Bearer $hfToken")
                                .build()
                        )
                    }
                }
            }
            .build()

        return Retrofit.Builder()
            .baseUrl("https://huggingface.co/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HfApiService::class.java)
    }
}
