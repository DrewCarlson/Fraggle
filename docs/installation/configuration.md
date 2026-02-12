# Configuration

Fraggle uses a YAML configuration file located at `$FRAGGLE_ROOT/config/fraggle.yaml`.

## Configuration Wizard

The easiest way to configure Fraggle is with the interactive wizard:

```bash
./bin/fraggle configure
```

The wizard offers two modes:

- **Quick setup** - Configure essential settings: LLM provider, chat bridge, and API server
- **Full setup** - Walk through all configuration sections

If a configuration file already exists, the wizard will use your current values as defaults.

## Environment Variables

| Variable       | Description                                               | Default           |
|----------------|-----------------------------------------------------------|-------------------|
| `FRAGGLE_ROOT` | Root directory for all runtime files (config, data, logs) | Current directory |

## Full Configuration Reference

```yaml
fraggle:
  # LLM Provider settings
  provider:
    type: lmstudio                    # lmstudio, openai, anthropic
    url: http://localhost:1234/v1     # API endpoint
    model: ""                         # Model name (optional for LM Studio)
    api_key: ""                       # Required for OpenAI/Anthropic

  # Chat bridge configurations
  bridges:
    signal:
      enabled: true
      phone: "+1234567890"            # Your Signal phone number
      config_dir: ./config/apps/signal
      trigger: "@fraggle"             # Prefix to trigger bot in groups
      signal_cli_path: null           # Path to signal-cli (null = auto-detect)
      auto_install: true              # Auto-download signal-cli if not found
      signal_cli_version: "0.13.23"   # Version to auto-install
      respond_to_direct_messages: true
      show_typing_indicator: true

  # Prompt file configuration
  prompts:
    prompts_dir: ./config/prompts     # Directory for prompt files
    max_file_chars: 20000             # Max chars per prompt file
    auto_create_missing: true         # Auto-create from templates

  # Memory storage settings
  memory:
    base_dir: ./data/memory

  # Tool execution settings
  executor:
    type: local                       # local or remote
    work_dir: ./data/workspace
    remote_url: ""                    # Worker URL (required when type: remote)
    supervision: none                 # none or supervised
    tool_policies: []                  # Policy-based rules for tool approval (allow, deny, ask)

  # Agent behavior settings
  agent:
    temperature: 0.7                  # LLM sampling temperature (0.0-2.0)
    max_tokens: 4096                  # Max response tokens
    max_iterations: 10                # Max tool-use iterations
    max_history_messages: 20          # Messages to include in context

  # Registered chats with custom settings
  chats:
    registered: []
    # - id: "group-abc123"
    #   name: "Dev Team"
    #   trigger_override: "@bot"
    #   enabled: true

  # Web browsing configuration
  web:
    playwright:                       # Optional Playwright config
      ws_endpoint: ws://localhost:3000
      navigation_timeout: 30000
      wait_after_load: 2000
      viewport_width: 1280
      viewport_height: 720
      user_agent: null                # null = browser default

  # Tracing
  tracing:
    level: off                        # off, metadata, full

  # REST API server
  api:
    enabled: false
    host: "0.0.0.0"
    port: 9191
    cors:
      enabled: true
      allowed_origins: []

  # Web dashboard
  dashboard:
    enabled: false
    static_path: null                 # null = use embedded assets
```

## Configuration Sections

### Provider

Configures the LLM provider connection.

| Option    | Description                              | Default                     |
|-----------|------------------------------------------|-----------------------------|
| `type`    | Provider type: `lmstudio`, `openai`, `anthropic` | `lmstudio`         |
| `url`     | API endpoint URL                         | `http://localhost:1234/v1`  |
| `model`   | Model identifier (optional for LM Studio)| `""`                        |
| `api_key` | API key for authentication               | `null`                      |

### Bridges

Chat platform integrations. Currently supports Signal.

**Signal Bridge** - Fraggle can automatically download and install signal-cli if it's not found in your system PATH. This is enabled by default (`auto_install: true`). On Linux, the native binary is downloaded for better performance; on other platforms, the Java-based version is used.

