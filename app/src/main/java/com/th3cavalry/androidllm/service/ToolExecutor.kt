package com.th3cavalry.androidllm.service

import android.content.Context
import com.th3cavalry.androidllm.Prefs

/**
 * Routes tool calls (by name) to the appropriate underlying service.
 * All methods are suspend functions and run on Dispatchers.IO.
 */
class ToolExecutor(private val context: Context) {

    private val sshService = SSHService()

    private fun webSearchService(): WebSearchService {
        val provider = Prefs.getString(context, Prefs.KEY_SEARCH_PROVIDER, "duckduckgo")
        val apiKey = Prefs.getString(context, Prefs.KEY_SEARCH_API_KEY)
        return WebSearchService(provider, apiKey)
    }

    private fun githubService(): GitHubService {
        val token = Prefs.getString(context, Prefs.KEY_GITHUB_TOKEN)
        return GitHubService(token)
    }

    /**
     * Executes a tool by name with the given arguments map.
     * MCP tool calls use the format "serverName__toolName".
     */
    suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
        return when {
            toolName == "web_search" -> executeWebSearch(arguments)
            toolName == "fetch_url" -> executeFetchUrl(arguments)
            toolName == "ssh_execute" -> executeSsh(arguments)
            toolName == "github_read_file" -> executeGitHubRead(arguments)
            toolName == "github_write_file" -> executeGitHubWrite(arguments)
            toolName == "github_list_files" -> executeGitHubList(arguments)
            toolName.contains("__") -> executeMcpTool(toolName, arguments)
            else -> "Unknown tool: $toolName"
        }
    }

    // ─── Built-in tools ───────────────────────────────────────────────────────

    private suspend fun executeWebSearch(args: Map<String, Any?>): String {
        val query = args["query"]?.toString() ?: return "Missing required parameter: query"
        val num = (args["num_results"] as? Number)?.toInt() ?: 5
        return webSearchService().search(query, num)
    }

    private suspend fun executeFetchUrl(args: Map<String, Any?>): String {
        val url = args["url"]?.toString() ?: return "Missing required parameter: url"
        return webSearchService().fetchUrl(url)
    }

    private suspend fun executeSsh(args: Map<String, Any?>): String {
        val host = args["host"]?.toString() ?: return "Missing required parameter: host"
        val username = args["username"]?.toString() ?: return "Missing required parameter: username"
        val command = args["command"]?.toString() ?: return "Missing required parameter: command"
        val port = (args["port"] as? Number)?.toInt() ?: 22
        val password = args["password"]?.toString()
        val privateKey = args["private_key"]?.toString()
            ?: Prefs.getString(context, Prefs.KEY_SSH_DEFAULT_KEY).takeIf { it.isNotBlank() }

        return sshService.executeCommand(
            host = host,
            port = port,
            username = username,
            password = password,
            privateKey = privateKey,
            command = command
        )
    }

    private suspend fun executeGitHubRead(args: Map<String, Any?>): String {
        val owner = args["owner"]?.toString() ?: return "Missing required parameter: owner"
        val repo = args["repo"]?.toString() ?: return "Missing required parameter: repo"
        val path = args["path"]?.toString() ?: return "Missing required parameter: path"
        val ref = args["ref"]?.toString() ?: "main"

        return when (val result = githubService().readFile(owner, repo, path, ref)) {
            is GitHubService.GitHubFileResult.Success ->
                "SHA: ${result.sha}\n\n${result.content}"
            is GitHubService.GitHubFileResult.Error -> result.message
        }
    }

    private suspend fun executeGitHubWrite(args: Map<String, Any?>): String {
        val owner = args["owner"]?.toString() ?: return "Missing required parameter: owner"
        val repo = args["repo"]?.toString() ?: return "Missing required parameter: repo"
        val path = args["path"]?.toString() ?: return "Missing required parameter: path"
        val content = args["content"]?.toString() ?: return "Missing required parameter: content"
        val message = args["message"]?.toString() ?: return "Missing required parameter: message"
        val branch = args["branch"]?.toString() ?: "main"
        val sha = args["sha"]?.toString()

        return githubService().writeFile(owner, repo, path, content, message, branch, sha)
    }

    private suspend fun executeGitHubList(args: Map<String, Any?>): String {
        val owner = args["owner"]?.toString() ?: return "Missing required parameter: owner"
        val repo = args["repo"]?.toString() ?: return "Missing required parameter: repo"
        val path = args["path"]?.toString() ?: ""
        val ref = args["ref"]?.toString() ?: "main"

        return githubService().listFiles(owner, repo, path, ref)
    }

    // ─── MCP tools ────────────────────────────────────────────────────────────

    private suspend fun executeMcpTool(toolName: String, arguments: Map<String, Any?>): String {
        val parts = toolName.split("__", limit = 2)
        if (parts.size < 2) return "Invalid MCP tool name format: $toolName"
        val serverName = parts[0]
        val actualToolName = parts[1]

        val servers = Prefs.getMCPServers(context)
        val server = servers.find { it.name == serverName && it.enabled }
            ?: return "MCP server '$serverName' not found or disabled"

        val client = MCPClient(server)
        client.initialize()
        return client.callTool(actualToolName, arguments)
    }
}
