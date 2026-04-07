package com.th3cavalry.androidllm.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.th3cavalry.androidllm.Prefs
import com.th3cavalry.androidllm.data.ChatMessage
import com.th3cavalry.androidllm.data.FunctionCallData
import com.th3cavalry.androidllm.data.MCPServer
import com.th3cavalry.androidllm.data.MessageRole
import com.th3cavalry.androidllm.data.ToolCallData
import com.th3cavalry.androidllm.network.dto.ToolDto
import com.th3cavalry.androidllm.service.LLMService
import com.th3cavalry.androidllm.service.MCPClient
import com.th3cavalry.androidllm.service.OnDeviceInferenceService
import com.th3cavalry.androidllm.service.ToolExecutor
import kotlinx.coroutines.launch

import java.util.concurrent.atomic.AtomicInteger

/**
 * ViewModel that manages the chat session and the agentic tool-calling loop.
 * Supports two inference modes:
 *  - **Remote** (default): calls any OpenAI-compatible API endpoint via [LLMService].
 *  - **On-device**: runs a local .task model via [OnDeviceInferenceService] with a
 *    ReAct-style text-based tool-calling loop (no native function calling required).
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    /** Mutable conversation history (passed to the LLM each turn). */
    private val history: MutableList<ChatMessage> = mutableListOf()

    private val llmService = LLMService(application)
    private val onDeviceService = OnDeviceInferenceService(application)
    private val gson = Gson()

    companion object {
        /** MediaPipe caps on-device generation; keep below the model's context window. */
        private const val MAX_ON_DEVICE_TOKENS = 1024
        /** Max tool-call iterations before giving up (both remote and on-device). */
        private const val MAX_REACT_ITERATIONS = 10
        /** Counter for generating unique on-device tool-call IDs. */
        private val toolCallCounter = AtomicInteger(0)
    }

    init {
        history.add(ChatMessage(role = MessageRole.SYSTEM, content = LLMService.SYSTEM_PROMPT))
    }

    /**
     * Sends the user's message and runs the appropriate agentic loop based on
     * the current inference mode (on-device or remote).
     */
    fun sendMessage(userText: String) {
        if (userText.isBlank() || _isLoading.value == true) return

        val userMsg = ChatMessage(role = MessageRole.USER, content = userText)
        history.add(userMsg)
        _messages.value = history.filterVisible()

        _isLoading.value = true
        _error.value = null

        val useOnDevice = Prefs.getBoolean(getApplication(), Prefs.KEY_ON_DEVICE_ENABLED)

        viewModelScope.launch {
            try {
                if (useOnDevice) {
                    runOnDeviceLoop(userText)
                } else {
                    runRemoteLoop()
                }
            } catch (e: Exception) {
                _error.postValue("Error: ${e.message}")
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    /** Clears the conversation (keeps the system prompt). */
    fun clearHistory() {
        history.clear()
        history.add(ChatMessage(role = MessageRole.SYSTEM, content = LLMService.SYSTEM_PROMPT))
        _messages.value = emptyList()
    }

    // ─── Remote loop ──────────────────────────────────────────────────────────

    private suspend fun runRemoteLoop() {
        val tools = buildToolsList()
        llmService.chat(
            history = history,
            tools = tools,
            onProgress = { updatedHistory ->
                _messages.postValue(updatedHistory.filterVisible())
            }
        )
        _messages.postValue(history.filterVisible())
    }

    // ─── On-device loop (ReAct pattern) ───────────────────────────────────────

    /**
     * Runs a ReAct-style (Reasoning + Acting) loop for on-device models:
     *  1. Builds a text prompt from conversation history + tool descriptions.
     *  2. Generates a response on-device.
     *  3. Parses `<tool_call>{json}</tool_call>` tags from the response.
     *  4. Executes the tool, appends the result to the prompt context, repeats.
     *  5. Stops when the model produces a plain-text response (no tool call).
     */
    private suspend fun runOnDeviceLoop(userText: String) {
        // Ensure the model is loaded
        if (!onDeviceService.isReady()) {
            val modelPath = Prefs.getString(getApplication(), Prefs.KEY_ON_DEVICE_MODEL_PATH)
            if (modelPath.isBlank()) {
                _error.postValue(
                    "No on-device model configured. Go to Settings → On-Device Model to set one up."
                )
                return
            }
            val result = onDeviceService.initialize(
                modelPath = modelPath,
                maxTokens = Prefs.getInt(
                    getApplication(), Prefs.KEY_LLM_MAX_TOKENS, Prefs.DEFAULT_MAX_TOKENS
                ).coerceAtMost(MAX_ON_DEVICE_TOKENS),
                temperature = Prefs.getFloat(
                    getApplication(), Prefs.KEY_LLM_TEMPERATURE, Prefs.DEFAULT_TEMPERATURE
                )
            )
            if (result.isFailure) {
                _error.postValue("Failed to load model: ${result.exceptionOrNull()?.message}")
                return
            }
        }

        val tools = buildToolsList()
        val toolDescriptions = buildToolDescriptionsText(tools)
        val executor = ToolExecutor(getApplication())

        // Growing context appended to on each iteration (in text form for the LLM)
        val context = StringBuilder()

        // Replay prior turns into the context (skip system and tool-role messages)
        for (msg in history.dropLast(1)) {
            when (msg.role) {
                MessageRole.USER -> context.appendLine("User: ${msg.content}")
                MessageRole.ASSISTANT -> {
                    if (msg.toolCalls.isNullOrEmpty()) {
                        context.appendLine("Assistant: ${msg.content}")
                    }
                }
                else -> {}
            }
        }
        context.append("User: $userText\nAssistant:")

        val toolCallRegex = Regex(
            "<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        var iterations = 0

        while (iterations < MAX_REACT_ITERATIONS) {
            iterations++

            val fullPrompt = buildOnDeviceSystemPrompt(toolDescriptions) + context
            val rawResponse = onDeviceService.generate(fullPrompt).trim()

            val toolMatch = toolCallRegex.find(rawResponse)

            if (toolMatch != null) {
                val toolCallJson = toolMatch.groupValues[1].trim()

                @Suppress("UNCHECKED_CAST")
                val toolCallMap = runCatching {
                    gson.fromJson(toolCallJson, Map::class.java) as? Map<String, Any?>
                }.getOrNull()

                val toolName = toolCallMap?.get("name")?.toString() ?: ""
                @Suppress("UNCHECKED_CAST")
                val toolArgs = toolCallMap?.get("arguments")
                    ?.let { it as? Map<String, Any?> }
                    ?: emptyMap()

                // Show the tool call in the chat UI
                val callId = "od_${toolCallCounter.incrementAndGet()}"
                history.add(
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = null,
                        toolCalls = listOf(
                            ToolCallData(
                                id = callId,
                                type = "function",
                                function = FunctionCallData(toolName, toolCallJson)
                            )
                        )
                    )
                )
                _messages.postValue(history.filterVisible())

                // Execute the tool
                val toolResult = runCatching {
                    executor.execute(toolName, toolArgs)
                }.getOrElse { "Error: ${it.message}" }

                // Show the tool result in the chat UI
                history.add(
                    ChatMessage(
                        role = MessageRole.TOOL,
                        content = toolResult,
                        toolCallId = callId,
                        toolName = toolName
                    )
                )
                _messages.postValue(history.filterVisible())

                // Grow the context so the model can see the result
                context.appendLine(" <tool_call>$toolCallJson</tool_call>")
                context.appendLine("Tool result ($toolName): $toolResult")
                context.append("Assistant:")

            } else {
                // No tool call → final answer
                val finalText = rawResponse.removePrefix(":").trim()
                history.add(ChatMessage(role = MessageRole.ASSISTANT, content = finalText))
                _messages.postValue(history.filterVisible())
                break
            }
        }

        if (iterations >= MAX_REACT_ITERATIONS) {
            val msg = "Maximum reasoning steps reached."
            history.add(ChatMessage(role = MessageRole.ASSISTANT, content = msg))
            _messages.postValue(history.filterVisible())
        }
    }

    /**
     * Builds the system-level portion of the on-device prompt.
     * The prompt instructs the model to use `<tool_call>{json}</tool_call>` syntax,
     * which modern instruction-tuned models (Gemma, Phi, Llama, etc.) follow reliably.
     */
    private fun buildOnDeviceSystemPrompt(toolDescriptions: String): String = """
You are a powerful AI assistant. You have access to the following tools:

$toolDescriptions

To call a tool, output EXACTLY this and nothing else on that turn:
<tool_call>{"name":"tool_name","arguments":{"param":"value"}}</tool_call>

After a tool result is given, continue reasoning. When you have the final answer, respond normally without any <tool_call> tags.

""".trimIndent() + "\n"

    /**
     * Renders the available [tools] as a human-readable description for on-device prompts.
     */
    private fun buildToolDescriptionsText(tools: List<ToolDto>): String =
        tools.joinToString("\n") { tool ->
            val params = tool.function.parameters["properties"]
                ?.let { it as? Map<*, *> }
                ?.entries
                ?.joinToString(", ") { (k, v) ->
                    val desc = (v as? Map<*, *>)?.get("description")?.toString() ?: ""
                    "$k: $desc"
                } ?: ""
            "• ${tool.function.name}: ${tool.function.description}" +
                if (params.isNotEmpty()) "\n  Parameters: $params" else ""
        }

    // ─── Shared helpers ───────────────────────────────────────────────────────

    /**
     * Builds the full tools list: built-in tools + tools from enabled MCP servers.
     */
    private suspend fun buildToolsList(): List<ToolDto> {
        val tools = LLMService.builtInTools().toMutableList()

        val mcpServers: List<MCPServer> = Prefs.getMCPServers(getApplication())
        for (server in mcpServers.filter { it.enabled }) {
            try {
                val client = MCPClient(server)
                if (client.initialize()) {
                    tools.addAll(client.listTools())
                }
            } catch (e: Exception) {
                // Skip unavailable MCP servers
            }
        }

        return tools
    }

    /** Filters out system messages for display. */
    private fun List<ChatMessage>.filterVisible(): List<ChatMessage> =
        filter { it.role != MessageRole.SYSTEM }

    override fun onCleared() {
        super.onCleared()
        onDeviceService.close()
    }
}

