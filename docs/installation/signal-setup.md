# Signal Setup

This guide covers setting up Signal integration for Fraggle.

## Prerequisites

- A phone number to register with Signal
- **signal-cli** (automatically installed by Fraggle, or manually installed)

## Installing signal-cli

### Automatic Installation (Recommended)

Fraggle can automatically download and install signal-cli when it's not found in your system PATH. This is enabled by default and requires no manual setup.

When you first run Fraggle with Signal bridge enabled, it will:

1. Check if signal-cli is available in your system PATH
2. If not found, download the appropriate version to `data/apps/signal-cli-{version}/`
3. On Linux: Downloads the native binary for better performance
4. On macOS/Windows: Downloads the Java-based version

You can control this behavior with the `auto_install` and `signal_cli_version` configuration options.

### Manual Installation

If you prefer to install signal-cli manually, or if automatic installation fails:

#### macOS (Homebrew)

```bash
brew install signal-cli
```

#### Linux (Native Binary)

Download the native binary for better performance:

```bash
# Example for version 0.14.2
wget https://github.com/AsamK/signal-cli/releases/download/v0.14.2/signal-cli-0.14.2-Linux-native.tar.gz
tar xf signal-cli-0.14.2-Linux-native.tar.gz
sudo mv signal-cli /usr/local/bin/signal-cli
```

#### Linux/macOS (Java Version)

If you prefer the Java-based version:

```bash
# Example for version 0.14.2
wget https://github.com/AsamK/signal-cli/releases/download/v0.14.2/signal-cli-0.14.2.tar.gz
tar xf signal-cli-0.14.2.tar.gz
sudo mv signal-cli-0.14.2 /opt/signal-cli
sudo ln -s /opt/signal-cli/bin/signal-cli /usr/local/bin/signal-cli
```

## Registering a Phone Number

### Option 1: New Registration

Register a new phone number with Signal:

```bash
# Request verification code via SMS
signal-cli -u +1234567890 register

# Or request via voice call
signal-cli -u +1234567890 register --voice

# Verify with the code you received
signal-cli -u +1234567890 verify CODE
```

### Option 2: Link to Existing Account

Link signal-cli as a secondary device to an existing Signal account:

```bash
signal-cli link -n "Fraggle Bot"
```

This displays a QR code or URI. Scan it with the Signal app on your phone:

1. Open Signal on your phone
2. Go to Settings > Linked Devices
3. Tap "Link New Device"
4. Scan the QR code

## Configuration

Add Signal configuration to your `fraggle.yaml`:

```yaml
fraggle:
  bridges:
    signal:
      enabled: true
      phone: "+1234567890"            # Your registered number
      config_dir: ~/.config/fraggle/signal
      trigger: "@fraggle"             # Trigger for group messages
      respond_to_direct_messages: true
      show_typing_indicator: true
      # Auto-installation settings (optional)
      auto_install: true              # Auto-download signal-cli if not found
      signal_cli_version: "0.14.2"   # Version to download
```

!!! tip "Automatic Installation"
    With `auto_install: true` (the default), you don't need to install signal-cli manually. Fraggle will download it automatically on first run if it's not found in your system PATH.

### Configuration Options

| Option                        | Description                                    | Default                    |
|-------------------------------|------------------------------------------------|----------------------------|
| `phone`                       | Registered Signal phone number (with country code) | Required                |
| `enabled`                     | Whether Signal bridge is active                | `true` if phone is set     |
| `config_dir`                  | Directory for Signal configuration             | `~/.config/fraggle/signal` |
| `trigger`                     | Prefix to trigger bot in group chats           | `@fraggle`                 |
| `signal_cli_path`             | Path to signal-cli (null = use PATH or auto-install) | `null`                |
| `auto_install`                | Automatically download signal-cli if not found | `true`                     |
| `signal_cli_version`          | Version of signal-cli to auto-install          | `0.14.2`                  |
| `respond_to_direct_messages`  | Respond to DMs without trigger                 | `true`                     |
| `show_typing_indicator`       | Show typing indicator while processing         | `true`                     |

## Trigger Behavior

### Direct Messages

When `respond_to_direct_messages` is `true`, Fraggle responds to all direct messages automatically.

### Group Messages

In group chats, Fraggle only responds when the message starts with the trigger prefix:

```
@fraggle what's the weather like?
```

Set `trigger` to `null` to respond to all group messages (use with caution).

### Per-Chat Triggers

You can override the trigger for specific chats:

```yaml
fraggle:
  bridges:
    signal:
      trigger: "@fraggle"
  chats:
    registered:
      - id: "group-abc123"
        name: "Dev Team"
        trigger_override: "@bot"    # Use @bot in this group
```

## Text Formatting

Fraggle supports Signal's text formatting. The LLM can use markdown-like syntax that's automatically converted:

| Syntax              | Result                        |
|---------------------|-------------------------------|
| `**bold**`          | **Bold**                      |
| `*italic*`          | *Italic*                      |
| `~~strikethrough~~` | ~~Strikethrough~~             |
| `\|\|spoiler\|\|`   | Spoiler (hidden until tapped) |
| `` `monospace` ``   | `Monospace`                   |

!!! warning "Unsupported Formatting"
    Markdown links (`[text](url)`) and images (`![alt](url)`) are NOT supported in Signal messages. Images must be sent as attachments using the `send_image` tool.

## Troubleshooting

### "Failed to connect to Signal"

1. Verify signal-cli is installed: `signal-cli --version`
2. Check the phone number is registered: `signal-cli -u +1234567890 receive`
3. Ensure `config_dir` exists and is writable

### "Messages not being received"

1. Run in interactive mode first to verify the agent works: `./bin/fraggle chat`
2. Check logs at `$FRAGGLE_ROOT/logs/fraggle.log`
3. Try receiving messages manually: `signal-cli -u +1234567890 receive`

### "Permission denied" errors

Ensure the user running Fraggle has:

- Read/write access to `config_dir`
- Execute permission on `signal-cli`

### Auto-installation issues

If automatic installation of signal-cli fails:

1. Check internet connectivity - Fraggle needs to download from GitHub releases
2. Verify write permissions to `data/apps/` directory
3. Check logs for specific download or extraction errors
4. On Linux, ensure `tar` command is available for extraction
5. Try manual installation instead (see above)

To disable auto-installation and use a manually installed version:

```yaml
fraggle:
  bridges:
    signal:
      auto_install: false
      signal_cli_path: /path/to/signal-cli  # Optional: specify explicit path
```

### Updating signal-cli version

To use a different version of signal-cli:

```yaml
fraggle:
  bridges:
    signal:
      signal_cli_version: "0.13.24"  # Specify desired version
```

Fraggle will download the new version on next startup. Old versions in `data/apps/` can be manually deleted.

## Security Considerations

- The Signal configuration directory contains sensitive authentication data
- Restrict access to `config_dir` (e.g., `chmod 700`)
- Consider running Fraggle as a dedicated user
- The bot can access messages in any chat it's added to - be mindful of group memberships
