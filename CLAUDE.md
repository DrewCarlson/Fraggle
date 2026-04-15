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

# Terminal coding agent (TUI against the current working directory)
./gradlew :fraggle-cli:run --args="code"

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :fraggle-assistant:test
./gradlew :fraggle-cli:test

# Run a specific test class
./gradlew :fraggle-assistant:test --tests="*ToolsTest"

# Run a specific test method (use full pattern)
./gradlew :fraggle-assistant:test --tests="*ToolsTest.Execution*"

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
- **`fraggle-llm`** - LLM provider library: `LMStudioProvider`, `ChatRequest`/`ChatResponse`, `Message`/`ToolCall`/`Role` types, native LM Studio v1 serializer. Zero Fraggle-specific coupling (no DI, no config models); consumers pass primitives into the constructor.
- **`fraggle-agent-core`** - Generic agent runtime: `Agent`, `runAgentLoop`, `AgentState`, `AgentEvent`, `AgentMessage`, `LlmBridge`/`ProviderLlmBridge`, `AgentToolDef`, `FraggleToolRegistry`, `SupervisedToolCallExecutor`, `ToolExecutor`/`LocalToolExecutor`, `ToolSupervisor` + policy rules, `RemoteToolClient`, `AgentEventTracer`, `TraceStore`, `EventBus`, `ResponseAttachment`, `ToolExecutionContext`, **and the shared compaction layer** (`Compactor`, `LlmCompactor`, `CompactionPolicy`, `ContextUsage`). Reusable by any agent application. `AgentCoreModule` provides `LMStudioProvider`, `ToolExecutor`, `ToolSupervisor`, `RemoteToolClient?`, `TraceStore?`.
- **`fraggle-assistant`** - Messenger assistant built on top of `fraggle-agent-core`: `FraggleAgent` orchestration, hierarchical `MemoryStore` (Global/Chat/User), `PromptManager` + templates (SYSTEM/IDENTITY/USER), auto-memory extraction/reconciliation, history compression (via shared `LlmCompactor`), `ChatBridge` + `ChatBridgeManager`, `ChatHistoryStore` + Exposed db, `InlineImageProcessor`, `TaskScheduler` + scheduling tools. `AssistantModule` provides the assistant-specific bindings, including the final `FraggleToolRegistry` that decorates `fraggle-tools`' `@BaseFraggleToolRegistry` with scheduling tools.
- **`fraggle-coding-agent`** - Terminal coding agent (`fraggle code`): `CodingAgent` orchestrator, session JSONL files with parentId tree (`SessionEntry`/`SessionFile`/`SessionTree`/`SessionManager` + `SessionTree.replayCurrentBranch()`), `AgentsFileLoader` context walker, `SystemPromptBuilder`, `PromptTemplateLoader`, `EditFileTool` (exact-string replace), `CodingToolRegistry` composing the base tools with `edit_file`, Mosaic-based TUI (`CodingApp`, `Editor`, `MessageList`, `Header`, `Footer`, `ApprovalOverlay`, `SlashCommandRegistry`, `Theme`, `EditorState`), `TuiToolPermissionHandler`, `CodingSettings` + `SettingsStore`. Depends on `fraggle-agent-core` + `fraggle-llm` + `fraggle-tools`; **does not depend on `fraggle-assistant`** so a future coding-agent-only distribution is possible. Uses direct construction, not Metro DI (the orchestrator is built manually by `CodeCommand` in `fraggle-cli`).
- **`fraggle-tools`** - Built-in tools: filesystem, web fetching, shell execution. `ToolsModule` provides `@BaseFraggleToolRegistry FraggleToolRegistry` (base set, no scheduling), `PlaywrightFetcher?`, `ToolArgTypes`. Depends on `fraggle-agent-core` only — scheduling tools moved to `fraggle-assistant` during the coding-agent pre-work so `fraggle-tools` stays reusable by non-messenger apps.
- **`fraggle-signal`** - Signal messenger integration. `SignalModule` provides nullable `SignalBridge?`, `MessageRouter?`, `SignalBridgeInitializer?`.
- **`fraggle-discord`** - Discord bot integration (Kord 0.17.0). `DiscordModule` provides nullable `DiscordBridge?`, `DiscordBridgeInitializer?`.
- **`fraggle-cli`** - CLI entry point (Clikt). Subcommands: `run` (production Signal+Discord service), `chat` (interactive messenger), `code` (terminal coding agent), `configure` (config wizard), `init-bridge` (interactive bridge setup), `worker` (remote tool execution endpoint). Hosts `AppGraph` (central Metro DI graph used by the messenger assistant commands), `ApiModule`, `ServiceOrchestrator`. The `code` subcommand bypasses Metro and constructs `CodingAgent` directly.
- **`fraggle-api`** - Optional Ktor REST API server with WebSocket event streaming
- **`fraggle-dashboard`** - Web dashboard built with Compose for HTML (no DI — only 2 singletons)

