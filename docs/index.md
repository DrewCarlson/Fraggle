# Fraggle

Fraggle is a Kotlin-based AI assistant framework that integrates with messaging platforms using local LLM providers.

## Features

- **ReAct-style Agent Loop** - Iterative reasoning with tool calling
- **Signal Integration** - Chat via Signal messenger
- **Extensible Skill System** - DSL-based skill definitions
- **Hierarchical Memory** - Global, chat, and user-scoped memory
- **Local LLM Support** - Works with LM Studio and compatible providers

## Quick Start

```bash
# Clone and build
git clone https://github.com/DrewCarlson/Fraggle.git
cd Fraggle
./gradlew build

# Run interactive chat mode
./gradlew :app:run --args="chat"
```

See the [Getting Started](installation/getting-started.md) guide for full setup instructions.
