package com.th3cavalry.androidllm.db

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.th3cavalry.androidllm.data.ChatMessage
import com.th3cavalry.androidllm.data.ChatSession
import com.th3cavalry.androidllm.data.ErrorInfo
import com.th3cavalry.androidllm.data.ExecutingInfo
import com.th3cavalry.androidllm.data.MessageRole
import com.th3cavalry.androidllm.data.ResponseInfo
import com.th3cavalry.androidllm.data.ToolCallData

/**
 * Repository for chat session persistence backed by Room.
 * Converts between domain models ([ChatSession]/[ChatMessage]) and Room entities.
 */
class ChatRepository(context: Context) {

    private val dao = AppDatabase.get(context).chatDao()
    private val gson = Gson()

    // ── Observable ───────────────────────────────────────────────────────────

    fun observeSessions(): LiveData<List<ChatSession>> =
        dao.observeSessions().map { entities -> entities.map { it.toDomain(emptyList()) } }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    suspend fun getAllSessions(): List<ChatSession> =
        dao.getAllSessions().map { session ->
            val messages = dao.getMessages(session.id).map { it.toDomain() }
            session.toDomain(messages)
        }

    suspend fun getSession(id: Long): ChatSession? {
        val session = dao.getSession(id) ?: return null
        val messages = dao.getMessages(id).map { it.toDomain() }
        return session.toDomain(messages)
    }

    suspend fun saveSession(session: ChatSession) {
        val sessionEntity = SessionEntity(
            id = session.id,
            title = session.title,
            timestamp = session.timestamp
        )
        val messageEntities = session.messages.mapIndexed { index, msg ->
            msg.toEntity(session.id, index)
        }
        dao.saveSessionWithMessages(sessionEntity, messageEntities)
    }

    suspend fun deleteSession(id: Long) = dao.deleteSession(id)

    suspend fun renameSession(id: Long, title: String) = dao.renameSession(id, title)

    suspend fun sessionCount(): Int = dao.sessionCount()

    // ── Mapping: Entity → Domain ─────────────────────────────────────────────

    private fun SessionEntity.toDomain(messages: List<ChatMessage>) =
        ChatSession(id = id, title = title, timestamp = timestamp, messages = messages)

    private fun MessageEntity.toDomain(): ChatMessage {
        val ext = extras?.let { parseExtras(it) }
        return ChatMessage(
            role = MessageRole.entries.first { it.value == role },
            content = content,
            toolCalls = ext?.toolCalls,
            toolCallId = ext?.toolCallId,
            toolName = ext?.toolName,
            responseInfo = ext?.responseInfo,
            errorInfo = ext?.errorInfo,
            executingInfo = ext?.executingInfo,
            isStreaming = false,
            imageUri = ext?.imageUri
        )
    }

    // ── Mapping: Domain → Entity ─────────────────────────────────────────────

    private fun ChatMessage.toEntity(sessionId: Long, ordinal: Int) = MessageEntity(
        sessionId = sessionId,
        ordinal = ordinal,
        role = role.value,
        content = content,
        extras = buildExtras()
    )

    private fun ChatMessage.buildExtras(): String? {
        val ext = MessageExtras(
            toolCalls = toolCalls,
            toolCallId = toolCallId,
            toolName = toolName,
            responseInfo = responseInfo,
            errorInfo = errorInfo,
            executingInfo = executingInfo,
            imageUri = imageUri
        )
        // Only serialize if there's anything beyond defaults
        return if (ext.hasData()) gson.toJson(ext) else null
    }

    private fun parseExtras(json: String): MessageExtras =
        gson.fromJson(json, MessageExtras::class.java)

    /** Internal DTO for the JSON extras column. */
    private data class MessageExtras(
        val toolCalls: List<ToolCallData>? = null,
        val toolCallId: String? = null,
        val toolName: String? = null,
        val responseInfo: ResponseInfo? = null,
        val errorInfo: ErrorInfo? = null,
        val executingInfo: ExecutingInfo? = null,
        val imageUri: String? = null
    ) {
        fun hasData() = toolCalls != null || toolCallId != null || toolName != null
                || responseInfo != null || errorInfo != null || executingInfo != null
                || imageUri != null
    }
}
