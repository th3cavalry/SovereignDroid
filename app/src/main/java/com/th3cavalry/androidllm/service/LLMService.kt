package com.th3cavalry.androidllm.service

import android.content.Context
import com.google.gson.Gson
import com.th3cavalry.androidllm.Prefs
import com.th3cavalry.androidllm.data.ResponseInfo
import com.th3cavalry.androidllm.data.ChatMessage
import com.th3cavalry.androidllm.data.FunctionCallData
import com.th3cavalry.androidllm.data.MessageRole
import com.th3cavalry.androidllm.data.ToolCallData
import com.th3cavalry.androidllm.network.RetrofitClient
import com.th3cavalry.androidllm.network.dto.*

/**
 * Orchestrates the LLM interaction including the multi-step tool-calling loop.
 */
class LLMService(private val context: Context) {

    private val gson = Gson()

    private fun buildApi() = RetrofitClient.buildLLMApi(
        Prefs.getString(context, Prefs.KEY_LLM_ENDPOINT, Prefs.DEFAULT_ENDPOINT)
    )

    private fun buildAuth(): String {
        val key = Prefs.getString(context, Prefs.KEY_LLM_API_KEY)
        return if (key.isBlank()) "Bearer none" else "Bearer $key"
    }

    private fun model(): String =
        Prefs.getString(context, Prefs.KEY_LLM_MODEL, Prefs.DEFAULT_MODEL)

    private fun maxTokens(): Int =
        Prefs.getInt(context, Prefs.KEY_LLM_MAX_TOKENS, Prefs.DEFAULT_MAX_TOKENS)

    private fun temperature(): Float =
        Prefs.getFloat(context, Prefs.KEY_LLM_TEMPERATURE, Prefs.DEFAULT_TEMPERATURE)

    /**
     * Runs the full agentic loop: sends messages to the LLM, executes any tool calls,
     * then feeds results back until the model returns a plain text response.
     *
     * @param history  The current conversation history (will be mutated with tool messages).
     * @param tools    All available tools (built-in + MCP).
     * @param onProgress Callback invoked whenever the history changes (e.g., a tool was called).
     * @return The final assistant reply text.
     */
    suspend fun chat(
        history: MutableList<ChatMessage>,
        tools: List<ToolDto>,
        onProgress: (suspend (List<ChatMessage>) -> Unit)? = null
    ): String {
        val api = buildApi()
        var iterations = 0
        val maxIterations = 10
        val startMs = System.currentTimeMillis()

        while (iterations < maxIterations) {
            iterations++

            val request = ChatRequest(
                model = model(),
                messages = history.toMessageDtoList(),
                tools = tools.takeIf { it.isNotEmpty() },
                maxTokens = maxTokens(),
                temperature = temperature()
            )

            val response = api.chatCompletion(buildAuth(), request)

            if (!response.isSuccessful) {
                val error = response.errorBody()?.string() ?: "Unknown error"
                return "Error ${response.code()}: $error"
            }

            val body = response.body() ?: return "Empty response from LLM"
            val choice = body.choices.firstOrNull() ?: return "No choices in LLM response"
            val message = choice.message

            // If the model wants to call tools
            if (!message.toolCalls.isNullOrEmpty()) {
                // Add the assistant's tool_calls message to history
                history.add(
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = message.content,
                        toolCalls = message.toolCalls.map { tc ->
                            ToolCallData(
                                id = tc.id,
                                type = tc.type,
                                function = FunctionCallData(tc.function.name, tc.function.arguments)
                            )
                        }
                    )
                )
                onProgress?.invoke(history.toList())

                // Execute each tool call
                val executor = ToolExecutor(context)
                for (toolCall in message.toolCalls) {
                    val result = try {
                        val args = gson.fromJson(toolCall.function.arguments, Map::class.java)
                            ?.mapKeys { it.key.toString() }
                            ?.mapValues { it.value }
                            ?: emptyMap()
                        executor.execute(toolCall.function.name, args)
                    } catch (e: Exception) {
                        "Error executing tool ${toolCall.function.name}: ${e.message}"
                    }

                    history.add(
                        ChatMessage(
                            role = MessageRole.TOOL,
                            content = result,
                            toolCallId = toolCall.id,
                            toolName = toolCall.function.name
                        )
                    )
                }
                onProgress?.invoke(history.toList())
                // Continue the loop so the model can see tool results
                continue
            }

            // Plain text response — we're done
            val finalText = message.content ?: "(no response)"
            val durationMs = System.currentTimeMillis() - startMs
            val responseInfo = ResponseInfo(
                model = body.model ?: model(),
                totalTokens = body.usage?.totalTokens,
                durationMs = durationMs
            )
            history.add(ChatMessage(role = MessageRole.ASSISTANT, content = finalText, responseInfo = responseInfo))
            return finalText
        }

