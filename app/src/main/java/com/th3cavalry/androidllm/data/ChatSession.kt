package com.th3cavalry.androidllm.data

/** A saved chat conversation that can be persisted and reloaded. */
data class ChatSession(
    /** Unique identifier (epoch millis of creation). */
    val id: Long,
    /** Human-readable title derived from the first user message. */
    val title: String,
    /** Epoch milliseconds timestamp for display purposes. */
    val timestamp: Long,
    /** Full message history (excluding the system prompt). */
    val messages: List<ChatMessage>
)
