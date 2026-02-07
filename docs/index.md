# Fraggle

Fraggle is a Kotlin-based AI assistant framework that integrates with messaging platforms. Built on [Koog](https://github.com/JetBrains/koog) for agent orchestration, it connects to local or cloud LLM providers and provides extensible tools for tasks like file operations, web fetching, and command execution.

## Key Features

<div class="grid cards" markdown>

- :material-chat-processing: **Chat Bridge Integration**
  Connect to messaging platforms like Signal and Discord, with support for direct conversations.

- :material-robot: **Koog Agent**
  ReAct-style reasoning and acting loop powered by Koog for reliable tool use with any compatible LLM.

- :material-puzzle: **Extensible Tools**
  Type-safe tool definitions using Koog's `SimpleTool` framework make it easy to add custom capabilities.

- :material-memory: **Persistent Memory**
  Hierarchical memory system with global, chat, and user scopes.

- :material-shield-check: **Sandboxed Execution**
  File and shell operations run in a configurable sandbox environment.

- :material-server: **Multiple LLM Providers**
  Works with LM Studio, OpenAI, and Anthropic APIs.

</div>

## Quick Start

```bash
# Download latest release
wget https://github.com/DrewCarlson/Fraggle/releases/latest/download/fraggle.zip
unzip fraggle.zip
cd fraggle

# Run the configuration wizard
./bin/fraggle configure

# Start chatting
./bin/fraggle chat
```

See the [Getting Started](installation/getting-started.md) guide for full setup instructions.

## Project Structure

```
Fraggle/
├── fraggle-cli/            # CLI application entry point
├── fraggle-agent/          # Core agent framework (Koog integration)
├── fraggle-signal/         # Signal messaging bridge
├── fraggle-discord/        # Discord bot bridge
├── fraggle-tools/          # Built-in tool implementations
├── fraggle-api/            # REST API server
├── fraggle-dashboard/      # Web dashboard (Compose for Web)
├── fraggle-common/         # Shared models (Kotlin Multiplatform)
└── libs/                   # Internal libraries
    └── documented-*        # Annotation processor for config docs
```

## How It Works

1. **Message Received** - A message arrives via a chat bridge (or interactive mode)
2. **Agent Processing** - Koog's agent service evaluates the message and available tools
3. **Tool Execution** - The agent calls tools as needed (file ops, web fetch, etc.)
4. **Response Sent** - The final response is delivered back to the user

The agent continues reasoning and acting until it produces a final response or reaches the iteration limit.

## License

Fraggle is published under the [MIT License](https://github.com/DrewCarlson/Fraggle/blob/main/LICENSE).
