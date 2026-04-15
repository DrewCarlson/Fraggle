# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development checklist

For any change, consider and apply updates to:

- Project root README.md file
- mkdocs powered documentation in [docs](docs)
- Dockerfile and docker-compose.yml files
- Github workflow files
- Fraggle Configuration models and related documentation

## Build and Test Commands

```bash
# Build all modules
./gradlew build

# Run the application (connects to Signal, production mode)
./gradlew :fraggle-cli:run --args="run"

# Interactive chat mode (for testing without Signal)
./gradlew :fraggle-cli:run --args="chat"

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :fraggle-agent:test
./gradlew :fraggle-cli:test

# Run a specific test class
./gradlew :fraggle-agent:test --tests="*ToolsTest"

# Run a specific test method (use full pattern)
./gradlew :fraggle-agent:test --tests="*ToolsTest.Execution*"

# Build the dashboard for development (with vite, the KMP webpack tasks are not used)
./gradlew :fraggle-dashboard:jsBrowserDevelopmentDist

# Build the dashboard for production (with vite, the KMP webpack tasks are not used)
./gradlew :fraggle-dashboard:jsBrowserProductionDist
```

## Project Architecture

Fraggle is a Kotlin-based AI assistant that integrates with messaging platforms (Signal, Discord) using a local LLM provider (LM Studio).

### Module Structure

- **`fraggle-di`** - Shared DI infrastructure: Metro scopes/qualifiers, HTTP clients, JSON, `CoroutineScope`, `ConfigModule` (sub-config extraction), `FraggleEnvironment` (path resolution)
- **`fraggle-common`** - Shared models (Kotlin Multiplatform): config data classes, event models, API contracts
- **`fraggle-agent`** - Core framework: agent loop, tool registry, memory, tool executor, chat bridge abstractions. `AgentModule` provides all core services via DI.
- **`fraggle-tools`** - Built-in tools: filesystem, web fetching, shell execution, task scheduling. `ToolsModule` provides `FraggleToolRegistry`, `TaskScheduler`, `PlaywrightFetcher`.
- **`fraggle-signal`** - Signal messenger integration. `SignalModule` provides nullable `SignalBridge?`, `MessageRouter?`, `SignalBridgeInitializer?`.
- **`fraggle-discord`** - Discord bot integration (Kord 0.17.0). `DiscordModule` provides nullable `DiscordBridge?`, `DiscordBridgeInitializer?`.
- **`fraggle-cli`** - CLI entry point (Clikt), `AppGraph` (central DI graph), `ApiModule`, `ServiceOrchestrator` (thin lifecycle manager)
- **`fraggle-api`** - Optional Ktor REST API server with WebSocket event streaming
- **`fraggle-dashboard`** - Web dashboard built with Compose for HTML (no DI — only 2 singletons)

### Dependency Injection (Metro DI)

