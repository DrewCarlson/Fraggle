# Signal Setup

This guide covers setting up Signal integration for Fraggle.

## Prerequisites

- [signal-cli](https://github.com/AsamK/signal-cli) installed
- A phone number to register with Signal

## Installing signal-cli

### macOS (Homebrew)

```bash
brew install signal-cli
```

### Linux

Download the latest release from [signal-cli releases](https://github.com/AsamK/signal-cli/releases):

```bash
# Example for version 0.13.0
wget https://github.com/AsamK/signal-cli/releases/download/v0.13.0/signal-cli-0.13.0.tar.gz
tar xf signal-cli-0.13.0.tar.gz
sudo mv signal-cli-0.13.0 /opt/signal-cli
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
```

### Configuration Options

| Option                        | Description                                    | Default                    |
|-------------------------------|------------------------------------------------|----------------------------|
| `phone`                       | Registered Signal phone number (with country code) | Required                |
| `enabled`                     | Whether Signal bridge is active                | `true` if phone is set     |
| `config_dir`                  | Directory for Signal configuration             | `~/.config/fraggle/signal` |
| `trigger`                     | Prefix to trigger bot in group chats           | `@fraggle`                 |
| `signal_cli_path`             | Path to signal-cli (null = use PATH)           | `null`                     |
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
    Markdown links (`[text](url)`) and images (`![alt](url)`) are NOT supported in Signal messages. Images must be sent as attachments using the `send_image` skill.

## Troubleshooting

### "Failed to connect to Signal"

1. Verify signal-cli is installed: `signal-cli --version`
2. Check the phone number is registered: `signal-cli -u +1234567890 receive`
3. Ensure `config_dir` exists and is writable

### "Messages not being received"

1. Run in interactive mode first to verify the agent works: `./gradlew :app:run --args="chat"`
2. Check logs at `$FRAGGLE_ROOT/logs/fraggle.log`
3. Try receiving messages manually: `signal-cli -u +1234567890 receive`

### "Permission denied" errors

Ensure the user running Fraggle has:

- Read/write access to `config_dir`
- Execute permission on `signal-cli`

## Security Considerations

- The Signal configuration directory contains sensitive authentication data
- Restrict access to `config_dir` (e.g., `chmod 700`)
- Consider running Fraggle as a dedicated user
- The bot can access messages in any chat it's added to - be mindful of group memberships
