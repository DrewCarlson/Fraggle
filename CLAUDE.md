# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
# Build all modules
./gradlew build

# Run the application (connects to Signal, production mode)
./gradlew :app:run --args="run"

# Interactive chat mode (for testing without Signal)
./gradlew :app:run --args="chat"

# Test Signal connection
./gradlew :app:run --args="test-signal"

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :fraggle:test
./gradlew :app:test

# Run a specific test class
./gradlew :fraggle:test --tests="*SkillTest"

# Run a specific test method (use full pattern)
./gradlew :fraggle:test --tests="*SkillTest.Execution*"
```

## Project Architecture

Fraggle is a Kotlin-based AI assistant that integrates with messaging platforms (currently Signal) using a local LLM provider (LM Studio).

### Module Structure

- **`fraggle`** - Core framework: agent loop, LLM provider interface, skill system, memory storage, sandbox abstraction, chat bridge interface
- **`fraggle-signal`** - Signal messenger integration with message routing and text formatting
- **`fraggle-skills`** - Built-in skills: filesystem, web fetching, shell execution, task scheduling
- **`app`** - CLI application using Clikt, configuration loading, service orchestration
- **`backend`** - Optional Ktor REST API server

### Key Architectural Patterns

**ReAct Agent Loop** (`FraggleAgent`): Iteratively processes messages, calling tools when needed until a final response is generated. Uses OpenAI function-calling format for tool definitions.

**Skill System**: DSL-based skill definition with type-safe parameters. Skills convert to OpenAI function format automatically.
```kotlin
skill("my_skill") {
    description = "Does something"
    parameter<String>("input") { required = true }
    execute { params -> SkillResult.Success("result") }
}
```

**Hierarchical Memory**: Three scopes (Global, Chat, User) with file-based persistence as human-readable markdown.

**Chat Bridge Abstraction**: `ChatBridge` interface allows multiple messaging platform implementations. `ChatBridgeManager` routes messages with qualified chat IDs (e.g., "signal:+1234567890").

**Prompt Management**: Template files (SYSTEM.md, IDENTITY.md, USER.md) stored as JAR resources, copied to workspace for customization. HTML comments are stripped before injection.

### Configuration

- Config file: `{FRAGGLE_ROOT}/config/fraggle.yaml`
- FRAGGLE_ROOT env var defaults to current directory
- Development runs use `runtime-dev/` (set automatically by Gradle)

### Technology Stack

- Kotlin 2.3.0 on JDK 21
- Ktor 3.4.0 (HTTP client/server)
- kotlinx-coroutines for async operations
- KAML for YAML parsing
- Clikt for CLI
- JUnit 5 + MockK for testing

## Testing Patterns

Tests use JUnit 5 with `@Nested` for organization, `runTest` for coroutines, and MockK for mocking. `InMemoryStore` and temp directories (`@TempDir`) avoid external dependencies.
