# Architecture Overview

Fraggle is built as a modular Kotlin application with clear separation of concerns. The agent loop and tool system are powered by [Koog](https://github.com/JetBrains/koog), an AI agent framework.

## Module Structure

```
Fraggle/
├── fraggle-cli/            # CLI application and entry point
├── fraggle-agent/          # Core framework
├── fraggle-signal/         # Signal messenger bridge
├── fraggle-discord/        # Discord bot bridge
├── fraggle-tools/          # Built-in tool implementations
├── fraggle-common/         # Shared models (Kotlin Multiplatform)
├── fraggle-api/            # REST API server
├── fraggle-dashboard/      # Web dashboard (Compose for Web)
└── libs/                   # Internal libraries
    └── documented-*        # Annotation processor for config docs
```

## Core Components

### FraggleAgent

The central component that orchestrates message processing. It wraps Koog's `AIAgentService` which implements a ReAct (Reasoning + Acting) loop:

1. Receives a message
2. Builds per-request input (platform context, memory, conversation history)
3. Delegates to Koog's agent service, which sends the context + tools to the LLM
4. Koog handles tool calls iteratively until a final response is produced
5. If auto-memory is enabled, new facts are extracted and reconciled

See [Agent System](agent.md) for details.

### Tool System

Tools are the capabilities available to the agent. Each tool extends Koog's `SimpleTool` with type-safe serializable parameters:

```kotlin
class MyTool : SimpleTool<MyTool.Args>(
    argsSerializer = Args.serializer(),
    name = "my_tool",
    description = "Does something useful",
) {
    @Serializable
    data class Args(
        @LLMDescription("The input value")
        val input: String,
    )
    override suspend fun execute(args: Args): String = "Result: ${args.input}"
}
```

See [Tools](tools.md) for the full reference.

### Memory System

Hierarchical memory storage with three scopes:

- **Global** - Shared across all conversations
- **Chat** - Per-conversation memory
- **User** - Per-user memory across chats

Memory is persisted as human-readable markdown files. A `FraggleMemoryProvider` adapter bridges Fraggle's storage to Koog's memory system.

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
│    Chat     │────>│   Agent     │────>│   Tools     │
│   Bridge    │<────│   (Koog)    │<────│             │
└─────────────┘     └─────────────┘     └─────────────┘
                          │
                          v
                    ┌─────────────┐
                    │   Memory    │
                    └─────────────┘
```

1. **Chat Bridge** receives messages and passes them to the agent
2. **Agent** builds context and delegates to Koog's agent service, which calls tools as needed
3. **Tools** execute operations and return results
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

- **JVM** - Main application, chat bridges, tools
- **JS** - Web dashboard (Compose for Web)

Shared modules (like configuration models) compile to both targets.
