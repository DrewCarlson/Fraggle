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
  sandbox:
    type: local                       # local or remote
    work_dir: ./data/workspace
    remote_url: ""                    # Worker URL (required when type: remote)
    supervision: none                 # none or supervised
    auto_approve: []                  # Tool names to auto-approve in supervised mode

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
| `auto_approve` | Tool names that skip approval in supervised mode          | `[]`                 |

#### Execution Modes

**Local** (`type: local`) — Tools run directly in the Fraggle process. This is the default and simplest mode.

**Remote** (`type: remote`) — Tool calls are forwarded over HTTP to a separate worker process. This lets you isolate tool execution on another machine or in a container. You must set `remote_url` to the worker's address and start a worker with `fraggle worker`.

```yaml
sandbox:
  type: remote
  remote_url: http://worker-host:9292
```

#### Supervision

When `supervision: supervised` is set, every tool call requires explicit approval before it runs. How approval works depends on how Fraggle is started:

- **`fraggle chat`** — Prompts on the terminal (stdin). You have 60 seconds to respond `y` or `n`.
- **`fraggle run`** — Emits a permission request event over the WebSocket API. The dashboard or any connected client can approve or deny the request within 120 seconds.

Tools listed in `auto_approve` skip the approval prompt entirely.

```yaml
sandbox:
  supervision: supervised
  auto_approve:
    - list_files
    - file_exists
    - read_file
```

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
