# Fraggle

[![codecov](https://codecov.io/gh/DrewCarlson/Fraggle/graph/badge.svg?token=W77U2C9HUB)](https://codecov.io/gh/DrewCarlson/Fraggle)

An AI-powered assistant that integrates with Signal messaging, featuring tool use capabilities, persistent memory, and sandboxed execution.

## Features

- **Signal Integration** - Receive and respond to Signal messages (direct and group chats)
- **LLM Provider Support** - Connect to LM Studio, OpenAI, or Anthropic APIs
- **Tool/Skill System** - Extensible skills for file operations, web fetching, shell execution, and task scheduling
- **Persistent Memory** - Per-conversation memory storage
- **Sandboxed Execution** - Safe execution environment for file and shell operations
- **Interactive Mode** - Test the agent without Signal integration

## Project Structure

```
Fraggle/
├── app/                    # Main application entry point
├── fraggle/                # Core agent, provider, and skill abstractions
├── fraggle-signal/         # Signal messaging integration
├── fraggle-skills/         # Built-in skill implementations
├── backend/                # Backend API server (optional)
└── config/                 # Example configuration files
```

## Requirements

- JDK 21+
- [signal-cli](https://github.com/AsamK/signal-cli) (for Signal integration)
- An LLM provider (LM Studio, OpenAI API, or Anthropic API)

## Quick Start

### 1. Clone and Build

```bash
git clone https://github.com/DrewCarlson/Fraggle.git
cd Fraggle
./gradlew build
```

### 2. Configure

Copy the example configuration and customize it:

```bash
mkdir -p runtime-dev/config
cp config/fraggle.yaml runtime-dev/config/fraggle.yaml
```

Edit `runtime-dev/config/fraggle.yaml` to configure your LLM provider and Signal settings.

### 3. Run

See the [Running](#running) section below for available commands.

## Configuration

Fraggle uses a YAML configuration file. By default, it looks for `$FRAGGLE_ROOT/config/fraggle.yaml`.

### Environment Variables

| Variable       | Description                                               | Default           |
|----------------|-----------------------------------------------------------|-------------------|
| `FRAGGLE_ROOT` | Root directory for all runtime files (config, data, logs) | Current directory |

### Configuration File

```yaml
fraggle:
  # LLM Provider settings
  provider:
    type: lmstudio                    # lmstudio, openai, anthropic
    url: http://localhost:1234/v1     # API endpoint
    model: ""                         # Model name (optional for LM Studio)
    # api_key: ""                     # Required for OpenAI/Anthropic

  # Signal integration settings
  signal:
    phone: "+1234567890"              # Your Signal phone number
    config_dir: ~/.config/fraggle/signal
    trigger: "@fraggle"               # Prefix to trigger bot in groups
    # signal_cli_path: /usr/local/bin/signal-cli
    respond_to_direct_messages: true

  # Memory storage settings
  memory:
    base_dir: ./data/memory

  # Sandbox settings
  sandbox:
    type: permissive                  # permissive, docker, gvisor
    work_dir: ./data/workspace

  # Agent settings
  agent:
    # system_prompt: "Custom system prompt"
    temperature: 0.7
    max_tokens: 4096
    max_iterations: 10
    max_history_messages: 20

  # Registered chats (optional)
  chats:
    registered: []
    # - id: "group-abc123"
    #   name: "Dev Team"
    #   trigger_override: "@bot"
    #   enabled: true
```

## Running

### Development Mode

The default Gradle run task sets `FRAGGLE_ROOT` to `runtime-dev/` for development:

```bash
# Run the full service with Signal integration
./gradlew :app:run --args="run"

# Run with a custom config file
./gradlew :app:run --args="run -c /path/to/config.yaml"
```

### Interactive Chat Mode

Test the agent without Signal integration:

```bash
# Interactive chat with default config
./gradlew :app:run --args="chat"

# Interactive chat with custom config
./gradlew :app:run --args="chat -c /path/to/config.yaml"

# Interactive chat with model override
./gradlew :app:run --args="chat -m gpt-4"
```

### Production Mode

For production, set `FRAGGLE_ROOT` to your desired location:

```bash
export FRAGGLE_ROOT=/opt/fraggle
java -jar app/build/libs/app.jar run
```

Or build a distribution:

```bash
./gradlew :app:installDist
FRAGGLE_ROOT=/opt/fraggle ./app/build/install/app/bin/app run
```

## Directory Structure

When running, Fraggle creates the following directory structure under `FRAGGLE_ROOT`:

```
$FRAGGLE_ROOT/
├── config/
│   └── fraggle.yaml        # Configuration file
├── data/
│   ├── memory/             # Conversation memory storage
│   └── workspace/          # Sandbox working directory
└── logs/
    └── fraggle.log         # Application logs
```

## Built-in Skills

Fraggle comes with several built-in skill groups:

### Filesystem Skills
- `read_file` - Read file contents
- `write_file` - Write content to a file
- `append_file` - Append content to a file
- `list_files` - List directory contents
- `search_files` - Search for files by pattern
- `file_exists` - Check if a file exists
- `delete_file` - Delete a file

### Web Skills
- `fetch_url` - Fetch content from a URL (sandboxed)
- `fetch_url_raw` - Fetch URL without sandbox restrictions
- `send_image` - Download an image from a URL and send it to the chat
- `fetch_rendered_page` - Fetch a page using Playwright (requires Playwright config)
- `screenshot_page` - Take a screenshot of a web page (requires Playwright config)

### Shell Skills
- `execute_command` - Execute shell commands (sandboxed)

### Scheduling Skills
- Task scheduling for delayed/recurring operations

## Signal Integration

### Setup signal-cli

1. Install [signal-cli](https://github.com/AsamK/signal-cli)
2. Register or link your phone number:
   ```bash
   signal-cli -u +1234567890 register
   signal-cli -u +1234567890 verify CODE
   ```
3. Configure the phone number in `fraggle.yaml`

### Trigger Behavior

- **Direct messages**: Fraggle responds to all direct messages (if `respond_to_direct_messages: true`)
- **Group messages**: Fraggle responds only when the message starts with the trigger prefix (default: `@fraggle`)

### Text Formatting

Fraggle supports Signal's text formatting. The LLM can use simple markdown-like syntax which is automatically converted to Signal's native formatting:

| Syntax              | Result                        |
|---------------------|-------------------------------|
| `**bold**`          | **Bold**                      |
| `*italic*`          | *Italic*                      |
| `~~strikethrough~~` | ~~Strikethrough~~             |
| `\|\|spoiler\|\|`   | Spoiler (hidden until tapped) |
| `` `monospace` ``   | `Monospace`                   |

Note: Markdown links and images are NOT supported - images must be sent as attachments.

### Registered Chats

You can configure specific chats with custom settings:

```yaml
chats:
  registered:
    - id: "group-abc123"
      name: "Dev Team"
      trigger_override: "@bot"    # Custom trigger for this chat
      enabled: true
```

## Playwright Integration (Optional)

For JavaScript-heavy websites, Fraggle can use Playwright to render pages in a real browser before extracting content. This enables:
- Fetching content from single-page applications (React, Vue, Angular)
- Taking screenshots of fully-rendered pages
- Working with lazy-loaded content

### Setup

1. Run a Playwright-compatible browser server. Options include:

   **Using Browserless (recommended for Docker):**
   ```bash
   docker run -p 3000:3000 browserless/chrome
   ```

   **Using Playwright's built-in server:**
   ```bash
   npx playwright run-server --port 3000
   ```

2. Configure the WebSocket endpoint in `fraggle.yaml`:
   ```yaml
   fraggle:
     web:
       playwright:
         ws_endpoint: ws://localhost:3000/playwright
         navigation_timeout: 30000
         wait_after_load: 2000
   ```

### Configuration Options

| Option               | Description                             | Default           |
|----------------------|-----------------------------------------|-------------------|
| `ws_endpoint`        | WebSocket URL for the Playwright server | (required)        |
| `navigation_timeout` | Page load timeout in milliseconds       | 30000             |
| `wait_after_load`    | Extra wait time for JS to settle (ms)   | 2000              |
| `viewport_width`     | Browser viewport width                  | 1280              |
| `viewport_height`    | Browser viewport height                 | 720               |
| `user_agent`         | Custom user agent string                | (browser default) |

### Skills Enabled

When Playwright is configured, these additional skills become available:
- `fetch_rendered_page` - Fetch a page with full JavaScript rendering
- `screenshot_page` - Capture a screenshot of a rendered page

## Logging

Logs are written to `$FRAGGLE_ROOT/logs/fraggle.log` with daily rotation (30 days retained).

Log levels can be adjusted in `app/src/main/resources/logback.xml`.

## License

Published under the MIT License, see [LICENSE](LICENSE).
