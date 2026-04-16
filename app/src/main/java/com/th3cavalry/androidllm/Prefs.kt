package com.th3cavalry.androidllm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.th3cavalry.androidllm.data.ChatSession
import com.th3cavalry.androidllm.data.MCPServer

object Prefs {

    private const val PREF_NAME = "androidllm_prefs"
    private const val ENCRYPTED_PREF_NAME = "androidllm_secure_prefs"

    // LLM settings
    const val KEY_LLM_ENDPOINT = "llm_endpoint"
    const val KEY_LLM_API_KEY = "llm_api_key"
    const val KEY_LLM_MODEL = "llm_model"
    const val KEY_LLM_MAX_TOKENS = "llm_max_tokens"
    const val KEY_LLM_TEMPERATURE = "llm_temperature"

    // Web search
    const val KEY_SEARCH_PROVIDER = "search_provider"
    const val KEY_SEARCH_API_KEY = "search_api_key"

    // GitHub
    const val KEY_GITHUB_TOKEN = "github_token"

    // SSH defaults
    const val KEY_SSH_DEFAULT_HOST = "ssh_default_host"
    const val KEY_SSH_DEFAULT_USER = "ssh_default_user"
    const val KEY_SSH_DEFAULT_KEY = "ssh_default_key"

    // MCP servers
    const val KEY_MCP_SERVERS = "mcp_servers"

    // UI toggles
    /** When true, tool-call and tool-result messages are hidden from the chat UI. */
    const val KEY_HIDE_TOOL_MESSAGES = "hide_tool_messages"
    /** When true, show model/tokens/time metadata below each assistant response. */
    const val KEY_SHOW_RESPONSE_INFO = "show_response_info"
    /**
     * Color theme index: 0 = Purple (default), 1 = Blue, 2 = Green, 3 = Orange.
     * Applied via [com.th3cavalry.androidllm.ThemeHelper].
     */
    const val KEY_COLOR_THEME = "color_theme"

    // Inference backend selection
    // Replaces the old binary KEY_ON_DEVICE_ENABLED toggle.
    const val KEY_INFERENCE_BACKEND = "inference_backend"

    /** Inference backend IDs stored in [KEY_INFERENCE_BACKEND]. */
    const val BACKEND_REMOTE       = "remote"        // OpenAI-compatible remote API (default)
    const val BACKEND_MEDIAPIPE    = "mediapipe"     // MediaPipe LLM Inference — .task files
    const val BACKEND_LITERT_LM    = "litert_lm"    // Google LiteRT-LM — .litertlm files
    const val BACKEND_OLLAMA_LOCAL = "ollama_local"  // Ollama running in Termux at localhost:11434
    const val BACKEND_GEMINI_NANO  = "gemini_nano"   // Google Gemini Nano via AI Edge SDK (Pixel 9+)

    // Model file paths for each local backend (kept separate so users can switch without re-picking)
    const val KEY_ON_DEVICE_MODEL_PATH  = "on_device_model_path"   // MediaPipe .task path
    const val KEY_LITERT_LM_MODEL_PATH  = "litert_lm_model_path"  // LiteRT-LM .litertlm path

    // Hugging Face personal access token — used by the Model Browser for gated model downloads
    const val KEY_HF_TOKEN = "hf_token"

    // Legacy key kept for migration; use KEY_INFERENCE_BACKEND for new code.
    @Deprecated("Use KEY_INFERENCE_BACKEND instead", ReplaceWith("KEY_INFERENCE_BACKEND"))
    const val KEY_ON_DEVICE_ENABLED = "on_device_enabled"

    // System prompt
    const val KEY_SYSTEM_PROMPT = "system_prompt"

    // Chat history
    private const val KEY_CHAT_SESSIONS = "chat_sessions"
    /** Maximum number of saved chat sessions to keep. Oldest are dropped when exceeded. */
    private const val MAX_SAVED_SESSIONS = 50
    const val DEFAULT_ENDPOINT = "http://localhost:11434/v1"
    const val DEFAULT_MODEL = "llama3.2"
    const val DEFAULT_MAX_TOKENS = 4096
    const val DEFAULT_TEMPERATURE = 0.7f

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Get encrypted SharedPreferences for storing secrets (API keys, tokens, SSH keys).
     * These are backed by Android Keystore and NOT included in backups.
     */
    private fun encryptedPrefs(context: Context): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse { e ->
        // Fallback: if encryption setup fails (rare), log and use regular prefs
        android.util.Log.e("Prefs", "Failed to initialize encrypted prefs", e)
        prefs(context)
    }

