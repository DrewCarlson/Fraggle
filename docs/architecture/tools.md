# Tools

Tools are the capabilities available to the Fraggle agent. They enable the agent to interact with the file system, web, shell, and more. Tools are built on [Koog's](https://github.com/JetBrains/koog) `SimpleTool` framework.

## Built-in Tools

Fraggle includes several tool groups out of the box.

### Filesystem Tools

File operations within the sandbox:

| Tool | Description | Parameters |
|------|-------------|------------|
| `read_file` | Read file contents | `path` (required), `max_lines` (default: 1000) |
| `write_file` | Write content to a file | `path` (required), `content` (required) |
| `append_file` | Append content to a file | `path` (required), `content` (required) |
| `list_files` | List directory contents | `path` (required), `recursive` (default: false) |
| `search_files` | Search for files by pattern | `path` (required), `pattern` (required, supports `*` and `?` wildcards) |
| `file_exists` | Check if a file or directory exists | `path` (required) |
| `delete_file` | Delete a file | `path` (required) |

### Web Tools

Web fetching and browser automation:

| Tool | Description | Parameters |
|------|-------------|------------|
| `fetch_webpage` | Fetch content from a webpage | `url` (required) |
| `fetch_api` | Fetch data from an API endpoint | `url` (required), `method` (default: GET) |
| `screenshot_page` | Take a screenshot of a webpage | `url` (required), `full_page` (default: false), `caption` (optional) |

!!! note "Playwright Tools"
    The `fetch_webpage` tool uses Playwright for JavaScript rendering when configured. The `screenshot_page` tool requires Playwright configuration.

#### Playwright Setup

For JavaScript-heavy websites, configure Playwright:

**Option 1: Browserless (Docker)**
```bash
docker run -p 3000:3000 browserless/chrome
```

**Option 2: Playwright Server**
```bash
npx playwright run-server --port 3000
```

**Configuration:**
```yaml
fraggle:
  web:
    playwright:
      ws_endpoint: ws://localhost:3000
      navigation_timeout: 30000
      wait_after_load: 2000
```

### Shell Tools

Command execution in the sandbox:

| Tool | Description | Parameters |
|------|-------------|------------|
| `execute_command` | Execute a shell command | `command` (required), `timeout_seconds` (default: 30) |

!!! warning "Security"
    Shell execution includes basic safeguards against dangerous commands (like `rm -rf /`). Use [supervision](../installation/configuration.md#supervision) for interactive approval of tool calls.

### Scheduling Tools

Task scheduling for deferred operations:

| Tool | Description | Parameters |
|------|-------------|------------|
| `schedule_task` | Schedule a task for later | `name`, `action`, `delay_seconds`, `repeat_interval_seconds` (optional) |
| `list_tasks` | List all scheduled tasks | (none) |
| `get_task` | Get task details | `task_id` |
| `cancel_task` | Cancel a scheduled task | `task_id` |

## Defining Custom Tools

Tools extend Koog's `SimpleTool<Args>` with a `@Serializable` data class for parameters:

```kotlin
class WeatherTool(private val apiClient: HttpClient) : SimpleTool<WeatherTool.Args>(
    argsSerializer = Args.serializer(),
    name = "get_weather",
    description = "Get current weather for a location",
) {
    @Serializable
    data class Args(
        @LLMDescription("City name or coordinates")
        val location: String,
    )

    override suspend fun execute(args: Args): String {
        // Call weather API...
        return "Weather in ${args.location}: Sunny, 72F"
    }
}
```

Key points:

- **`@Serializable`** on the `Args` data class enables automatic JSON schema generation for the LLM
- **`@LLMDescription`** on each field provides the parameter description shown to the LLM
- **Default values** on fields make parameters optional (e.g., `val max_lines: Int = 1000`)
- **`execute`** returns a `String` result that the LLM uses for further reasoning

## Tool Registry

Tools are collected into a Koog `ToolRegistry`:

```kotlin
val registry = ToolRegistry {
    tool(MyTool().managed(supervisor, remoteClient))
}
```

The built-in `DefaultTools.createToolRegistry()` registers all standard tools:

```kotlin
val registry = DefaultTools.createToolRegistry(
    toolExecutor = toolExecutor,
    httpClient = httpClient,
    taskScheduler = taskScheduler,
    supervisor = supervisor,
    remoteClient = remoteClient,            // null for local-only
    playwrightFetcher = playwrightFetcher,  // optional
)
```

## Supervision and Remote Execution

Every tool that performs I/O is wrapped in a `ManagedTool` before being added to the registry. `ManagedTool` adds two layers around the underlying tool:

1. **Supervision** — Before executing, the tool checks with a `ToolSupervisor`. In `none` mode (`NoOpToolSupervisor`), all calls are auto-approved. In `supervised` mode (`InteractiveToolSupervisor`), the tool name is checked against the `auto_approve` list; if not listed, a permission request is sent to the user.

2. **Remote forwarding** — If a `RemoteToolClient` is configured (`type: remote`), the call is forwarded over HTTP to a [worker process](../installation/configuration.md#remote-worker) instead of executing locally.

```
ManagedTool.execute(args)
  │
  ├─ Supervisor: check permission
  │   ├─ auto_approve list → Approved
  │   ├─ CLI prompt (fraggle chat) → Approved / Denied
  │   └─ WebSocket event (fraggle run) → Approved / Denied
  │
  ├─ Remote client present? → Forward to worker via HTTP
  └─ Otherwise → Execute delegate tool locally
```

Scheduling tools (`schedule_task`, `list_tasks`, `get_task`, `cancel_task`) are **not** wrapped because they don't perform external I/O.

See [Executor configuration](../installation/configuration.md#executor) for the YAML options.

## Execution Context

Tools can access per-request context (chat ID, user ID) via `ToolExecutionContext`:

```kotlin
override suspend fun execute(args: Args): String {
    val context = ToolExecutionContext.current()
    val chatId = context?.chatId
    // Use context for chat-specific behavior
    return "Response for chat $chatId"
}
```

Tools that produce binary output (like `screenshot_page`) add attachments to the context, which the agent sends alongside the text response.
