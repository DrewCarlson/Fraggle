# Fraggle

[![codecov](https://codecov.io/gh/DrewCarlson/Fraggle/graph/badge.svg?token=W77U2C9HUB)](https://codecov.io/gh/DrewCarlson/Fraggle)

An AI-powered assistant framework that connects LLMs to messaging platforms with extensible tool capabilities — plus a terminal coding agent (`fraggle code`) that shares the same provider, tool, and agent-loop infrastructure.

## Highlights

- **Chat Bridge Integration** - Connect to messaging platforms like Signal
- **Terminal Coding Agent** - `fraggle code` is a Mosaic-based TUI for pair-programming with an LLM: JSONL session branching, `AGENTS.md` context files, Claude-Code-style `edit_file` semantics, interactive tool approval
- **Local LLM Support** - Works with LM Studio for fully local, private AI
- **Cloud Providers** - Also supports OpenAI and Anthropic APIs
- **Built-in Tools** - File operations, web fetching, shell execution, task scheduling, time/timezone
- **Agent Skills** - Portable [agentskills.io](https://agentskills.io) bundles with Python venv support, dependency management, and secrets injection
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

# Launch the terminal coding agent in the current directory
./bin/fraggle code

# Start a remote tool worker
./bin/fraggle worker

# Manage agent skills
./bin/fraggle skills list
./bin/fraggle skills add vercel-labs/skills
./bin/fraggle skills init my-skill -d "What the skill does and when to use it."
./bin/fraggle skills find commit
./bin/fraggle skills remove my-skill

# Python skill management
./bin/fraggle skills setup my-skill          # Create Python venv + install deps
./bin/fraggle skills secrets set my-skill API_KEY   # Configure secrets
./bin/fraggle skills secrets check my-skill  # Verify env var status
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
- [Coding Agent](https://drewcarlson.github.io/Fraggle/coding-agent/)
- [Architecture](https://drewcarlson.github.io/Fraggle/architecture/overview/)
- [Tools Reference](https://drewcarlson.github.io/Fraggle/architecture/tools/)
- [Agent Skills](https://drewcarlson.github.io/Fraggle/architecture/skills/)

## Project Structure

```
Fraggle/
├── fraggle-cli/            # CLI application (run / chat / code / configure / worker)
├── fraggle-assistant/      # Messenger assistant (FraggleAgent, memory, chat bridges, db)
├── fraggle-coding-agent/   # Terminal coding agent (TUI, sessions, coding tools)
├── fraggle-agent-core/     # Generic agent runtime (loop, tools, executor, tracing, compaction)
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
