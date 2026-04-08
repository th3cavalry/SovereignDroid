package com.th3cavalry.androidllm.data

import com.google.gson.annotations.SerializedName

enum class MessageRole(val value: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool")
}

data class ChatMessage(
    val role: MessageRole,
    val content: String?,
    @SerializedName("tool_calls") val toolCalls: List<ToolCallData>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null,
    /** Display name for tool results shown in the UI */
    val toolName: String? = null,
    /** Optional metadata shown under assistant responses (model, tokens, time). */
    val responseInfo: ResponseInfo? = null
)

/** Metadata attached to the final assistant response message. */
data class ResponseInfo(
    val model: String,
    val totalTokens: Int?,
    val durationMs: Long
)

data class ToolCallData(
    val id: String,
    val type: String = "function",
    val function: FunctionCallData
)

data class FunctionCallData(
    val name: String,
    val arguments: String
)
