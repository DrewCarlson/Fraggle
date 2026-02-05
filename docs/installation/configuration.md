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
      config_dir: ~/.config/fraggle/signal
      trigger: "@fraggle"             # Prefix to trigger bot in groups
      signal_cli_path: null           # Path to signal-cli (null = use PATH)
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

  # Sandbox settings for file/shell operations
  sandbox:
    type: permissive                  # permissive, docker, gvisor
    work_dir: ./data/workspace

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

See [Signal Setup](signal-setup.md) for detailed Signal configuration.

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

### Sandbox

Controls the execution environment for file and shell operations:

| Type         | Description                                    |
|--------------|------------------------------------------------|
| `permissive` | Direct execution with minimal restrictions     |
| `docker`     | Execute commands in Docker containers          |
| `gvisor`     | Execute in gVisor-sandboxed containers         |

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

See [Skills - Web Skills](../architecture/skills.md#web-skills) for setup instructions.

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
