# Fraggle

[![codecov](https://codecov.io/gh/DrewCarlson/Fraggle/graph/badge.svg?token=W77U2C9HUB)](https://codecov.io/gh/DrewCarlson/Fraggle)

An AI-powered assistant framework that connects LLMs to messaging platforms with extensible tool capabilities.

## Highlights

- **Chat Bridge Integration** - Connect to messaging platforms like Signal
- **Local LLM Support** - Works with LM Studio for fully local, private AI
- **Cloud Providers** - Also supports OpenAI and Anthropic APIs
- **Built-in Skills** - File operations, web fetching, shell execution, task scheduling
- **Persistent Memory** - Remembers context across conversations
- **Sandboxed Execution** - Safe environment for file and shell operations
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
- [Skills Reference](https://drewcarlson.github.io/Fraggle/architecture/skills/)

## Project Structure

```
Fraggle/
├── app/                    # CLI application
├── fraggle/                # Core agent and provider abstractions
├── fraggle-signal/         # Signal messenger integration
├── fraggle-skills/         # Built-in skill implementations
├── backend/                # REST API server
├── dashboard/              # Web dashboard (Compose for Web)
└── shared/                 # Shared models (Kotlin Multiplatform)
```

## License

Published under the [MIT License](LICENSE).