    fun getString(context: Context, key: String, default: String = ""): String =
        prefs(context).getString(key, default) ?: default

    fun putString(context: Context, key: String, value: String) =
        prefs(context).edit().putString(key, value).apply()

    fun getInt(context: Context, key: String, default: Int): Int =
        prefs(context).getInt(key, default)

    fun putInt(context: Context, key: String, value: Int) =
        prefs(context).edit().putInt(key, value).apply()

    fun getFloat(context: Context, key: String, default: Float): Float =
        prefs(context).getFloat(key, default)

    fun putFloat(context: Context, key: String, value: Float) =
        prefs(context).edit().putFloat(key, value).apply()

    fun getBoolean(context: Context, key: String, default: Boolean = false): Boolean =
        prefs(context).getBoolean(key, default)

    fun putBoolean(context: Context, key: String, value: Boolean) =
        prefs(context).edit().putBoolean(key, value).apply()

    // ─── Encrypted Storage Helpers (for secrets) ────────────────────────────────────

    /**
     * Retrieve a secret (API key, token, etc.) from encrypted storage.
     * These are backed by Android Keystore and NOT backed up.
     */
    fun getSecret(context: Context, key: String, default: String = ""): String =
        encryptedPrefs(context).getString(key, default) ?: default

    /**
     * Store a secret (API key, token, etc.) in encrypted storage.
     * These are backed by Android Keystore and NOT backed up.
     */
    fun putSecret(context: Context, key: String, value: String) =
        encryptedPrefs(context).edit().putString(key, value).apply()

    /**
     * Clear all secrets (useful for logout or account switching).
     */
    fun clearSecrets(context: Context) {
        encryptedPrefs(context).edit().clear().apply()
    }

    /**
     * Migrate plaintext secrets from unencrypted SharedPreferences to encrypted storage.
     * Call this once during app initialization to move existing secrets to Keystore-backed storage.
     * This is a one-time migration and can be safely called repeatedly.
     */
    fun migrateSecretsToEncrypted(context: Context) {
        val secretKeys = listOf(
            KEY_LLM_API_KEY,
            KEY_SEARCH_API_KEY,
            KEY_GITHUB_TOKEN,
            KEY_SSH_DEFAULT_KEY,
            KEY_HF_TOKEN
        )

        secretKeys.forEach { key ->
            val plaintext = prefs(context).getString(key, null)
            if (plaintext != null) {
                // Move to encrypted storage
                putSecret(context, key, plaintext)
                // Remove from unencrypted storage
                prefs(context).edit().remove(key).apply()
            }
        }
    }

    fun getMCPServers(context: Context): List<MCPServer> {
        val json = getString(context, KEY_MCP_SERVERS)
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<MCPServer>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveMCPServers(context: Context, servers: List<MCPServer>) {
        putString(context, KEY_MCP_SERVERS, Gson().toJson(servers))
    }

    // ─── Chat session persistence ─────────────────────────────────────────────

    fun getSavedSessions(context: Context): List<ChatSession> {
        val json = getString(context, KEY_CHAT_SESSIONS)
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<ChatSession>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSession(context: Context, session: ChatSession) {
        val sessions = getSavedSessions(context).toMutableList()
        // Replace existing session with the same id or prepend a new one
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx >= 0) sessions[idx] = session else sessions.add(0, session)
        // Trim to cap
        val trimmed = if (sessions.size > MAX_SAVED_SESSIONS) {
            sessions.take(MAX_SAVED_SESSIONS)
        } else sessions
        putString(context, KEY_CHAT_SESSIONS, Gson().toJson(trimmed))
    }

    fun deleteSession(context: Context, sessionId: Long) {
        val sessions = getSavedSessions(context).filter { it.id != sessionId }
        putString(context, KEY_CHAT_SESSIONS, Gson().toJson(sessions))
    }

    fun renameSession(context: Context, sessionId: Long, newTitle: String) {
        val sessions = getSavedSessions(context).map { session ->
            if (session.id == sessionId) session.copy(title = newTitle) else session
        }
        putString(context, KEY_CHAT_SESSIONS, Gson().toJson(sessions))
    }

    // ─── Remote model cache ───────────────────────────────────────────────────

    private const val KEY_REMOTE_MODEL_IDS = "remote_model_ids"

    /** Returns the cached list of model IDs fetched from the remote endpoint. */
    fun getRemoteModelIds(context: Context): List<String> {
        val json = getString(context, KEY_REMOTE_MODEL_IDS)
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRemoteModelIds(context: Context, ids: List<String>) {
        putString(context, KEY_REMOTE_MODEL_IDS, Gson().toJson(ids))
    }
}
