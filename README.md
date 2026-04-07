# Android LLM

An Android app that runs an LLM (Large Language Model) with agentic capabilities — it can search the web, execute SSH commands, interact with GitHub repositories, and connect to MCP (Model Context Protocol) servers.

## Features

- 💬 **LLM Chat** — Connect to any OpenAI-compatible API endpoint (Ollama, LM Studio, OpenAI, etc.)
- 🔧 **Tool Calling** — Full agentic loop: the LLM decides when to use tools and processes results automatically
- 🌐 **Web Search** — DuckDuckGo (free, no key needed), Brave Search, or SerpAPI
- 🔗 **URL Fetching** — Fetches and reads web pages via [Jina Reader](https://r.jina.ai)
- 🖥️ **SSH Terminal** — Execute commands on remote servers via SSH (password or key auth)
- 🐙 **GitHub Integration** — Read files, write/update files, and list directories in any GitHub repo
- 🔌 **MCP Servers** — Connect to external [Model Context Protocol](https://modelcontextprotocol.io) servers to add more tools

## Setup

### Requirements
- Android 8.0 (API 26) or later
- A running LLM API endpoint:
  - **[Ollama](https://ollama.ai)** (recommended for local) — default endpoint is `http://<your-ip>:11434/v1`
  - **[LM Studio](https://lmstudio.ai)** — use its local server endpoint
  - **OpenAI** — use `https://api.openai.com/v1` with your API key
  - Any other OpenAI-compatible API

### Building from Source

1. Clone this repository
2. Open in Android Studio (or build via command line)
3. Build and run:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Configuration (in-app Settings)

| Setting | Description |
|---|---|
| API Endpoint | Your LLM base URL (e.g. `http://192.168.1.100:11434/v1`) |
| API Key | Leave blank for Ollama; required for OpenAI |
| Model | Model name (e.g. `llama3.2`, `gpt-4o`, `mistral`) |
| Max Tokens | Max tokens per response (default: 4096) |
| Temperature | Creativity/randomness 0.0–2.0 (default: 0.7) |
| Search Provider | DuckDuckGo (free), Brave Search, or SerpAPI |
| Search API Key | Required for Brave or SerpAPI |
| GitHub Token | Personal Access Token for GitHub file operations |
| SSH Defaults | Default host, username, and PEM private key |

## Usage Examples

### Chat & Web Search
> "What's the latest news about the Llama model family?"

The LLM will automatically call `web_search` to find current information.

### SSH System Administration
> "SSH into 192.168.1.100 as ubuntu and check current CPU and memory usage"

The LLM calls `ssh_execute` with the appropriate commands.

### GitHub Operations
> "Read the README from torvalds/linux and summarize the build instructions"

The LLM calls `github_read_file` to fetch and summarize the file.

> "In my-org/my-repo, create a new file docs/API.md with this content: ..."

The LLM calls `github_write_file` to create the file.

### MCP Servers
Add any HTTP-based MCP server in the **MCP Servers** screen. The LLM will discover its tools automatically and use them when appropriate.

## Architecture

```
app/src/main/java/com/th3cavalry/androidllm/
├── MainActivity.kt           # Chat UI
├── SettingsActivity.kt       # Configuration
├── TerminalActivity.kt       # Interactive SSH terminal
├── MCPManagerActivity.kt     # MCP server management
├── Prefs.kt                  # SharedPreferences helper
├── data/
│   ├── ChatMessage.kt        # Chat message model
│   └── MCPServer.kt          # MCP server config
├── network/
│   ├── LLMApi.kt             # Retrofit API interface
│   ├── RetrofitClient.kt     # HTTP client builder
│   └── dto/ChatDto.kt        # Request/response DTOs
├── service/
│   ├── LLMService.kt         # LLM + agentic tool loop
│   ├── WebSearchService.kt   # Web search + URL fetching
│   ├── SSHService.kt         # SSH via JSch
│   ├── GitHubService.kt      # GitHub REST API
│   ├── MCPClient.kt          # MCP protocol client
│   └── ToolExecutor.kt       # Routes tool calls
├── ui/
│   └── ChatAdapter.kt        # RecyclerView chat adapter
└── viewmodel/
    └── ChatViewModel.kt      # Chat state & agentic loop
```

## MCP Server Support

Connect to any HTTP-based MCP server that implements the [MCP Streamable HTTP transport](https://spec.modelcontextprotocol.io/specification/basic/transports/). The app will:
1. Initialize a session with the server
2. Discover all available tools
3. Namespace them as `ServerName__tool_name`
4. Automatically use them when the LLM decides

Example MCP servers to try:
- [Filesystem MCP](https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem)
- [GitHub MCP](https://github.com/modelcontextprotocol/servers/tree/main/src/github)
- [Brave Search MCP](https://github.com/modelcontextprotocol/servers/tree/main/src/brave-search)

## Dependencies

| Library | Purpose |
|---|---|
| Retrofit2 + OkHttp3 | LLM API and HTTP requests |
| Gson | JSON serialization |
| JSch (mwiede fork) | SSH client |
| Markwon | Markdown rendering in chat |
| AndroidX Lifecycle | ViewModel + LiveData |
| Kotlin Coroutines | Async operations |
| Material Design 3 | UI components |

## License

MIT
