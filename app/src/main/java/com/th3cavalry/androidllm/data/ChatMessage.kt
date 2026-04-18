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
    val responseInfo: ResponseInfo? = null,
    /** Error information if this message represents an error */
    val errorInfo: ErrorInfo? = null,
    /** Tool execution progress information */
    val executingInfo: ExecutingInfo? = null,
    /** Whether this message is currently being streamed (for typing indicator) */
    val isStreaming: Boolean = false,
    /** URI of an attached image (content:// or file://) for vision/multimodal models */
    val imageUri: String? = null
)

/** Metadata for tools currently being executed */
data class ExecutingInfo(
    val toolName: String,
    val status: String? = null,  // Optional status message for long-running operations
    val startTimeMs: Long = System.currentTimeMillis()
)

/** Metadata for error messages displayed in chat */
data class ErrorInfo(
    val message: String,
    val details: String? = null,
    val category: ErrorCategory? = null,
    val isRetryable: Boolean = false,
    val originalMessage: String? = null // Store the user message that caused the error
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
