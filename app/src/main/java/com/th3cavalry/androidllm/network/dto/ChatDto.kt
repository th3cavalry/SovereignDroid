package com.th3cavalry.androidllm.network.dto

import com.google.gson.annotations.SerializedName

// ─── Request ────────────────────────────────────────────────────────────────

data class ChatRequest(
    val model: String,
    val messages: List<MessageDto>,
    val tools: List<ToolDto>? = null,
    val stream: Boolean = false,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    val temperature: Float? = null
)

data class MessageDto(
    val role: String,
    val content: String?,
    @SerializedName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null
)

data class ToolDto(
    val type: String = "function",
    val function: FunctionDefinitionDto
)

data class FunctionDefinitionDto(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>
)

data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: FunctionCallDto
)

data class FunctionCallDto(
    val name: String,
    val arguments: String
)

// ─── Response ────────────────────────────────────────────────────────────────

data class ChatResponse(
    val id: String?,
    val `object`: String?,
    val model: String?,
    val choices: List<ChoiceDto>,
    val usage: UsageDto?
)

data class ChoiceDto(
    val index: Int,
    val message: MessageDto,
    @SerializedName("finish_reason") val finishReason: String?
)

data class UsageDto(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("completion_tokens") val completionTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int
)

// ─── Models list ─────────────────────────────────────────────────────────────

data class ModelsResponse(
    val `object`: String?,
    val data: List<ModelDto>
)

data class ModelDto(
    val id: String,
    val `object`: String?,
    val owned_by: String?
)