| Option                       | Description                             | Default                    |
|------------------------------|-----------------------------------------|----------------------------|
| `phone`                      | Signal phone number (with country code) | Required                   |
| `enabled`                    | Whether bridge is active                | `true` if phone is set     |
| `config_dir`                 | Directory for Signal configuration      | `~/.config/fraggle/signal` |
| `trigger`                    | Prefix to trigger bot in group chats    | `@fraggle`                 |
| `signal_cli_path`            | Path to signal-cli (null = auto-detect) | `null`                     |
| `auto_install`               | Auto-download signal-cli if not found   | `true`                     |
| `signal_cli_version`         | Version of signal-cli to auto-install   | `0.13.23`                  |
| `respond_to_direct_messages` | Respond to DMs without trigger          | `true`                     |
| `show_typing_indicator`      | Show typing indicator while processing  | `true`                     |

See [Signal Setup](signal-setup.md) for detailed Signal configuration and registration.

### Prompts

The prompt system uses markdown files for customization:

- `SYSTEM.md` - Core system prompt and instructions
- `IDENTITY.md` - Agent personality and identity
- `USER.md` - User-specific instructions and preferences

Files are loaded from `prompts_dir` and concatenated to form the full system prompt.

### Memory

Memory is stored as human-readable markdown files in hierarchical scopes:

- **Global** - Shared across all conversations (`memory/global/`)
- **Chat** - Per-conversation memory (`memory/chats/{chat_id}/`)
- **User** - Per-user memory (`memory/users/{user_id}/`)

### Executor

Controls how tools execute file and shell operations. The YAML key is `sandbox` for backward compatibility.

| Option         | Description                                               | Default              |
|----------------|-----------------------------------------------------------|----------------------|
| `type`         | Execution mode: `local` or `remote`                       | `local`              |
| `work_dir`     | Working directory for tool operations                     | `./data/workspace`   |
| `remote_url`   | URL of the remote worker (required when `type: remote`)   | `""`                 |
| `supervision`  | Permission mode: `none` or `supervised`                   | `none`               |
| `tool_policies` | Policy-based rules for tool approval (allow, deny, ask with arg matchers and shell-aware command matching) | `[]`  |

#### Execution Modes

**Local** (`type: local`) — Tools run directly in the Fraggle process. This is the default and simplest mode.

**Remote** (`type: remote`) — Tool calls are forwarded over HTTP to a separate worker process. This lets you isolate tool execution on another machine or in a container. You must set `remote_url` to the worker's address and start a worker with `fraggle worker`.

```yaml
executor:
  type: remote
  remote_url: http://worker-host:9292
```

#### Supervision

When `supervision: supervised` is set, every tool call requires explicit approval before it runs. How approval works depends on how Fraggle is started:

- **`fraggle chat`** — Prompts on the terminal (stdin). You have 60 seconds to respond `y` or `n`.
- **`fraggle run`** — Emits a permission request event over the WebSocket API. The dashboard or any connected client can approve or deny the request within 120 seconds.

Rules in `tool_policies` are evaluated top-to-bottom, first match wins. Each rule specifies a `tool` name and an optional `policy` (`allow`, `deny`, or `ask`). The default policy is `allow`.

```yaml
executor:
  supervision: supervised
  tool_policies:
    # Simplest: tool name only (policy defaults to allow)
    - tool: list_files

    # Explicit policy with argument constraints
    - tool: read_file
      policy: allow
      args:
        - name: path
          value: [/workspace/**]

    # Allow reading only markdown files under /workspace
    - tool: read_file
      policy: allow
      args:
        - name: path
          value: ["/workspace/{*.md,**/*.md}"]

    # Deny specific patterns
    - tool: write_file
      policy: deny
      args:
        - name: path
          value: [/etc/**]

    # Arg-level policy override
    - tool: execute_command
      policy: allow
      args:
        - name: command
          value: [ls]
        - name: working_dir
          value: [/sensitive/**]
          policy: deny
```

##### Policies

| Policy  | Behavior                                                     |
|---------|--------------------------------------------------------------|
| `allow` | Tool call is approved immediately (default)                  |
| `deny`  | Tool call is denied immediately without consulting the user  |
| `ask`   | Tool call is forwarded to the user for interactive approval  |

When a rule has no `policy` field, it defaults to `allow`. When no rule matches a tool call, the call is forwarded to the user (same as `ask`).

