package com.th3cavalry.androidllm.service

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Interacts with the GitHub REST API v3 to read and write repository files.
 */
class GitHubService(private val token: String) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://api.github.com"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ─── Read file ───────────────────────────────────────────────────────────

    suspend fun readFile(
        owner: String,
        repo: String,
        path: String,
        ref: String = "main"
    ): GitHubFileResult = withContext(Dispatchers.IO) {
        val cleanPath = path.trimStart('/')
        val url = "$baseUrl/repos/$owner/$repo/contents/$cleanPath?ref=$ref"
        try {
            val request = buildRequest(url)
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext GitHubFileResult.Error("Empty response")
            if (!response.isSuccessful) {
                return@withContext GitHubFileResult.Error("HTTP ${response.code}: $body")
            }
            val json = JSONObject(body)
            val encoding = json.optString("encoding")
            val sha = json.optString("sha")
            val contentBase64 = json.optString("content").replace("\n", "")
            val content = if (encoding == "base64") {
                Base64.decode(contentBase64, Base64.DEFAULT).toString(Charsets.UTF_8)
            } else {
                contentBase64
            }
            GitHubFileResult.Success(content, sha)
        } catch (e: Exception) {
            GitHubFileResult.Error("Error reading file: ${e.message}")
        }
    }

    // ─── Write / update file ─────────────────────────────────────────────────

    suspend fun writeFile(
        owner: String,
        repo: String,
        path: String,
        content: String,
        commitMessage: String,
        branch: String = "main",
        sha: String? = null          // Required when updating an existing file
    ): String = withContext(Dispatchers.IO) {
        val cleanPath = path.trimStart('/')
        val url = "$baseUrl/repos/$owner/$repo/contents/$cleanPath"

        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val payload = JSONObject().apply {
            put("message", commitMessage)
            put("content", encoded)
            put("branch", branch)
            if (!sha.isNullOrBlank()) put("sha", sha)
        }

        return@withContext try {
            val body = payload.toString().toRequestBody(jsonMedia)
            val request = Request.Builder()
                .url(url)
                .put(body)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                "File written successfully to $owner/$repo/$cleanPath on branch $branch"
            } else {
                "Error writing file: HTTP ${response.code}: $responseBody"
            }
        } catch (e: Exception) {
            "Error writing file: ${e.message}"
        }
    }

    // ─── List directory ──────────────────────────────────────────────────────

    suspend fun listFiles(
        owner: String,
        repo: String,
        path: String = "",
        ref: String = "main"
    ): String = withContext(Dispatchers.IO) {
        val cleanPath = path.trimStart('/')
        val urlPath = if (cleanPath.isEmpty()) "" else "/$cleanPath"
        val url = "$baseUrl/repos/$owner/$repo/contents$urlPath?ref=$ref"
        try {
            val request = buildRequest(url)
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext "Empty response"
            if (!response.isSuccessful) return@withContext "HTTP ${response.code}: $body"

            val sb = StringBuilder("Files in $owner/$repo/${cleanPath.ifEmpty { "/" }}:\n")
            // Parse JSON array
            val trimmed = body.trim()
            if (!trimmed.startsWith("[")) return@withContext body

            val itemPattern = Regex(""""name"\s*:\s*"([^"]+)".*?"type"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            var found = false
            for (match in itemPattern.findAll(body)) {
                found = true
                val name = match.groupValues[1]
                val type = match.groupValues[2]
                val icon = if (type == "dir") "📁" else "📄"
                sb.append("$icon $name\n")
            }
            if (!found) sb.append("(empty directory)")
            sb.toString()
        } catch (e: Exception) {
            "Error listing files: ${e.message}"
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun buildRequest(url: String): Request = Request.Builder()
        .url(url)
        .apply {
            if (token.isNotBlank()) header("Authorization", "Bearer $token")
        }
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .build()

    sealed class GitHubFileResult {
        data class Success(val content: String, val sha: String) : GitHubFileResult()
        data class Error(val message: String) : GitHubFileResult()
    }
}
