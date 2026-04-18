package com.th3cavalry.androidllm.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.th3cavalry.androidllm.Prefs
import com.th3cavalry.androidllm.data.ChatMessage
import com.th3cavalry.androidllm.data.ChatSession
import com.th3cavalry.androidllm.data.ConnectionError
import com.th3cavalry.androidllm.data.FunctionCallData
import com.th3cavalry.androidllm.data.MCPServer
import com.th3cavalry.androidllm.data.MessageRole
import com.th3cavalry.androidllm.data.NetworkError
import com.th3cavalry.androidllm.data.ResponseInfo
import com.th3cavalry.androidllm.data.TimeoutError
import com.th3cavalry.androidllm.data.ToolCallData
import com.th3cavalry.androidllm.data.toAppError
import com.th3cavalry.androidllm.db.ChatRepository
import com.th3cavalry.androidllm.network.dto.ToolDto
import com.th3cavalry.androidllm.service.GeminiNanoBackend
import com.th3cavalry.androidllm.service.InferenceBackend
import com.th3cavalry.androidllm.service.LLMService
import com.th3cavalry.androidllm.service.LiteRtLmBackend
import com.th3cavalry.androidllm.service.MCPClient
import com.th3cavalry.androidllm.service.OnDeviceInferenceService
import com.th3cavalry.androidllm.service.ToolExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import android.app.ActivityManager
import android.content.Context
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

    /** Memory warning shown to the user when available RAM is low. */
    private val _memoryWarning = MutableLiveData<String?>(null)
    val memoryWarning: LiveData<String?> = _memoryWarning

    /** Mutable conversation history (passed to the LLM each turn). */
    private val history: MutableList<ChatMessage> = mutableListOf()

    /** ID of the active session (set when the user first sends a message or loads a session). */
    private var activeSessionId: Long = System.currentTimeMillis()

    private val llmService = LLMService(application)

    /** Room-backed repository for chat session persistence. */
    private val chatRepo = ChatRepository(application)

    /** Optional document context injected via RAG file loading. */
    private val _documentContext = MutableLiveData<String?>(null)
    val documentContext: LiveData<String?> = _documentContext

    /** Lazily created; only one backend is alive at a time. */
    private var activeBackend: InferenceBackend? = null

    /** The currently running generation job, cancellable via [stopGeneration]. */
    private var currentJob: Job? = null

    /** Callback registered with [App] to handle low-memory events from the system. */
    private val memoryPressureHandler: () -> Unit = {
        // If we're not actively generating, release the backend to free memory
        if (_isLoading.value != true) {
            activeBackend?.let { backend ->
                android.util.Log.w("ChatViewModel", "Low memory: releasing ${backend.displayName}")
                // App.releaseBackendCache() handles closing — just clear local ref
                activeBackend = null
                _memoryWarning.postValue("Low memory — model unloaded to free resources. It will reload on next message.")
            }
        }
    }

    companion object {
        /** MediaPipe caps on-device generation; keep below the model's context window. */
        private const val MAX_ON_DEVICE_TOKENS = 1024
        /** Max tool-call iterations before giving up (both remote and on-device). */
        private const val MAX_REACT_ITERATIONS = 10
        /** Counter for generating unique on-device tool-call IDs. */
        private val toolCallCounter = AtomicInteger(0)
        /** Shared Gson instance — thread-safe for reading after construction. */
        private val gson = Gson()
        /** Minimum available MB of RAM required to load an on-device model. */
        private const val MIN_AVAILABLE_RAM_MB = 512
        /** Maximum conversation history entries before applying a sliding window. */
        private const val MAX_HISTORY_SIZE = 50
    }

    init {
        history.add(ChatMessage(role = MessageRole.SYSTEM, content = systemPrompt()))
        // Register for system memory-pressure callbacks
        (application as? com.th3cavalry.androidllm.App)?.addMemoryPressureListener(memoryPressureHandler)
    }

    /** Returns the user-configured system prompt, falling back to the built-in default. */
    private fun systemPrompt(): String {
        val base = Prefs.getString(getApplication(), Prefs.KEY_SYSTEM_PROMPT)
            .ifBlank { LLMService.SYSTEM_PROMPT }
        val doc = _documentContext.value
        return if (doc != null) {
            "$base\n\n--- Attached Document Context ---\n$doc"
        } else {
            base
        }
    }

    /** Sets or clears document context loaded via the document picker. */
    fun setDocumentContext(content: String?) {
        _documentContext.value = content
        // Update the system prompt in history to reflect the new context
        if (history.isNotEmpty() && history[0].role == MessageRole.SYSTEM) {
            history[0] = ChatMessage(role = MessageRole.SYSTEM, content = systemPrompt())
        }
    }

    /**
     * Sends the user's message and runs the appropriate agentic loop based on
     * the currently selected inference backend.
     */
    fun sendMessage(userText: String, imageUri: String? = null) {
        if (userText.isBlank() || _isLoading.value == true || currentJob?.isActive == true) return

        val userMsg = ChatMessage(role = MessageRole.USER, content = userText, imageUri = imageUri)
        history.add(userMsg)
        trimHistoryIfNeeded()
        _messages.value = history.filterVisible()

        _isLoading.value = true
        _error.value = null
        _memoryWarning.value = null

        val backendKey = Prefs.getString(
            getApplication(), Prefs.KEY_INFERENCE_BACKEND, Prefs.BACKEND_REMOTE
        )

        currentJob = viewModelScope.launch {
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                
                // Add error message to chat history
                val appError = e.toAppError()
                val isRetryable = appError is NetworkError || appError is TimeoutError || appError is ConnectionError
                
                history.add(ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = null,
                    errorInfo = com.th3cavalry.androidllm.data.ErrorInfo(
                        message = appError.message,
                        details = e.stackTraceToString().take(500),
                        category = appError.category,
                        isRetryable = isRetryable,
                        originalMessage = userText
                    )
                ))
                _messages.postValue(history.filterVisible())
                
                // Also set error for Snackbar (backward compatibility)
                _error.postValue("Error: ${appError.message}")
            } finally {
                _isLoading.postValue(false)
                autoSaveSession()
            }
        }
    }

    /** Cancels the currently running generation, if any. */
    fun stopGeneration() {
        val job = currentJob ?: return
        currentJob = null
        // Use invokeOnCompletion to ensure UI is only disabled after the job truly completes.
        // This prevents concurrent state mutations if the job is still running after cancel().
        job.invokeOnCompletion {
            _isLoading.postValue(false)
        }
        job.cancel()
    }

    /** Retries a failed message by resending the original user message. */
    fun retryMessage(errorMessage: ChatMessage) {
        val originalText = errorMessage.errorInfo?.originalMessage ?: return
        // Remove the error message from history
        history.removeAll { it === errorMessage }
        _messages.value = history.filterVisible()
        // Resend the original message
        sendMessage(originalText)
    }

    /** Clears the conversation (keeps the system prompt) and resets the session ID. */
    fun clearHistory() {
        history.clear()
        history.add(ChatMessage(role = MessageRole.SYSTEM, content = systemPrompt()))
        _messages.value = emptyList()
        activeSessionId = System.currentTimeMillis()
    }

    /** Saves the current chat session to persistent storage.
     * Persists the full history (including tool messages) so saved chats are independent
     * of display preferences. filterVisible() is applied only during rendering.
     */
    fun saveCurrentSession() {
        val sessionMessages = history.filter { it.role != MessageRole.SYSTEM }
        if (sessionMessages.isEmpty()) return
        val session = ChatSession(
            id = activeSessionId,
            title = sessionTitle(sessionMessages),
            timestamp = activeSessionId,
            messages = sessionMessages
        )
        viewModelScope.launch {
            chatRepo.saveSession(session)
        }
    }

    /** Loads a previously saved session into the active chat. */
    fun loadSession(session: ChatSession) {
        history.clear()
        history.add(ChatMessage(role = MessageRole.SYSTEM, content = systemPrompt()))
        history.addAll(session.messages)
        activeSessionId = session.id
        _messages.value = history.filterVisible()
    }

    /** Observable session list backed by Room. */
    val savedSessions: LiveData<List<ChatSession>> = chatRepo.observeSessions()

    /** Loads a session by ID from Room and opens it. */
    fun loadSessionById(id: Long) {
        viewModelScope.launch {
            chatRepo.getSession(id)?.let { loadSession(it) }
        }
    }

    /** Deletes a session by ID from Room. */
    fun deleteSession(id: Long) {
        viewModelScope.launch { chatRepo.deleteSession(id) }
    }

    /** Renames a session in Room. */
    fun renameSession(id: Long, title: String) {
        viewModelScope.launch { chatRepo.renameSession(id, title) }
    }

    /** Returns a session from Room for export. */
    suspend fun getSessionForExport(id: Long): ChatSession? = chatRepo.getSession(id)

    /** One-time migration: moves sessions from SharedPreferences into Room. */
    fun migrateFromPrefsIfNeeded() {
        viewModelScope.launch {
            if (chatRepo.sessionCount() > 0) return@launch
            val legacy = Prefs.getSavedSessions(getApplication())
            if (legacy.isEmpty()) return@launch
            for (session in legacy) {
                chatRepo.saveSession(session)
            }
            // Clear SharedPreferences sessions after migration
            android.util.Log.i("ChatViewModel", "Migrated ${legacy.size} sessions from SharedPreferences to Room")
        }
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
        // Check the application-level cache first (survives ViewModel recreation)
        val app = getApplication<Application>() as? com.th3cavalry.androidllm.App
        val cached = app?.cachedBackend
        if (cached is T) {
            activeBackend = cached
            return cached
        }

        val current = activeBackend
        if (current is T) return current
        current?.close()

        // Check available RAM before loading a new on-device model
        val availableMb = getAvailableMemoryMb()
        if (availableMb < MIN_AVAILABLE_RAM_MB) {
            _memoryWarning.postValue(
                "Low memory warning: only ${availableMb} MB available. " +
                "Model loading may fail on this device. Consider closing other apps."
            )
        }

        val newBackend: T = when (T::class) {
            OnDeviceInferenceService::class -> OnDeviceInferenceService(getApplication()) as T
            LiteRtLmBackend::class         -> LiteRtLmBackend(getApplication()) as T
            GeminiNanoBackend::class       -> GeminiNanoBackend(getApplication()) as T
            else -> error("Unknown backend type ${T::class.simpleName}")
        }
        activeBackend = newBackend
        // Store in the app-level cache so the backend survives ViewModel recreation
        app?.cacheBackend(newBackend)
        return newBackend
    }

    /**
     * Returns the available device RAM in MB using [ActivityManager.MemoryInfo].
     */
    private fun getAvailableMemoryMb(): Long {
        val activityManager = getApplication<Application>()
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    /**
     * Applies a sliding window to the conversation history when it exceeds
     * [MAX_HISTORY_SIZE]. Keeps the system prompt and the most recent messages.
     */
    private fun trimHistoryIfNeeded() {
        if (history.size <= MAX_HISTORY_SIZE) return
        val systemPrompt = history.firstOrNull { it.role == MessageRole.SYSTEM }
        val recentMessages = history.takeLast(MAX_HISTORY_SIZE - 1)
        history.clear()
        if (systemPrompt != null) history.add(systemPrompt)
        history.addAll(recentMessages)
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
        var completedNormally = false
        val startMs = System.currentTimeMillis()

        while (iterations < MAX_REACT_ITERATIONS) {
            iterations++

            val fullPrompt = buildOnDeviceSystemPrompt(toolDescriptions) + context
            
            // Create a placeholder streaming message
            val streamingMessage = ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                isStreaming = true
            )
            history.add(streamingMessage)
            _messages.postValue(history.filterVisible())
            
            // Collect streamed tokens and accumulate the response
            val rawResponse = StringBuilder()
            backend.generateStream(fullPrompt).collect { token ->
                rawResponse.append(token)
                // Update the streaming message with accumulated content
                val updatedMessage = streamingMessage.copy(content = rawResponse.toString())
                history[history.lastIndexOf(streamingMessage)] = updatedMessage
                _messages.postValue(history.filterVisible())
            }
            
            // Mark streaming complete and finalize the message
            val finalMessage = streamingMessage.copy(
                content = rawResponse.toString().trim(),
                isStreaming = false
            )
            history[history.lastIndexOf(streamingMessage)] = finalMessage
            _messages.postValue(history.filterVisible())
            
            val trimmedResponse = rawResponse.toString().trim()

            // Extract JSON between <tool_call> ... </tool_call> using simple string search
            // rather than regex to correctly handle nested braces in the JSON body.
            // Case-sensitive: the prompt instructs the model to use these exact lowercase tags.
            val startIdx = trimmedResponse.indexOf(toolCallStartTag)
            val endIdx = trimmedResponse.indexOf(toolCallEndTag)
            val toolCallJson = if (startIdx >= 0 && endIdx > startIdx) {
                // Only treat as tool call if the tag is at the beginning (after trimming)
                // This prevents false positives when the model includes tags in plain text responses
                if (trimmedResponse.startsWith(toolCallStartTag)) {
                    trimmedResponse.substring(startIdx + toolCallStartTag.length, endIdx).trim()
                } else {
                    null  // Tags not at start, treat as plain text
                }
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
                    // The streaming message is already in history, just update with metadata
                    val durationMs = System.currentTimeMillis() - startMs
                    val finalText = trimmedResponse.trimAssistantPrefix()
                    val updatedFinal = finalMessage.copy(
                        content = finalText,
                        responseInfo = ResponseInfo(backend.displayName, null, durationMs)
                    )
                    history[history.lastIndexOf(finalMessage)] = updatedFinal
                    _messages.postValue(history.filterVisible())
                    completedNormally = true
                    break
                }

                // Remove the streaming message since we have a tool call
                history.remove(finalMessage)
                
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

                // Show executing state
                val executingMessage = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = null,
                    executingInfo = com.th3cavalry.androidllm.data.ExecutingInfo(
                        toolName = toolName,
                        status = getToolExecutingStatus(toolName)
                    )
                )
                history.add(executingMessage)
                _messages.postValue(history.filterVisible())

                // Execute the tool
                val toolResult = runCatching {
                    executor.execute(toolName, toolArgs)
                }.getOrElse { "Error: ${it.message}" }

                // Remove executing message and show the tool result
                history.remove(executingMessage)
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
                // The streaming message is already in history, just update with metadata
                val durationMs = System.currentTimeMillis() - startMs
                val finalText = trimmedResponse.trimAssistantPrefix()
                val updatedFinal = finalMessage.copy(
                    content = finalText,
                    responseInfo = ResponseInfo(backend.displayName, null, durationMs)
                )
                history[history.lastIndexOf(finalMessage)] = updatedFinal
                _messages.postValue(history.filterVisible())
                completedNormally = true
                break
            }
        }

        if (!completedNormally) {
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

    /** Filters messages for display, respecting the hide-tool-messages preference
     * and always removing the SYSTEM prompt.
     */
    private fun List<ChatMessage>.filterVisible(): List<ChatMessage> {
        val hideTools = Prefs.getBoolean(getApplication(), Prefs.KEY_HIDE_TOOL_MESSAGES, false)
        return filter { msg ->
            when {
                msg.role == MessageRole.SYSTEM -> false
                hideTools && msg.role == MessageRole.TOOL -> false
                hideTools && msg.role == MessageRole.ASSISTANT && msg.toolCalls != null -> false
                // Never hide executing messages
                msg.executingInfo != null -> true
                else -> true
            }
        }
    }

    /** Returns a status message for the executing tool based on its type. */
    private fun getToolExecutingStatus(toolName: String): String? {
        return when {
            toolName.contains("ssh") -> "Connecting to remote server..."
            toolName.contains("github") -> "Accessing GitHub API..."
            toolName.contains("search") -> "Searching the web..."
            toolName.contains("fetch") -> "Fetching URL content..."
            toolName.contains("__") -> "Calling MCP server..."
            else -> null
        }
    }

    /** Auto-saves the session after each user turn (if the chat is non-empty). */
    private fun autoSaveSession() {
        val visibleMessages = history.filterVisible()
        if (visibleMessages.none { it.role == MessageRole.USER }) return
        val session = ChatSession(
            id = activeSessionId,
            title = sessionTitle(visibleMessages),
            timestamp = activeSessionId,
            messages = visibleMessages
        )
        Prefs.saveSession(getApplication(), session)
    }

    /** Derives a human-readable title from the first user message in a message list. */
    private fun sessionTitle(messages: List<ChatMessage>): String =
        messages.firstOrNull { it.role == MessageRole.USER }?.content?.take(60) ?: "Chat"

    override fun onCleared() {
        super.onCleared()
        // Don't close the backend here — it's cached at the App level
        // so it survives ViewModel recreation. The App will release it
        // on memory pressure or process death.
        activeBackend = null
        (getApplication<Application>() as? com.th3cavalry.androidllm.App)
            ?.removeMemoryPressureListener(memoryPressureHandler)
    }
}
