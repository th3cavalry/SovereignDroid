package com.th3cavalry.androidllm

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.th3cavalry.androidllm.data.MCPServer

object Prefs {

    private const val PREF_NAME = "androidllm_prefs"

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

    // Defaults
    const val DEFAULT_ENDPOINT = "http://localhost:11434/v1"
    const val DEFAULT_MODEL = "llama3.2"
    const val DEFAULT_MAX_TOKENS = 4096
    const val DEFAULT_TEMPERATURE = 0.7f

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

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
}
