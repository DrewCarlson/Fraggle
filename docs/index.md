# Fraggle

Fraggle is a Kotlin-based AI assistant framework that integrates with messaging platforms. It connects to local or cloud LLM providers and provides an extensible skill system for tasks like file operations, web fetching, and command execution.

## Key Features

<div class="grid cards" markdown>

- :material-chat-processing: **Chat Bridge Integration**
  Connect to messaging platforms like Signal, with support for both direct and group conversations.

- :material-robot: **ReAct Agent Loop**
  Iterative reasoning and acting pattern for reliable tool use with any compatible LLM.

- :material-puzzle: **Extensible Skills**
  DSL-based skill definitions make it easy to add custom capabilities.

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
├── fraggle-agent/          # Core agent, provider, and skill abstractions
├── fraggle-signal/         # Signal messaging integration
├── fraggle-skills/         # Built-in skill implementations
├── fraggle-api/            # REST API server
├── fraggle-dashboard/      # Web dashboard (Compose for Web)
├── fraggle-common/         # Shared models (Kotlin Multiplatform)
├── libs/                   # Internal libraries
└── config/                 # Example configuration files
```

## How It Works

1. **Message Received** - A message arrives via a chat bridge (or interactive mode)
2. **Agent Processing** - The ReAct loop evaluates the message and available tools
3. **Skill Execution** - The agent calls skills as needed (file ops, web fetch, etc.)
4. **Response Sent** - The final response is delivered back to the user

The agent continues reasoning and acting until it produces a final response or reaches the iteration limit.

## License

Fraggle is published under the [MIT License](https://github.com/DrewCarlson/Fraggle/blob/main/LICENSE).