##### Argument Matchers

When a rule includes `args`, all matchers must pass for the rule to match. The `value` field is a list of patterns. How patterns are interpreted depends on the tool's argument type:

- **Path arguments** (e.g., `read_file.path`, `write_file.path`): patterns are glob-matched against the normalized path. Normalization prevents traversal attacks (e.g., `/workspace/../etc/passwd` is normalized to `/etc/passwd` and will not match `/workspace/**`).
- **Shell command arguments** (e.g., `execute_command.command`): patterns are shell-aware command patterns (see below).
- **Other arguments**: patterns are plain glob-matched against the raw string value.

| Pattern                       | Matches                                              |
|-------------------------------|------------------------------------------------------|
| `/workspace/**`               | Any file at any depth under /workspace               |
| `/workspace/*/*.md`           | `.md` files exactly one directory under /workspace   |
| `/workspace/**/*.md`          | `.md` files at any depth under /workspace (not direct children) |
| `/workspace/{*.md,**/*.md}`   | `.md` files at any depth including direct children   |
| `*.txt`                       | Any .txt filename                                    |
| `/data/?`                     | Single character under /data                         |
| `*.{txt,md}`                  | Files ending in .txt or .md                          |

**Note on `**` vs `**/*`:** The pattern `/workspace/**` matches any file at any depth (including direct children). However, `/workspace/**/*.md` requires at least one intermediate directory — it will **not** match `/workspace/readme.md`. To match `.md` files at all depths including direct children, use the brace expansion pattern `/workspace/{*.md,**/*.md}`.

Each argument matcher can also have its own `policy` field that overrides the tool-level policy. When multiple arg-level policies are present, the most restrictive one wins (`deny` > `ask` > `allow`).

##### Shell-aware Command Matching

For `execute_command` and similar shell tools, the `command` argument is automatically evaluated with shell-aware matching. The shell string is parsed and decomposed into individual commands (handling `&&`, `||`, `;`, `|`, `$(...)`, backticks, subshells, and process substitutions). Every parsed command must match at least one pattern for the rule to match. If any command doesn't match, the rule falls through.

There are two formats for defining command patterns: **simple value patterns** and **structured command patterns**.

###### Simple Value Patterns

Each `value` pattern is interpreted as `"executable [argPatterns...]"`. Flags (arguments starting with `-`) are automatically skipped when matching positional arguments against patterns.

- `ls` — allow `ls` with any arguments and any flags
- `cat /workspace/**` — allow `cat` with any flags, but only with positional args matching `/workspace/**`
- `grep` — allow `grep` with any arguments

```yaml
executor:
  supervision: supervised
  tool_policies:
    # Whitelist safe commands — only these executables are allowed
    - tool: execute_command
      policy: allow
      args:
        - name: command
          value:
            - ls
            - "cat /workspace/**"
            - grep
            - echo
            - uname

    # Explicitly deny dangerous commands
    - tool: execute_command
      policy: deny
      args:
        - name: command
          value:
            - rm
            - dd
            - mkfs
```

When argument patterns are provided (e.g., `cat /workspace/**`), every positional argument of the parsed command must match at least one pattern. Flags like `-n` or `--verbose` are skipped. Path arguments are normalized to prevent path traversal.

###### Structured Command Patterns

For fine-grained control over flags and positional arguments, use the `commands` field instead of `value`. Each entry is a `CommandPattern` with:

| Field         | Type           | Description                                                                 |
|---------------|----------------|-----------------------------------------------------------------------------|
| `command`     | `string`       | Executable name to match (required)                                         |
| `allow_flags` | `list<string>` | Flag allowlist. `null` = any flags OK (default); `[]` = no flags allowed    |
| `deny_flags`  | `list<string>` | Flag denylist. Checked first — takes precedence over `allow_flags`          |
| `paths`       | `list<string>` | Glob patterns for path-like positional args (normalized before matching)    |
| `args`        | `list<string>` | Glob patterns for non-path positional args (plain match, no normalization)  |

Positional arguments are classified as **path-like** (starts with `/`, `./`, `../`, `~`, or contains `/` without `://`) or **value-like** (everything else). Path-like args are matched against `paths` patterns; value-like args are matched against `args` patterns. If both `paths` and `args` are empty, any positional arguments are allowed — the constraint is only on the executable and flags.

