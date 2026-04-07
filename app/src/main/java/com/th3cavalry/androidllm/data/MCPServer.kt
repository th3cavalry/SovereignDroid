package com.th3cavalry.androidllm.data

/**
 * Represents an MCP (Model Context Protocol) server configuration.
 */
data class MCPServer(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val description: String = ""
)
