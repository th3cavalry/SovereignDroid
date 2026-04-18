# Privacy Policy — SovereignDroid

**Last updated:** 2025-01-01

SovereignDroid ("the App") is a free, open-source Android application that provides a
chat interface for large language models (LLMs). Your privacy matters to us.

## Data Collection

The App does **not** collect, transmit, or store any personal data on external servers
controlled by the developer. Specifically:

- **No analytics or telemetry.** The App contains no tracking SDKs, analytics frameworks,
  or crash-reporting services.
- **No advertising.** The App contains no ad networks.
- **No accounts.** The App does not require or create user accounts.

## Data Processed on Your Device

- **Chat history** is stored locally on your device in a Room database. It never leaves
  your device unless you explicitly export it.
- **Settings and API keys** are stored in encrypted SharedPreferences on your device.

## Data Sent to Third Parties

When you configure a cloud LLM endpoint (e.g., OpenAI, Anthropic, a self-hosted server),
the App sends your chat messages to **only** the endpoint you specify. The App has no
default cloud backend — no data is sent anywhere unless you configure one.

If you enable **Web Search** or **MCP servers**, those services will receive queries
according to their own privacy policies.

## On-Device Inference

When using on-device models (MediaPipe, LiteRT, Gemini Nano), all inference happens
locally. No data leaves your device.

## Permissions

| Permission | Purpose |
|---|---|
| INTERNET | Connect to user-configured LLM endpoints, web search, MCP servers |
| ACCESS_NETWORK_STATE | Check connectivity before making API calls |
| READ_EXTERNAL_STORAGE (API ≤ 32) | Access legacy model files in public storage |
| WRITE_EXTERNAL_STORAGE (API ≤ 28) | DownloadManager fallback for older devices |

## Children's Privacy

The App is not directed at children under 13 and does not knowingly collect data from them.

## Changes

We may update this policy. Changes will be reflected in the "Last updated" date above
and in the app's repository.

## Contact

For questions about this policy, open an issue at:
https://github.com/th3cavalry/Android-llm/issues