        return "Maximum tool-call iterations reached."
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun List<ChatMessage>.toMessageDtoList(): List<MessageDto> = map { msg ->
        MessageDto(
            role = msg.role.value,
            content = msg.content,
            toolCalls = msg.toolCalls?.map { tc ->
                ToolCallDto(
                    id = tc.id,
                    type = tc.type,
                    function = FunctionCallDto(tc.function.name, tc.function.arguments)
                )
            },
            toolCallId = msg.toolCallId
        )
    }

    companion object {
        /** Built-in tool definitions sent with every LLM request. */
        fun builtInTools(): List<ToolDto> = listOf(
            ToolDto(
                function = FunctionDefinitionDto(
                    name = "web_search",
                    description = "Search the web for information. Use this when you need current or factual information.",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "query" to mapOf("type" to "string", "description" to "The search query"),
                            "num_results" to mapOf("type" to "integer", "description" to "Number of results (1-10, default 5)")
                        ),
                        "required" to listOf("query")
                    )
                )
            ),
            ToolDto(
                function = FunctionDefinitionDto(
                    name = "fetch_url",
                    description = "Fetch the text content of a web page or URL.",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "url" to mapOf("type" to "string", "description" to "The URL to fetch")
                        ),
                        "required" to listOf("url")
                    )
                )
            ),
            ToolDto(
                function = FunctionDefinitionDto(
                    name = "ssh_execute",
                    description = "Execute a command on a remote server via SSH. Use for system administration, checking system resources, running scripts, etc.",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "host" to mapOf("type" to "string", "description" to "Hostname or IP address"),
                            "port" to mapOf("type" to "integer", "description" to "SSH port (default 22)"),
                            "username" to mapOf("type" to "string", "description" to "SSH username"),
                            "password" to mapOf("type" to "string", "description" to "SSH password (use instead of private_key)"),
                            "private_key" to mapOf("type" to "string", "description" to "PEM private key content (use instead of password)"),
                            "command" to mapOf("type" to "string", "description" to "Command to execute")
                        ),
                        "required" to listOf("host", "username", "command")
                    )
                )
            ),
            ToolDto(
                function = FunctionDefinitionDto(
                    name = "github_read_file",
                    description = "Read a file from a GitHub repository.",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "owner" to mapOf("type" to "string", "description" to "Repository owner/organization"),
                            "repo" to mapOf("type" to "string", "description" to "Repository name"),
                            "path" to mapOf("type" to "string", "description" to "File path in the repository"),
                            "ref" to mapOf("type" to "string", "description" to "Branch, tag or commit SHA (default: main)")
                        ),
                        "required" to listOf("owner", "repo", "path")
                    )
                )
            ),
            ToolDto(
                function = FunctionDefinitionDto(
                    name = "github_write_file",
                    description = "Create or update a file in a GitHub repository. Requires GitHub token in settings.",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "owner" to mapOf("type" to "string", "description" to "Repository owner/organization"),
                            "repo" to mapOf("type" to "string", "description" to "Repository name"),
                            "path" to mapOf("type" to "string", "description" to "File path in the repository"),
                            "content" to mapOf("type" to "string", "description" to "File content (plain text)"),
                            "message" to mapOf("type" to "string", "description" to "Commit message"),
                            "branch" to mapOf("type" to "string", "description" to "Branch name (default: main)"),
                            "sha" to mapOf("type" to "string", "description" to "Current file SHA (required for updates, omit for new files)")
                        ),
                        "required" to listOf("owner", "repo", "path", "content", "message")
                    )
                )
            ),
            ToolDto(
                function = FunctionDefinitionDto(
                    name = "github_list_files",
                    description = "List files in a GitHub repository directory.",
                    parameters = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "owner" to mapOf("type" to "string", "description" to "Repository owner/organization"),
                            "repo" to mapOf("type" to "string", "description" to "Repository name"),
                            "path" to mapOf("type" to "string", "description" to "Directory path (use empty string or '/' for root)"),
                            "ref" to mapOf("type" to "string", "description" to "Branch, tag or commit SHA (default: main)")
                        ),
                        "required" to listOf("owner", "repo")
                    )
                )
            )
        )

        val SYSTEM_PROMPT = """
You are a powerful AI assistant running on Android with access to several tools:

- **web_search**: Search the internet for current information
- **fetch_url**: Read any web page or URL
- **ssh_execute**: Run commands on remote servers via SSH
- **github_read_file**: Read files from GitHub repositories
- **github_write_file**: Create or update files in GitHub repositories  
- **github_list_files**: Browse GitHub repository structure

Use tools proactively when needed to give accurate, helpful responses. For SSH tasks, always confirm success. For GitHub operations, always verify the content before writing.
        """.trimIndent()
    }
}
