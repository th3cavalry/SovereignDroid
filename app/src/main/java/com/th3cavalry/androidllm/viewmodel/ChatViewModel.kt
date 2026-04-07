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
import com.th3cavalry.androidllm.service.GeminiNanoBackend
import com.th3cavalry.androidllm.service.InferenceBackend
import com.th3cavalry.androidllm.service.LLMService
import com.th3cavalry.androidllm.service.LiteRtLmBackend
import com.th3cavalry.androidllm.service.MCPClient
import com.th3cavalry.androidllm.service.OnDeviceInferenceService
import com.th3cavalry.androidllm.service.ToolExecutor
import kotlinx.coroutines.launch

import java.util.concurrent.atomic.AtomicInteger

/**
 * ViewModel that manages the chat session and the agentic tool-calling loop.
 * Supports multiple inference modes controlled by [Prefs.KEY_INFERENCE_BACKEND]:
 *  - **Remote** (default): calls any OpenAI-compatible API endpoint via [LLMService].
 *  - **MediaPipe**: runs a local .task model via [OnDeviceInferenceService].
 *  - **LiteRT-LM**: runs a local .litertlm model via [LiteRtLmBackend].
 *  - **Ollama on-device**: routes through [LLMService] pointed at localhost:11434
 *    (Ollama must be running in Termux on the same device).
 *
 * The on-device backends use a ReAct-style text-based tool-calling loop; the
 * remote path uses native OpenAI function-calling.
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

    /** Lazily created; only one backend is alive at a time. */
    private var activeBackend: InferenceBackend? = null

    companion object {
        /** MediaPipe caps on-device generation; keep below the model's context window. */
        private const val MAX_ON_DEVICE_TOKENS = 1024
        /** Max tool-call iterations before giving up (both remote and on-device). */
        private const val MAX_REACT_ITERATIONS = 10
        /** Counter for generating unique on-device tool-call IDs. */
        private val toolCallCounter = AtomicInteger(0)
        /** Shared Gson instance — thread-safe for reading after construction. */
        private val gson = Gson()
    }

    init {
        history.add(ChatMessage(role = MessageRole.SYSTEM, content = LLMService.SYSTEM_PROMPT))
    }

    /**
     * Sends the user's message and runs the appropriate agentic loop based on
     * the currently selected inference backend.
     */
    fun sendMessage(userText: String) {
        if (userText.isBlank() || _isLoading.value == true) return

        val userMsg = ChatMessage(role = MessageRole.USER, content = userText)
        history.add(userMsg)
        _messages.value = history.filterVisible()

        _isLoading.value = true
        _error.value = null

        val backendKey = Prefs.getString(
            getApplication(), Prefs.KEY_INFERENCE_BACKEND, Prefs.BACKEND_REMOTE
        )

        viewModelScope.launch {
            try {
                when (backendKey) {
                    Prefs.BACKEND_MEDIAPIPE -> runOnDeviceLoop(userText, getOrCreateBackend<OnDeviceInferenceService>())
                    Prefs.BACKEND_LITERT_LM -> runOnDeviceLoop(userText, getOrCreateBackend<LiteRtLmBackend>())
                    Prefs.BACKEND_GEMINI_NANO -> runOnDeviceLoop(userText, getOrCreateBackend<GeminiNanoBackend>())
                    Prefs.BACKEND_OLLAMA_LOCAL -> {
                        // Ollama runs on the device as a local server; treat it as a remote call
                        // pointed at localhost:11434. The endpoint is already configured in Settings.
                        runRemoteLoop()
                    }
                    else -> runRemoteLoop() // BACKEND_REMOTE (default)
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

    // ─── Remote loop ───────────────────────────────────────────────────────────

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
     * Lazily creates or reuses the backend of type [T], releasing any previously
     * active backend of a different type first.
     */
    private inline fun <reified T : InferenceBackend> getOrCreateBackend(): T {
        val current = activeBackend
        if (current is T) return current
        current?.close()
        val newBackend: T = when (T::class) {
            OnDeviceInferenceService::class -> OnDeviceInferenceService(getApplication()) as T
            LiteRtLmBackend::class         -> LiteRtLmBackend(getApplication()) as T
            GeminiNanoBackend::class       -> GeminiNanoBackend(getApplication()) as T
            else -> error("Unknown backend type ${T::class.simpleName}")
        }
        activeBackend = newBackend
        return newBackend
    }

    /**
     * Runs a ReAct-style (Reasoning + Acting) loop for on-device models:
     *  1. Ensures the [backend] model is loaded (initializing from the stored path if needed).
     *  2. Builds a text prompt from conversation history + tool descriptions.
     *  3. Generates a response on-device.
     *  4. Parses `<tool_call>{json}</tool_call>` tags from the response.
     *  5. Executes the tool, appends the result to the prompt context, and repeats.
     *  6. Stops when the model produces a plain-text response (no tool call).
     */
    private suspend fun runOnDeviceLoop(userText: String, backend: InferenceBackend) {
        // Determine the model-path preference key for this backend type
        // (Gemini Nano has no model file — uses the system model)
        val modelPathKey = when (backend) {
            is LiteRtLmBackend          -> Prefs.KEY_LITERT_LM_MODEL_PATH
            is OnDeviceInferenceService -> Prefs.KEY_ON_DEVICE_MODEL_PATH
            is GeminiNanoBackend        -> null   // No local model file needed
            else -> error("Unhandled backend type: ${backend::class.simpleName}")
        }

        // Ensure the model is loaded
        if (!backend.isReady()) {
            val modelPath = if (modelPathKey != null) {
                Prefs.getString(getApplication(), modelPathKey).also { path ->
                    if (path.isBlank()) {
                        _error.postValue(
                            "No model configured for ${backend.displayName}. " +
                                "Go to Settings → Inference Backend to set a model path."
                        )
                        return
                    }
                }
            } else {
                "" // Gemini Nano ignores the path
            }
            val result = backend.initialize(
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

        // Tag delimiters for extracting on-device tool calls from model output
        val toolCallStartTag = "<tool_call>"
        val toolCallEndTag = "</tool_call>"

        var iterations = 0

        while (iterations < MAX_REACT_ITERATIONS) {
            iterations++

            val fullPrompt = buildOnDeviceSystemPrompt(toolDescriptions) + context
            val rawResponse = backend.generate(fullPrompt).trim()

            // Extract JSON between <tool_call> ... </tool_call> using simple string search
            // rather than regex to correctly handle nested braces in the JSON body.
            // Case-sensitive: the prompt instructs the model to use these exact lowercase tags.
            val startIdx = rawResponse.indexOf(toolCallStartTag)
            val endIdx = rawResponse.indexOf(toolCallEndTag)
            val toolCallJson = if (startIdx >= 0 && endIdx > startIdx) {
                rawResponse.substring(startIdx + toolCallStartTag.length, endIdx).trim()
            } else null

            if (toolCallJson != null) {
                @Suppress("UNCHECKED_CAST")
                val toolCallMap = runCatching {
                    gson.fromJson(toolCallJson, Map::class.java) as? Map<String, Any?>
                }.getOrElse { e ->
                    // Model produced a malformed tool call; report it and treat as final answer
                    val msg = "On-device model returned invalid tool call JSON: ${e.message}"
                    history.add(ChatMessage(role = MessageRole.ASSISTANT, content = msg))
                    _messages.postValue(history.filterVisible())
                    return
                }

                if (toolCallMap == null) {
                    // Gson returned null (e.g., empty JSON); skip and treat as final answer
                    val finalText = rawResponse.trimAssistantPrefix()
                    history.add(ChatMessage(role = MessageRole.ASSISTANT, content = finalText))
                    _messages.postValue(history.filterVisible())
                    break
                }

                val toolName = toolCallMap["name"]?.toString() ?: ""
                @Suppress("UNCHECKED_CAST")
                val toolArgs = toolCallMap["arguments"]
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
                context.appendLine(" $toolCallStartTag$toolCallJson$toolCallEndTag")
                context.appendLine("Tool result ($toolName): $toolResult")
                context.append("Assistant:")

            } else {
                // No tool call tag found → final answer
                history.add(ChatMessage(role = MessageRole.ASSISTANT, content = rawResponse.trimAssistantPrefix()))
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
     * Some on-device models (e.g. Gemma) produce a leading `:` or whitespace when the
     * prompt ends with `"Assistant:"` and generation starts from a blank slate.
     * Strip it so the displayed response starts cleanly.
     */
    private fun String.trimAssistantPrefix(): String = removePrefix(":").trim()

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
        activeBackend?.close()
    }
}