### Dependency Injection (Metro DI)

The application uses [Metro](https://github.com/AdrianAndroid/Metro) v0.10.2 for compile-time dependency injection. Each feature module owns its bindings via `@ContributesTo(AppScope::class)` modules.

**Graph structure:**
- `AppGraph` (`fraggle-cli`) — `@DependencyGraph(AppScope::class)`, exposes all top-level bindings
- `NetworkModule` (`fraggle-di`) — HTTP clients, JSON, `CoroutineScope`
- `ConfigModule` (`fraggle-di`) — Extracts sub-configs from `FraggleConfig` (e.g., `ProviderConfig`, `MemoryConfig`, `SignalBridgeConfig?`)
- `AgentCoreModule` (`fraggle-agent-core`) — `LMStudioProvider`, `ToolExecutor`, `ToolSupervisor`, `RemoteToolClient?`, `TraceStore?`
- `AssistantModule` (`fraggle-assistant`) — `MemoryStore`, `PromptManager`, `LlmBridge`, `ToolCallExecutor`, runtime `AgentConfig`, `FraggleAgent`, `ChatBridgeManager`, `FraggleDatabase`, `ChatHistoryStore`, etc.
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

**Agent Loop** (`Agent` in `fraggle-agent-core`): A stateful wrapper owning the conversation, message queues, and listener list. Runs the loop via `runAgentLoop` from `fraggle-agent-core/src/main/kotlin/fraggle/agent/loop/` (files: `AgentLoop`, `AgentOptions`, `LlmBridge`, `ProviderLlmBridge`, `ToolCallExecutor`). LLM calls go through `LlmBridge` → `ProviderLlmBridge` → `LMStudioProvider` (`fraggle-llm/src/main/kotlin/fraggle/provider/LMStudioProvider.kt`). Two orchestrators sit on top of this core:
- **Messenger assistant** (`FraggleAgent` in `fraggle-assistant`): `process()` builds per-request input (platform context, hierarchical memory, conversation history), constructs an `Agent`, calls `prompt()` + `waitForIdle()`, extracts memory after each turn. Compaction via the shared `LlmCompactor` with `MessageCountCompactionPolicy(maxHistoryMessages)`.
- **Coding agent** (`CodingAgent` in `fraggle-coding-agent`): Owns a single `Agent` instance for its lifetime, persists every turn to a JSONL session file via `Session.record()`, runs `maybeCompact()` before each prompt, splices compaction summaries into the agent state via `Agent.replaceMessages()`, exposes `subscribe()` so the Mosaic TUI can render streaming events.

**Compaction** (`fraggle.agent.compaction.*` in `fraggle-agent-core`): Shared by both orchestrators. `Compactor` interface + `LlmCompactor` default impl. Decides whether to compact via a `CompactionPolicy` (`Ratio`/`MessageCount`/`AnyOf`/`AllOf`/`Never`), then summarizes the older head of the message list via a dedicated `LlmBridge` call and returns a `Compacted(recentMessages, compactedCount, summary)` result. Callers decide what to do with the summary — the assistant puts it in the system prompt, the coding agent splices it as a synthetic user message + writes a `Meta` session entry.

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

The application uses [JetBrains Exposed](https://github.com/JetBrains/Exposed) 1.0.0 with SQLite JDBC 3.51.1.0 for chat history and message metadata persistence. All database code lives in `fraggle-assistant/src/main/kotlin/fraggle/db/`.

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
- FRAGGLE_ROOT env var defaults to `~/.fraggle`
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
