# Getting Started

This guide walks you through setting up Fraggle from scratch.

## Requirements

- **JDK 21+** - Fraggle requires Java 21 or later
- **LLM Provider** - One of:
    - [LM Studio](https://lmstudio.ai/) (local, recommended for development)
    - OpenAI API key
    - Anthropic API key
- **signal-cli** - Optional, for Signal chat bridge ([installation guide](https://github.com/AsamK/signal-cli))

## Installation

### 1. Download the Latest Release

```bash
wget https://github.com/DrewCarlson/Fraggle/releases/latest/download/fraggle.zip
unzip fraggle.zip
cd fraggle
```

### 2. Configure Fraggle

Run the interactive configuration wizard:

```bash
./bin/fraggle configure
```

This walks you through setting up:

- LLM provider (LM Studio, OpenAI, or Anthropic)
- Signal integration (optional)
- API server and dashboard (optional)

Alternatively, copy and edit the example configuration manually:

```bash
cp config/fraggle.example.yaml config/fraggle.yaml
```

See the [Configuration](configuration.md) guide for all options.

### 3. Test with Interactive Mode

Before setting up chat bridges, test that everything works:

```bash
./bin/fraggle chat
```

This starts an interactive chat session where you can test the agent directly.

## Running Modes

### Interactive Chat Mode

Test the agent without chat bridges:

```bash
# With default config
./bin/fraggle chat

# With custom config file
./bin/fraggle chat -c /path/to/config.yaml

# With model override
./bin/fraggle chat -m gpt-4
```

### Full Service Mode

Run with chat bridges enabled:

```bash
# With default config
./bin/fraggle run

# With custom config file
./bin/fraggle run -c /path/to/config.yaml
```

## Custom Installation Location

To install Fraggle in a specific location, set `FRAGGLE_ROOT`:

```bash
export FRAGGLE_ROOT=/opt/fraggle
./bin/fraggle run
```

## Directory Structure

When running, Fraggle creates this directory structure under `FRAGGLE_ROOT`:

```
$FRAGGLE_ROOT/
├── config/
│   ├── fraggle.yaml        # Main configuration
│   └── prompts/            # Prompt templates
│       ├── SYSTEM.md       # System prompt
│       ├── IDENTITY.md     # Agent identity/personality
│       └── USER.md         # User-specific instructions
├── data/
│   ├── memory/             # Conversation memory storage
│   └── workspace/          # Sandbox working directory
└── logs/
    └── fraggle.log         # Application logs (daily rotation)
```

## Next Steps

- [Configuration Reference](configuration.md) - Full configuration options
- [Signal Setup](signal-setup.md) - Connect to Signal messenger
- [Skills](../architecture/skills.md) - Available tools and custom skill development
