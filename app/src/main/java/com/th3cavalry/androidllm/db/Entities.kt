package com.th3cavalry.androidllm.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val timestamp: Long,
    val pinned: Boolean = false
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val ordinal: Int,
    val role: String,
    val content: String?,
    /** JSON blob for toolCalls, toolCallId, toolName, responseInfo, errorInfo, executingInfo */
    val extras: String? = null
)
