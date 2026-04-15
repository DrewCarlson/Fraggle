# Fraggle

Fraggle is a Kotlin-based AI assistant framework that integrates with messaging platforms. It runs its own ReAct-style agent loop against local or cloud LLM providers and ships with extensible tools for tasks like file operations, web fetching, and command execution. It also ships a [terminal coding agent](coding-agent.md), `fraggle code`, that reuses the same agent loop, tools, and compaction infrastructure for in-project pair-programming.

## Key Features

<div class="grid cards" markdown>

- :material-chat-processing: **Chat Bridge Integration**
  Connect to messaging platforms like Signal and Discord, with support for direct conversations.

- :material-console: **Terminal Coding Agent**
  `fraggle code` is a Mosaic-based TUI for pair-programming with an LLM in any project — JSONL session branching, `AGENTS.md` context files, Claude-Code-style edits.

- :material-robot: **ReAct Agent Loop**
  Purpose-built reasoning and acting loop with streaming, tool dispatch, and iteration control for any OpenAI-compatible LLM.

- :material-puzzle: **Extensible Tools**
  Type-safe `AgentToolDef` definitions backed by `kotlinx.serialization` make it easy to add custom capabilities.

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

# Or pair-program with the terminal coding agent
./bin/fraggle code
```

See the [Getting Started](installation/getting-started.md) guide for full setup instructions.

## Project Structure

```
Fraggle/
├── fraggle-cli/            # CLI application (run / chat / code / configure / worker)
├── fraggle-assistant/      # Messenger assistant (FraggleAgent, memory, chat bridges, db)
├── fraggle-coding-agent/   # Terminal coding agent (TUI, sessions, coding tools)
├── fraggle-agent-core/     # Generic agent runtime (loop, tools, executor, tracing, compaction)
├── fraggle-llm/            # LLM provider (LMStudioProvider, ChatRequest/Response)
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
2. **Agent Processing** - The agent loop evaluates the message and available tools
3. **Tool Execution** - The agent calls tools as needed (file ops, web fetch, etc.)
4. **Response Sent** - The final response is delivered back to the user

The agent continues reasoning and acting until it produces a final response or reaches the iteration limit.

## License

Fraggle is published under the [MIT License](https://github.com/DrewCarlson/Fraggle/blob/main/LICENSE).
