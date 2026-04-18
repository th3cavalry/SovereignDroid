package com.th3cavalry.androidllm.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface ChatDao {

    // ── Sessions ─────────────────────────────────────────────────────────────

    @Query("SELECT * FROM sessions ORDER BY pinned DESC, timestamp DESC")
    fun observeSessions(): LiveData<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY pinned DESC, timestamp DESC")
    suspend fun getAllSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: Long): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun renameSession(id: Long, title: String)

    // ── Messages ─────────────────────────────────────────────────────────────

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY ordinal ASC")
    suspend fun getMessages(sessionId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY ordinal ASC")
    fun observeMessages(sessionId: Long): LiveData<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun clearMessages(sessionId: Long)

    // ── Transactions ─────────────────────────────────────────────────────────

    @Transaction
    suspend fun saveSessionWithMessages(session: SessionEntity, messages: List<MessageEntity>) {
        upsertSession(session)
        clearMessages(session.id)
        insertMessages(messages)
    }

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun sessionCount(): Int
}