```yaml
executor:
  supervision: supervised
  tool_policies:
    - tool: execute_command
      args:
        - name: command
          commands:
            # Path-only constraint, any flags
            - command: cat
              paths: ["/workspace/**"]

            # Flag allowlist + path constraint
            - command: grep
              allow_flags: ["-r", "-i", "-n", "-l", "-c", "-v", "-E", "-P", "-e"]
              paths: ["/workspace/**"]

            # Flag denylist + mixed positional args
            - command: chmod
              deny_flags: ["-R", "--recursive"]
              args: ["*"]               # permission value (644, +x, etc.)
              paths: ["/workspace/**"]  # target path

            # Flag-only restriction, any positional args
            - command: rm
              deny_flags: ["-r", "-R", "-f", "--recursive", "--force"]
```

Flag patterns support globs (e.g., `-*f*` matches `-rf`, `-force`). Combined flags like `-rf` are matched as-is — list all relevant forms explicitly or use globs.

#### Remote Worker

Start a lightweight worker process to handle tool execution:

```bash
fraggle worker [--port PORT] [--work-dir DIR]
```

| Option       | Description                      | Default              |
|--------------|----------------------------------|----------------------|
| `--port`     | HTTP port to listen on           | `9292`               |
| `--work-dir` | Working directory for tools      | `./data/workspace`   |

The worker exposes two endpoints:

| Endpoint                      | Method | Description                          |
|-------------------------------|--------|--------------------------------------|
| `/health`                     | GET    | Returns a JSON array of tool names   |
| `/api/v1/tools/execute`       | POST   | Execute a tool and return the result |

The worker loads the same built-in tools as the main process but runs with no supervision and no remote forwarding — it always executes locally.

### Agent

Controls agent behavior during the ReAct loop:

| Option                | Description                              | Default |
|-----------------------|------------------------------------------|---------|
| `temperature`         | LLM sampling temperature                 | `0.7`   |
| `max_tokens`          | Maximum response tokens                  | `4096`  |
| `max_iterations`      | Maximum tool-use cycles per request      | `10`    |
| `max_history_messages`| Messages to include in context           | `20`    |

### Registered Chats

Pre-configure specific chats with custom settings:

```yaml
chats:
  registered:
    - id: "group-abc123"        # Signal group ID
      name: "Dev Team"          # Human-readable name
      trigger_override: "@bot"  # Custom trigger for this chat
      enabled: true             # Enable/disable this chat
```

### Playwright (Optional)

For JavaScript-heavy websites, configure Playwright for full browser rendering:

```yaml
web:
  playwright:
    ws_endpoint: ws://localhost:3000
    navigation_timeout: 30000     # Page load timeout (ms)
    wait_after_load: 2000         # Wait for JS to settle (ms)
    viewport_width: 1280
    viewport_height: 720
    user_agent: null              # Custom user agent
```

See [Tools - Web Tools](../architecture/tools.md#web-tools) for setup instructions.

### Tracing

Controls agent tracing and observability. When enabled, trace events are captured during agent execution and viewable via the API and dashboard.

| Option  | Description                             | Default |
|---------|-----------------------------------------|---------|
| `level` | Tracing level: `off`, `metadata`, `full`| `off`   |

**Tracing levels:**

- **`off`** — Tracing is completely disabled. No trace events are captured and no overhead is added.
- **`metadata`** — Trace events are captured (agent lifecycle, tool calls, LLM call timing) but LLM message content is omitted from `detail` fields.
- **`full`** — Everything is captured, including full LLM request/response content with message payloads and token usage.

```yaml
tracing:
  level: full
```

### API Server

Enable the REST API for external integrations:

```yaml
api:
  enabled: true
  host: "0.0.0.0"
  port: 9191
  cors:
    enabled: true
    allowed_origins:
      - "http://localhost:3000"
      - "https://myapp.example.com"
```

### Dashboard

Enable the web dashboard UI (requires API to be enabled):

```yaml
api:
  enabled: true
dashboard:
  enabled: true
  static_path: null  # Use embedded assets
```
