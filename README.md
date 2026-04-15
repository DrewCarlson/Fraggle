# Fraggle

[![codecov](https://codecov.io/gh/DrewCarlson/Fraggle/graph/badge.svg?token=W77U2C9HUB)](https://codecov.io/gh/DrewCarlson/Fraggle)

An AI-powered assistant framework that connects LLMs to messaging platforms with extensible tool capabilities.

## Highlights

- **Chat Bridge Integration** - Connect to messaging platforms like Signal
- **Local LLM Support** - Works with LM Studio for fully local, private AI
- **Cloud Providers** - Also supports OpenAI and Anthropic APIs
- **Built-in Tools** - File operations, web fetching, shell execution, task scheduling, time/timezone
- **Persistent Memory** - Remembers context across conversations
- **Tool Supervision** - Approve or deny tool calls interactively via CLI or dashboard
- **Remote Execution** - Offload tool execution to a lightweight worker process
- **Web Dashboard** - Monitor and configure your assistant through a web UI

## Quick Start

```bash
# Download latest release
wget https://github.com/DrewCarlson/Fraggle/releases/latest/download/fraggle.zip
unzip fraggle.zip
cd fraggle

# Run the configuration wizard
./bin/fraggle configure

# Run interactive chat (no chat bridge required)
./bin/fraggle chat

# Run with chat bridges enabled
./bin/fraggle run

# Start a remote tool worker
./bin/fraggle worker
```

## Requirements

- JDK 21+
- LLM provider (LM Studio, OpenAI, or Anthropic)
- [signal-cli](https://github.com/AsamK/signal-cli) (optional, for Signal chat bridge)

## Documentation

Full documentation available at **[drewcarlson.github.io/Fraggle](https://drewcarlson.github.io/Fraggle/)**

- [Getting Started](https://drewcarlson.github.io/Fraggle/installation/getting-started/)
- [Configuration](https://drewcarlson.github.io/Fraggle/installation/configuration/)
- [Signal Setup](https://drewcarlson.github.io/Fraggle/installation/signal-setup/)
- [Architecture](https://drewcarlson.github.io/Fraggle/architecture/overview/)
- [Tools Reference](https://drewcarlson.github.io/Fraggle/architecture/tools/)

## Project Structure

```
Fraggle/
├── fraggle-cli/            # CLI application
├── fraggle-assistant/      # Messenger assistant (FraggleAgent, memory, chat bridges, db)
├── fraggle-agent-core/     # Generic agent runtime (loop, tools, executor, tracing)
├── fraggle-llm/            # LLM provider (LMStudioProvider, ChatRequest/Response)
├── fraggle-signal/         # Signal messenger bridge
├── fraggle-discord/        # Discord bot bridge
├── fraggle-tools/          # Built-in tool implementations
├── fraggle-api/            # REST API server
├── fraggle-dashboard/      # Web dashboard (Compose for Web)
├── fraggle-common/         # Shared models (Kotlin Multiplatform)
└── libs/                   # Internal libraries
    └── documented-*        # Annotation processor for config docs
```

## License

Published under the [MIT License](LICENSE).
