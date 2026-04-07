package com.th3cavalry.androidllm.service

import com.google.gson.Gson
import com.th3cavalry.androidllm.data.MCPServer
import com.th3cavalry.androidllm.network.dto.FunctionDefinitionDto
import com.th3cavalry.androidllm.network.dto.ToolDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client for the Model Context Protocol (MCP) over HTTP (Streamable HTTP transport).
 *
 * Spec: https://spec.modelcontextprotocol.io/specification/
 */
class MCPClient(private val server: MCPServer) {

    private val gson = Gson()
    private val requestId = AtomicInteger(0)
    private val jsonMedia = "application/json".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ─── Initialize ───────────────────────────────────────────────────────────

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonRpc("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "clientInfo" to mapOf("name" to "AndroidLLM", "version" to "1.0"),
            "capabilities" to mapOf("tools" to emptyMap<String, Any>())
        ))
        try {
            val result = sendRequest(payload)
            // Send initialized notification
            val notification = buildJsonRpc("notifications/initialized", null, isNotification = true)
            sendNotification(notification)
            result != null
        } catch (e: Exception) {
            false
        }
    }

    // ─── Tool discovery ───────────────────────────────────────────────────────

    suspend fun listTools(): List<ToolDto> = withContext(Dispatchers.IO) {
        val payload = buildJsonRpc("tools/list", emptyMap<String, Any>())
        val result = try {
            sendRequest(payload)
        } catch (e: Exception) {
            return@withContext emptyList()
        } ?: return@withContext emptyList()

        parseToolsList(result)
    }

    // ─── Tool execution ───────────────────────────────────────────────────────

    suspend fun callTool(toolName: String, arguments: Map<String, Any?>): String =
        withContext(Dispatchers.IO) {
            val payload = buildJsonRpc("tools/call", mapOf(
                "name" to toolName,
                "arguments" to arguments
            ))
            return@withContext try {
                val result = sendRequest(payload)
                extractToolResult(result)
            } catch (e: Exception) {
                "MCP tool error: ${e.message}"
            }
        }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildJsonRpc(
        method: String,
        params: Any?,
        isNotification: Boolean = false
    ): String {
        val map = mutableMapOf<String, Any?>(
            "jsonrpc" to "2.0",
            "method" to method
        )
        if (!isNotification) map["id"] = requestId.incrementAndGet()
        if (params != null) map["params"] = params
        return gson.toJson(map)
    }

    private fun sendRequest(jsonBody: String): Map<*, *>? {
        val request = Request.Builder()
            .url(server.url)
            .post(jsonBody.toRequestBody(jsonMedia))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: return null

        // Handle SSE responses (text/event-stream)
        val contentType = response.header("Content-Type") ?: ""
        val jsonText = if (contentType.contains("event-stream")) {
            extractSseData(body)
        } else {
            body
        }

        @Suppress("UNCHECKED_CAST")
        val parsed = gson.fromJson(jsonText, Map::class.java) as? Map<*, *> ?: return null

        if (parsed.containsKey("error")) {
            val error = parsed["error"]
            throw RuntimeException("MCP error: $error")
        }

        return parsed["result"] as? Map<*, *>
    }

    private fun sendNotification(jsonBody: String) {
        try {
            val request = Request.Builder()
                .url(server.url)
                .post(jsonBody.toRequestBody(jsonMedia))
                .header("Content-Type", "application/json")
                .build()
            httpClient.newCall(request).execute().close()
        } catch (_: Exception) {
            // Notifications don't need a response
        }
    }

    private fun extractSseData(sseBody: String): String {
        // SSE format: lines starting with "data: "
        for (line in sseBody.lines()) {
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
                if (data != "[DONE]" && data.isNotEmpty()) return data
            }
        }
        return "{}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseToolsList(result: Map<*, *>?): List<ToolDto> {
        val tools = result?.get("tools") as? List<*> ?: return emptyList()
        return tools.mapNotNull { rawTool ->
            val tool = rawTool as? Map<*, *> ?: return@mapNotNull null
            val name = tool["name"]?.toString() ?: return@mapNotNull null
            val description = tool["description"]?.toString() ?: ""
            val inputSchema = (tool["inputSchema"] as? Map<*, *>)?.let {
                @Suppress("UNCHECKED_CAST")
                it as Map<String, Any?>
            } ?: mapOf("type" to "object", "properties" to emptyMap<String, Any>())

            ToolDto(
                function = FunctionDefinitionDto(
                    name = "${server.name}__$name",  // Namespace tool with server name
                    description = "[${server.name}] $description",
                    parameters = inputSchema
                )
            )
        }
    }

    private fun extractToolResult(result: Map<*, *>?): String {
        if (result == null) return "No result from MCP tool"
        val content = result["content"] as? List<*> ?: return result.toString()
        return content.joinToString("\n") { item ->
            val itemMap = item as? Map<*, *> ?: return@joinToString item.toString()
            when (itemMap["type"]?.toString()) {
                "text" -> itemMap["text"]?.toString() ?: ""
                "image" -> "[Image: ${itemMap["mimeType"]}]"
                else -> itemMap.toString()
            }
        }
    }
}