The application uses [Metro](https://github.com/AdrianAndroid/Metro) v0.10.2 for compile-time dependency injection. Each feature module owns its bindings via `@ContributesTo(AppScope::class)` modules.

**Graph structure:**
- `AppGraph` (`fraggle-cli`) — `@DependencyGraph(AppScope::class)`, exposes all top-level bindings
- `NetworkModule` (`fraggle-di`) — HTTP clients, JSON, `CoroutineScope`
- `ConfigModule` (`fraggle-di`) — Extracts sub-configs from `FraggleConfig` (e.g., `ProviderConfig`, `MemoryConfig`, `SignalBridgeConfig?`)
- `AgentModule` (`fraggle-agent`) — `MemoryStore`, `ToolExecutor`, `PromptManager`, `LMStudioProvider`, `LlmBridge`, `ToolCallExecutor`, `FraggleAgent`, `ChatBridgeManager`, etc.
- `ToolsModule` (`fraggle-tools`) — `FraggleToolRegistry`, `TaskScheduler`, `PlaywrightFetcher?`
- `SignalModule` (`fraggle-signal`) — Nullable chain for Signal services
- `DiscordModule` (`fraggle-discord`) — Nullable chain for Discord services
- `ApiModule` (`fraggle-cli`) — `FraggleServicesImpl`, `EmbeddedServer?`

**Key DI conventions:**
- Classes created by `@Provides` factory methods should NOT have `@Inject` on their constructors
- Classes auto-constructed by Metro (e.g., `InlineImageProcessor`) keep `@Inject`
- Nullable bindings model optional features — when a bridge is unconfigured, its entire dependency chain resolves to null
- Config model types from `fraggle-common` (e.g., `models.AgentConfig`) may clash with runtime types (e.g., `agent.AgentConfig`); use import aliases in DI modules
- `@SingleIn(AppScope::class)` on all singleton bindings
- Qualifiers: `@DefaultHttpClient`, `@LlmHttpClient`

**Adding a new DI binding:**
1. Add a `@Provides` method to the appropriate module's companion object
2. Add an accessor to `AppGraph` if the binding needs to be used directly in `ServiceOrchestrator` or `Main.kt`
3. Run `./gradlew build` — Metro generates factories at compile time, so errors surface immediately

### Key Architectural Patterns

**Agent Loop** (`FraggleAgent` + `Agent`): `FraggleAgent.process()` builds per-request input (platform context, memory, history), constructs a stateful `Agent` (`fraggle.agent.Agent`) wired with `AgentOptions(systemPrompt, model, llmBridge, toolCallExecutor, maxIterations, ...)`, subscribes an `AgentEventTracer`, then calls `agent.prompt(userInput)` and `agent.waitForIdle()`. The loop itself lives in `fraggle-agent/src/main/kotlin/fraggle/agent/loop/` (`runAgentLoop`, `AgentOptions`, `LlmBridge`, `ProviderLlmBridge`, `ToolCallExecutor`). LLM calls go through `LlmBridge` → `ProviderLlmBridge` → `LMStudioProvider` (`fraggle-agent/src/main/kotlin/fraggle/provider/LMStudioProvider.kt`).

**Tool System**: Tools extend `AgentToolDef<Args>` with `@Serializable` argument data classes and `@LLMDescription` annotations. JSON schemas for the LLM are generated from the serializer descriptors by `FraggleToolRegistry`.
```kotlin
class MyTool : AgentToolDef<MyTool.Args>(
    name = "my_tool",
    description = "Does something",
    argsSerializer = Args.serializer(),
) {
    @Serializable
    data class Args(
        @LLMDescription("The input value")
        val input: String,
    )
    override suspend fun execute(args: Args): String = "result"
}
```
Tool invocation goes through `SupervisedToolCallExecutor`, which consults `ToolSupervisor` (policy rules + optional interactive approval) before dispatching to the underlying `AgentToolDef`.

**Hierarchical Memory**: Three scopes (Global, Chat, User) with file-based persistence as human-readable markdown. `FraggleAgent` reads from `MemoryStore` directly when building per-request input. When `autoMemory` is enabled, facts are automatically extracted and reconciled via LLM after each response.

**Event Tracing**: The agent emits a stream of `AgentEvent`s (turn start/end, message start/update/end, tool execution start/end). `AgentEventTracer` bridges these to `TraceStore` (persistence) and `EventBus` (WebSocket fan-out to the dashboard).

**Chat Bridge Abstraction**: `ChatBridge` interface allows multiple messaging platform implementations. `ChatBridgeManager` routes messages with qualified chat IDs (e.g., "signal:+1234567890").

**Prompt Management**: Template files (SYSTEM.md, IDENTITY.md, USER.md) stored as JAR resources, copied to workspace for customization. HTML comments are stripped before injection.

**Service Orchestrator**: Thin lifecycle manager (~290 lines) that takes `AppGraph`, registers bridges/initializers, and runs the message loop. All service creation is handled by DI modules.

### Database (Exposed ORM + SQLite)

The application uses [JetBrains Exposed](https://github.com/JetBrains/Exposed) 1.0.0 with SQLite JDBC 3.51.1.0 for chat history and message metadata persistence. All database code lives in `fraggle-agent/src/main/kotlin/fraggle/db/`.

**Critical: Exposed 1.0 API packages.** Exposed 1.0.0 uses `v1`-namespaced packages — **not** the legacy `org.jetbrains.exposed.sql.*` packages:
```kotlin
// Correct (Exposed 1.0)
import org.jetbrains.exposed.v1.core.*        // Table, Column, ColumnType, eq, and, isNotNull, SortOrder
import org.jetbrains.exposed.v1.jdbc.*        // Database, SchemaUtils, insert, update, select, selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

// Wrong (pre-1.0 API)
import org.jetbrains.exposed.sql.*
```

**Schema** (`Tables.kt`): Two tables — `ChatTable` (platform, externalId, name, isGroup, timestamps) and `MessageTable` (chatId FK, senderId, contentType enum, direction enum, timestamp, processingDuration). Message content is **not** stored — only metadata for analytics.

**Custom column types** (`TimeColumns.kt`): `InstantColumnType` and `DurationColumnType` store `kotlin.time.Instant` and `kotlin.time.Duration` as epoch milliseconds (Long). Used via `Table.instant(name)` and `Table.duration(name)` extension functions.

**Query patterns:**
```kotlin
// All operations wrapped in transaction(database) { ... }
ChatTable.selectAll().where { ChatTable.externalId eq externalId }.singleOrNull()
ChatTable.insert { it[platform] = value }[ChatTable.id]
ChatTable.update({ ChatTable.id eq id }) { it[lastActiveAt] = now }
MessageTable.selectAll().where { ... }.orderBy(MessageTable.timestamp, SortOrder.DESC).limit(limit).offset(offset)
```

**SQLite PRAGMA** must be set via connection URL params (not `exec()`):
```kotlin
val url = "jdbc:sqlite:$dbPath?journal_mode=WAL&foreign_keys=ON"
Database.connect(url = url, driver = "org.sqlite.JDBC")
```

**DI wiring:** `DatabaseConfig` → `ConfigModule`, `FraggleDatabase` + `ChatHistoryStore` → `AgentModule`. Both are `@SingleIn(AppScope::class)` singletons. `FraggleDatabase.close()` uses `TransactionManager.closeAndUnregister(database)`.

### Configuration

- Config file: `{FRAGGLE_ROOT}/config/fraggle.yaml`
- FRAGGLE_ROOT env var defaults to current directory
- Development runs use `runtime-dev/` (set automatically by Gradle)
- `FraggleEnvironment` (in `fraggle-di`) handles path resolution across all modules

### Technology Stack

- Kotlin 2.3.0 on JDK 21
- Metro 0.10.2 (compile-time dependency injection)
- Exposed 1.0.0 + SQLite JDBC 3.51.1.0 (database ORM)
- Ktor 3.4.0 (HTTP client/server)
- Kord 0.17.0 (Discord API)
- kotlinx-coroutines for async operations
- KAML for YAML parsing
- Clikt for CLI
- JUnit 5 + MockK for testing

## Testing Patterns

Tests use JUnit 5 with `@Nested` for organization, `runTest` for coroutines, and MockK for mocking. `InMemoryStore` and temp directories (`@TempDir`) avoid external dependencies. DI modules are tested implicitly through full `./gradlew build` (Metro compile-time validation) and `./gradlew :fraggle-cli:run --args="chat"` for end-to-end verification.
