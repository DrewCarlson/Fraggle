# Discord Setup

This guide covers setting up Discord integration for Fraggle.

## Overview

Fraggle's Discord integration is designed for **personal use** - the bot is installed directly to your Discord user account, not to servers. This means:

- You communicate with your bot instance privately via DMs
- Your bot is not shared with other users
- No server administrator permissions are required
- Your conversations remain private

!!! note "Server Installation"
    Multi-user/server installation is not currently supported. Each user runs their own Fraggle instance with their own bot.

## Prerequisites

- A Discord account
- A Discord application with a bot token (see below for instructions)

## Creating a Discord Bot

### Step 1: Create an Application

1. Go to the [Discord Developer Portal](https://discord.com/developers/applications)
2. Click **"New Application"**
3. Enter a name for your bot (e.g., "My Fraggle Bot")
4. Accept the Terms of Service and click **"Create"**

### Step 2: Configure the Bot

1. In your application, go to the **"Bot"** section in the left sidebar
2. Click **"Reset Token"** to generate a new token
3. **Copy and save the token** - you won't be able to see it again!
4. Under **"Privileged Gateway Intents"**, enable:
    - **Message Content Intent** (required to read message text)

!!! warning "Keep Your Token Secret"
    Never share your bot token or commit it to version control. Anyone with your token can control your bot.

### Step 3: Configure OAuth2 (Required for DMs)

To enable DM conversations, you need to configure OAuth2 so Fraggle can send you an initial welcome message.

1. Go to the **"OAuth2"** section in the left sidebar
2. Under **"Client Information"**:
    - Copy your **Client ID** (also visible at the top of the General Information page)
    - Click **"Reset Secret"** and copy your **Client Secret**
3. Under **"Redirects"**, add your callback URL:
    - For local development: `http://localhost:9191/api/v1/discord/oauth/callback`
    - For production: `https://your-domain/api/v1/discord/oauth/callback`

!!! note Only the Fraggle API is required for Discord support,
    the Dashboard does not need to be enabled.

!!! warning "Keep Your Client Secret Safe"
    Like the bot token, never share your client secret or commit it to version control.

### Step 4: Configure Installation Settings

1. Go to the **"Installation"** section in the left sidebar
2. Under **"Installation Contexts"**, configure for user installation:
    - ✅ Enable **"User Install"**
    - ❌ Disable **"Guild Install"** (optional, but recommended for personal use)
3. Under **"Default Install Settings"**:
    - For **"User Install"**, add the scopes:
        - `applications.commands` (Added by default)

### Step 5: Install the Bot via OAuth2

1. Start Fraggle with your Discord configuration (see below)
2. Navigate to the Dashboard **Chat Bridges** section or open the `http://localhost:9191/api/v1/discord/oauth/authorize` API endpoint
3. Discord will redirect you to authorize the application
4. Select **"Install to your account"**
5. Click **"Authorize"**
6. You'll be redirected back to Fraggle, and a welcome DM will be sent to you

You will receive a DM from your bot telling you everything is complete.

## Configuration

Add Discord configuration to your `fraggle.yaml`:

```yaml
fraggle:
  bridges:
    discord:
      enabled: true
      token: "your-bot-token-here"           # From Step 2 above
      client_secret: "your-client-secret"    # From Step 3 (OAuth2)
      oauth_redirect_uri: "http://localhost:9191/api/v1/discord/oauth/callback"
      trigger: "!fraggle"                    # Trigger prefix (optional for DMs)
      respond_to_direct_messages: true
      show_typing_indicator: true
      max_images_per_message: 10             # Discord allows up to 10
      max_file_size_mb: 10                   # 10 free, 50 Nitro Basic, 500 Nitro
```

### Configuration Options

| Option                       | Description                                           | Default    |
|------------------------------|-------------------------------------------------------|------------|
| `token`                      | Discord bot token (required)                          | -          |
| `enabled`                    | Whether Discord bridge is active                      | `true` if token is set |
| `client_id`                  | OAuth2 client ID (extracted from token if not set)    | -          |
| `client_secret`              | OAuth2 client secret (required for DM setup)          | -          |
| `oauth_redirect_uri`         | OAuth2 callback URL (must match Developer Portal)     | -          |
| `trigger`                    | Prefix to trigger bot (null = respond to all)         | `!fraggle` |
| `respond_to_direct_messages` | Respond to DMs without trigger                        | `true`     |
| `show_typing_indicator`      | Show typing indicator while processing                | `true`     |
| `max_images_per_message`     | Maximum images per message (Discord limit: 10)        | `10`       |
| `max_file_size_mb`           | Max file size in MB (see limits below)                | `10`       |
| `allowed_channel_ids`        | Only respond in these channels (empty = all)          | `[]`       |
| `allowed_guild_ids`          | Only respond in these guilds (empty = all)            | `[]`       |

### File Size Limits

Discord has different file upload limits based on your subscription:

| Plan         | Max File Size |
|--------------|---------------|
| Free         | 10 MB         |
| Nitro Basic  | 50 MB         |
| Nitro        | 500 MB        |

Set `max_file_size_mb` according to your Discord subscription level.

## Using Your Bot

### Direct Messages

Once the OAuth2 flow is complete, your bot appears in your Discord DM list. Simply send a message:

```
Hello! What can you help me with?
```

When `respond_to_direct_messages` is `true` (the default), your bot responds to all DMs automatically without needing a trigger prefix.

### Using a Trigger Prefix

If you prefer to use a trigger prefix even in DMs:

```yaml
fraggle:
  bridges:
    discord:
      respond_to_direct_messages: false
      trigger: "!ask"
```

Then message your bot with:

```
!ask What's the weather like today?
```

## Sending Images

Fraggle supports sending images in Discord messages. You can include images in two ways:

### Inline Image Syntax

Use the `[[image:URL]]` syntax to include images directly in responses:

```
Here's a cute cat picture: [[image:https://example.com/cat.jpg]]
```

Discord supports **up to 10 images per message**, and Fraggle will automatically combine them.

### Screenshot Tool

Use the `screenshot_page` tool (requires Playwright) to capture and send website screenshots:

```
Can you show me what google.com looks like?
```

## Text Formatting

Discord supports rich markdown formatting. The LLM can use:

| Syntax              | Result                         |
|---------------------|--------------------------------|
| `**bold**`          | **Bold**                       |
| `*italic*`          | *Italic*                       |
| `~~strikethrough~~` | ~~Strikethrough~~              |
| `\|\|spoiler\|\|`   | Spoiler (hidden until clicked) |
| `` `code` ``        | `Inline code`                  |
| ` ```code``` `      | Code block                     |
| `> quote`           | Block quote                    |
| `# Heading`         | Large heading                  |
| `- item`            | Bullet list                    |

## Troubleshooting

### "Invalid bot token"

1. Verify the token is correct in your `fraggle.yaml`
2. Make sure you copied the full token from the Developer Portal
3. Try resetting the token and using the new one

### "Message Content Intent not enabled"

1. Go to your bot's settings in the Discord Developer Portal
2. Navigate to the **"Bot"** section
3. Enable **"Message Content Intent"** under Privileged Gateway Intents
4. Save changes and restart Fraggle

### Bot appears offline

1. Check that Fraggle is running with Discord bridge enabled
2. Verify the token is valid
3. Check the logs for connection errors: `$FRAGGLE_ROOT/logs/fraggle.log`

### Bot doesn't respond to messages

1. Ensure `respond_to_direct_messages: true` is set (for DMs)
2. Check if a trigger prefix is required and you're using it
3. Verify the bot is installed to your user account (check DM list)
4. Check logs for any errors during message processing

### Bot not appearing in DMs

User-installed Discord apps don't automatically appear in your DM list. You need to complete the OAuth2 flow:

1. Verify `client_secret` and `oauth_redirect_uri` are set in your config
2. Make sure the redirect URI in your config matches the one in Discord Developer Portal
3. Visit `http://localhost:9191/api/v1/discord/oauth/authorize`
4. Complete the authorization flow
5. Your bot should send you a welcome DM

### OAuth2 errors

**"Discord OAuth not configured"**

- Ensure `client_secret` and `oauth_redirect_uri` are set in your `fraggle.yaml`
- Restart Fraggle after changing configuration

**"Failed to exchange authorization code"**

- Verify your `client_secret` is correct
- Check that the redirect URI matches exactly (including trailing slashes)

**"Discord bot not connected"**

- The bot must be running and connected before completing OAuth2
- Check that your bot token is valid
- Look for connection errors in the logs

### "File too large" errors

Discord limits file uploads based on your subscription:

- **Free users**: 10 MB max
- **Nitro Basic**: 50 MB max
- **Nitro**: 500 MB max

Update your configuration to match:

```yaml
fraggle:
  bridges:
    discord:
      max_file_size_mb: 50  # For Nitro Basic
```

## Security Considerations

- **Never share your bot token or client secret** - anyone with these credentials can control your bot
- Store tokens and secrets securely (e.g., environment variables, secrets manager)
- The bot can read all messages in conversations it's part of
- Consider running Fraggle as a dedicated system user
- The OAuth2 redirect URI should use HTTPS in production environments
- Review Discord's [Terms of Service](https://discord.com/terms) and [Developer Terms](https://discord.com/developers/docs/policies-and-agreements/developer-terms-of-service)
