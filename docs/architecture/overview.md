# Architecture Overview

Fraggle is built as a modular Kotlin application with clear separation of concerns.

## Module Structure

```
Fraggle/
├── app/                    # CLI application and entry point
├── fraggle/                # Core framework
│   ├── agent/              # ReAct agent implementation
│   ├── provider/           # LLM provider abstractions
│   ├── skill/              # Skill system and DSL
│   └── sandbox/            # Execution sandbox
├── fraggle-signal/         # Signal messenger bridge
├── fraggle-skills/         # Built-in skill implementations
├── shared/                 # Shared models (Kotlin Multiplatform)
├── backend/                # REST API server
├── dashboard/              # Web dashboard (Compose for Web)
└── documented-*            # Annotation processor for config docs
```

## Core Components

### FraggleAgent

The central component that orchestrates message processing. It implements a ReAct (Reasoning + Acting) loop:

1. Receives a message
2. Sends message + history + tools to LLM
3. If LLM requests a tool call, executes it and loops back to step 2
4. If LLM produces a final response, returns it

See [Agent System](agent.md) for details.

### LLM Providers

Abstraction layer for different LLM backends:

- **LM Studio** - Local inference via OpenAI-compatible API
- **OpenAI** - GPT models via OpenAI API
- **Anthropic** - Claude models via Anthropic API

All providers implement the same interface, making it easy to switch between them.

### Skill System

Skills are the tools available to the agent. They're defined using a Kotlin DSL:

```kotlin
skill("my_skill") {
    description = "Does something useful"

    parameter<String>("input") {
        description = "The input value"
        required = true
    }

    execute { params ->
        val input = params.get<String>("input")
        SkillResult.Success("Result: $input")
    }
}
```

See [Skills](skills.md) for the full skill reference.

### Memory System

Hierarchical memory storage with three scopes:

- **Global** - Shared across all conversations
- **Chat** - Per-conversation memory
- **User** - Per-user memory across chats

Memory is persisted as human-readable markdown files.

See [Memory](memory.md) for details.

### Sandbox

Execution environment for potentially dangerous operations:

- File system access
- Shell command execution
- Network requests

The sandbox can be configured for different security levels.

## Data Flow

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│    Chat     │────>│   Agent     │────>│   Skills    │
│   Bridge    │<────│   (ReAct)   │<────│             │
└─────────────┘     └─────────────┘     └─────────────┘
                          │
                          v
                    ┌─────────────┐
                    │   Memory    │
                    └─────────────┘
```

1. **Chat Bridge** receives messages and passes them to the agent
2. **Agent** processes messages using the ReAct loop, calling skills as needed
3. **Skills** execute operations and return results
4. **Memory** stores and retrieves conversation context
5. **Agent** generates final response
6. **Chat Bridge** sends the response back to the user

## Configuration

Configuration is managed through a YAML file with type-safe Kotlin models:

```kotlin
@Serializable
data class FraggleConfig(
    val fraggle: FraggleSettings,
)
```

The `@Documented` annotation generates runtime documentation that powers the web dashboard's settings UI.

## Platform Support

Fraggle uses Kotlin Multiplatform for shared code:

- **JVM** - Main application, chat bridges, skills
- **JS** - Web dashboard (Compose for Web)

Shared modules (like configuration models) compile to both targets.
