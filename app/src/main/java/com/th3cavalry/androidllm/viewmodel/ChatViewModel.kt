package com.th3cavalry.androidllm.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.th3cavalry.androidllm.Prefs
import com.th3cavalry.androidllm.data.ChatMessage
import com.th3cavalry.androidllm.data.MCPServer
import com.th3cavalry.androidllm.data.MessageRole
import com.th3cavalry.androidllm.network.dto.ToolDto
import com.th3cavalry.androidllm.service.LLMService
import com.th3cavalry.androidllm.service.MCPClient
import kotlinx.coroutines.launch

/**
 * ViewModel that manages the chat session and the agentic tool-calling loop.
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

    init {
        // Add the system prompt to history but don't show it in UI
        history.add(ChatMessage(role = MessageRole.SYSTEM, content = LLMService.SYSTEM_PROMPT))
    }

    /**
     * Sends the user's message and runs the agentic loop.
     */
    fun sendMessage(userText: String) {
        if (userText.isBlank() || _isLoading.value == true) return

        val userMsg = ChatMessage(role = MessageRole.USER, content = userText)
        history.add(userMsg)
        _messages.value = history.filterVisible()

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val tools = buildToolsList()

                llmService.chat(
                    history = history,
                    tools = tools,
                    onProgress = { updatedHistory ->
                        _messages.postValue(updatedHistory.filterVisible())
                    }
                )

                _messages.postValue(history.filterVisible())
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

    // ─── Helpers ──────────────────────────────────────────────────────────────

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

    /**
     * Filters out system messages for display; shows user, assistant, and tool messages.
     */
    private fun List<ChatMessage>.filterVisible(): List<ChatMessage> =
        filter { it.role != MessageRole.SYSTEM }
}
