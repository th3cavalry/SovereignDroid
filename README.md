# SovereignDroid

**Your AI, Your Rules** — An Android app that runs LLMs with complete privacy and control.

An Android app that runs an LLM (Large Language Model) with agentic capabilities — it can search the web, execute SSH commands, interact with GitHub repositories, and connect to MCP (Model Context Protocol) servers.

## Features

- 📱 **On-Device Inference** — Run LLMs locally on your Android device using:
  - [MediaPipe LLM Inference API](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android) — Gemma 2B/7B, Phi-2, Falcon 1B, and more (`.task` files)
  - [LiteRT-LM](https://ai.google.dev/edge/litert) — Efficient inference engine using `.litertlm` model files
  - [Gemini Nano](https://ai.google.dev/gemini-api/docs/models/gemini-nano) — On-device Gemini Nano via Android AICore (Pixel 9+ / API 35+)
- 💬 **Remote LLM Chat** — Connect to any OpenAI-compatible API endpoint (Ollama, LM Studio, OpenAI, etc.)
- 🔧 **Tool Calling** — Full agentic ReAct loop: the LLM decides when to use tools and processes results automatically
- 🌐 **Web Search** — DuckDuckGo (free, no key needed), Brave Search, or SerpAPI
- 🔗 **URL Fetching** — Fetches and reads web pages via [Jina Reader](https://r.jina.ai)
- 🖥️ **SSH Terminal** — Execute commands on remote servers via SSH (password or key auth)
- 🐙 **GitHub Integration** — Read files, write/update files, and list directories in any GitHub repo
- 🔌 **MCP Servers** — Connect to external [Model Context Protocol](https://modelcontextprotocol.io) servers to add more tools
- 🔒 **Keystore-Backed Secret Storage** — All API keys and credentials are encrypted using Android Keystore

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
2. Open in Android Studio, or build on the command line:
   ```bash
   # Requires Java 17 (Java 26+ not supported by AGP)
   export JAVA_HOME=/usr/lib/jvm/java-17-openjdk   # Linux
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Configuration (in-app Settings)

#### Inference Backend

Select your preferred backend in Settings:

| Backend | Model Format | Requirements |
|---|---|---|
| **Remote API** | Any (via endpoint) | OpenAI-compatible API server |
| **MediaPipe** | `.task` | Android 10+, 4 GB+ RAM |
| **LiteRT-LM** | `.litertlm` | Android 8+, 4 GB+ RAM |
| **Ollama Local** | Any (via Ollama) | Ollama running on device or LAN |
| **Gemini Nano** | System model | Pixel 9+ / Android 15 (API 35+) |

#### On-Device Inference — MediaPipe

**Supported models (download `.task` files from Kaggle):**

| Model | Size | Recommended for |
|---|---|---|
| [Gemma 2B-IT (INT4)](https://www.kaggle.com/models/google/gemma/frameworks/tfLite/variations/gemma-2b-it-gpu-int4) | ~1.4 GB | Best quality/size balance |
| Gemma 7B-IT | ~4 GB | Best quality (needs 8 GB+ RAM) |
| Phi-2 | ~1.6 GB | Good English reasoning |
| Falcon 1B | ~1 GB | Fastest inference |

#### On-Device Inference — LiteRT-LM

Download `.litertlm` files via the built-in **Model Browser** (Settings → Model Browser), which pulls from the [litert-community](https://huggingface.co/litert-community) collection on Hugging Face. An optional HF token unlocks gated models.

#### Remote API Settings

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

> **Security note:** All API keys, tokens, and SSH private keys are encrypted using Android Keystore and stored in `EncryptedSharedPreferences`. They are never logged or backed up.

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
├── MainActivity.kt               # Chat UI
├── SettingsActivity.kt           # Configuration
├── TerminalActivity.kt           # Interactive SSH terminal
├── MCPManagerActivity.kt         # MCP server management
├── ModelBrowserActivity.kt       # HF model browser & download
├── Prefs.kt                      # SharedPreferences + EncryptedSharedPreferences
├── data/
│   ├── ChatMessage.kt            # Chat message model
│   └── MCPServer.kt              # MCP server config
├── network/
│   ├── LLMApi.kt                 # Retrofit API interface
│   ├── RetrofitClient.kt         # HTTP client builder
│   ├── HfApiService.kt           # Hugging Face Hub REST API
│   └── dto/ChatDto.kt            # Request/response DTOs
├── service/
│   ├── InferenceBackend.kt       # Common interface for all backends
│   ├── LLMService.kt             # Remote LLM + agentic tool loop (function calling)
│   ├── OnDeviceInferenceService.kt  # MediaPipe on-device inference + ReAct loop
│   ├── LiteRtLmBackend.kt        # LiteRT-LM inference backend
│   ├── GeminiNanoBackend.kt      # Gemini Nano (Android AICore)
│   ├── WebSearchService.kt       # Web search + URL fetching
│   ├── SSHService.kt             # SSH via JSch
│   ├── GitHubService.kt          # GitHub REST API
│   ├── MCPClient.kt              # MCP protocol client
│   └── ToolExecutor.kt           # Routes tool calls to services
├── ui/
│   └── ChatAdapter.kt            # RecyclerView chat adapter
└── viewmodel/
    ├── ChatViewModel.kt          # Chat state & agentic loop orchestration
    └── ModelBrowserViewModel.kt  # Model browser state & downloads
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

## Security

- **Encrypted credential storage** — API keys, tokens, and SSH keys are stored in `EncryptedSharedPreferences` backed by Android Keystore (AES256-GCM). Never stored in plaintext.
- **HTTPS-only networking** — Cleartext HTTP is disabled globally. Local network access (`localhost`, `10.x`, `192.168.x`, `172.16–31.x`) is the only plain-HTTP exception (useful for Ollama/LM Studio on your LAN).
- **SSH host verification** — Strict host key checking is enforced; unknown hosts prompt for confirmation before connecting.
- **No credential logging** — HTTP logging is limited to headers in debug builds and fully disabled in release builds. Request/response bodies (including prompts) are never written to logcat.
- **Backup exclusion** — Encrypted credentials are excluded from Google Cloud Backup and device-to-device transfers.

## Dependencies

| Library | Purpose |
|---|---|
| MediaPipe `tasks-genai` | On-device LLM inference (MediaPipe backend) |
| LiteRT-LM `litertlm-android` | On-device LLM inference (LiteRT backend) |
| Android AICore | Gemini Nano on-device inference (Pixel 9+ / API 35+) |
| Retrofit2 + OkHttp3 | LLM API and HTTP requests |
| Gson | JSON serialization |
| JSch (mwiede fork) | SSH client |
| Markwon | Markdown rendering in chat |
| AndroidX Security Crypto | Keystore-backed EncryptedSharedPreferences |
| AndroidX Lifecycle | ViewModel + LiveData |
| Kotlin Coroutines | Async operations |
| Material Design 3 | UI components |

## License

MIT
