# Coding Agent

`fraggle code` is a terminal coding agent that runs a ReAct-style agent loop against a local or cloud LLM and gives it read/write access to your project. It sits alongside the messenger assistant (`fraggle run` / `fraggle chat`) and shares the same provider configuration, so one LM Studio setup powers both.

The coding agent is built on the same agent-loop, compaction, and tool-supervision infrastructure as the messenger assistant, but with a different shape: project-scoped sessions as JSONL files with tree branching, `AGENTS.md` context files walked from the project root, and a terminal UI instead of a chat bridge.

## Quick Start

```bash
# Start a fresh session in the current directory
fraggle code

# Ask a one-shot question with an initial prompt
fraggle code "what does src/main/kotlin/Main.kt do?"

# Include a file in the initial prompt
fraggle code @README.md "summarize this"

# Resume the most recent session for this project
fraggle code -c

# Disable tool approval prompts (auto-approve all tool calls)
fraggle code --supervision none
```

The first time you run `fraggle code` in a project, a new session file is created under `$FRAGGLE_ROOT/coding/sessions/<project-hash>/<uuid>.jsonl`. Subsequent runs in the same directory can resume that session with `-c`/`--continue` or browse prior sessions with `-r`/`--resume`.

## CLI Reference

```
fraggle code [<options>] [<message>]...
```

### Session options

| Flag | Description |
|---|---|
| `-c`, `--continue` | Resume the most recent session for the current project. |
| `-r`, `--resume` | Resume the most recent session (alias of `--continue` in MVP; a selectable picker is future work). |
| `--session <path>` | Open a specific session file by path. |
| `--fork <path>` | Fork a specific session file into a new session in the same project. |
| `--no-session` | Ephemeral mode — start fresh, don't chain off any existing session. |

The session flags are mutually exclusive; passing more than one is an error.

### Model and workspace

| Flag | Description |
|---|---|
| `--model <id>` | Override the model id from `fraggle.yaml` and `settings.json`. |
| `--workdir <path>` | Override the working directory. All tool calls and context-file walks start here. |

### Tool selection

| Flag | Description |
|---|---|
| `--tools <list>` | Comma-separated list of tools to enable. Example: `--tools read_file,grep,edit_file`. |
| `--no-tools` | Disable all built-in tools. The agent becomes pure conversation. |

### System prompt

| Flag | Description |
|---|---|
| `--system-prompt <text>` | Replace the default system prompt entirely with the given text. |
| `--append-system-prompt <text>` | Append text to whichever system prompt is active. |

### Supervision

| Flag | Description |
|---|---|
| `--supervision <ask\|none>` | `ask` prompts for approval on every tool call (default). `none` auto-approves all tool calls — use this in containers or other sandboxed environments. |

### Positional arguments

Any text after the options becomes the initial user message. Arguments that start with `@` are treated as file paths and their contents are inlined into the message as a code block:

```bash
fraggle code @src/Foo.kt @src/Bar.kt "these two files look similar — should they be merged?"
```

## Interactive Mode

Once `fraggle code` launches the TUI, you get a live message list, a multi-line editor, and a status footer.

### Layout

```
┌─────────────────────────────────────────────────────────────┐
│  fraggle code  │  ?: /hotkeys  │  3 context files  │  ...  │  ← Header
├─────────────────────────────────────────────────────────────┤
│  » what's in foo.kt?                                        │
│                                                             │
│  ◆ I'll read the file                                       │
│    └─ read_file {"path":"foo.kt"}                           │
│         ← read_file: fun main() { println("hi") }           │
│  ◆ The file defines a main function that prints hello.     │
│                                                             │  ← MessageList
├─────────────────────────────────────────────────────────────┤
│  > your next message here_                                  │
│                                                             │  ← Editor
├─────────────────────────────────────────────────────────────┤
│ ~/proj │ 8f3c1a │ 128 tok │ 2% ctx │ idle  │ qwen3-coder    │  ← Footer
└─────────────────────────────────────────────────────────────┘
```

- **»** marks user messages.
- **◆** marks assistant turns. Tool calls appear as indented bullets beneath the assistant that emitted them.
- **←** marks successful tool results; **✗** marks errors.

### Keyboard shortcuts

| Key | Action |
|---|---|
| `Enter` | Submit the current message. |
| `Shift+Enter` | Insert a newline without submitting. |
| `Backspace` / `Delete` | Delete character behind / at cursor. |
| `←` / `→` | Cursor left / right. |
| `↑` / `↓` | Move cursor up / down between lines (column preserved when possible). |
| `Home` / `End` | Start / end of the current line. |
| `Escape` | If the agent is running: abort the current turn. If the editor has text: clear it. Otherwise: exit (pressing twice confirms). |
| `Ctrl+C` | Clear the editor if it has text, otherwise exit. |
| `y` / `n` | Approve / deny a pending tool call (only when the approval overlay is visible). |

### Tool approval (ask mode)

When `supervision = ask` (the default), the TUI shows an approval overlay whenever the agent wants to run a tool:

```
╭─── tool approval ───────────────────────────────────────╮
│ tool: edit_file
│ args: {"path":"src/Foo.kt","old_string":"x","new_string":"y"}
│ approve? [y] yes   [n] no
╰─────────────────────────────────────────────────────────╯
```

Press `y` to let the tool call run or `n` (or `Escape`) to deny. The agent receives the denial as a tool error and usually asks you what to do instead.

To run without prompts, start with `--supervision none` or set `"supervision": "none"` in `settings.json`.

## Sessions

Sessions are JSONL files with a tree structure. Every entry has an `id` and an optional `parentId`, so multiple branches can share a prefix. This is how `/tree` navigation and forking work without creating new files for every branch point.

### Where sessions live

```
$FRAGGLE_ROOT/coding/sessions/
  <project-hash>/               # 12-char sha1 of the absolute project path
    <session-uuid>.jsonl
    <session-uuid>.jsonl
```

The project hash isolates each project's sessions so `fraggle code -r` in one directory can't show you sessions from another.

### Resuming

```bash
fraggle code -c              # most recent session for this project
fraggle code --session <path>  # specific session file
fraggle code --fork <path>   # copy a session into a new file
fraggle code --no-session    # ignore any existing sessions, start fresh
```

On resume, the agent loads the current branch from the session file and replays it as the initial conversation history. Tool calls, tool results, and token usage are all preserved.

### JSONL format

Every line is a JSON object with:

```json
{
  "id": "uuid",
  "parentId": "uuid or null",
  "timestampMs": 1713000000000,
  "payload": { "kind": "user" | "assistant" | "tool_result" | "meta" | "root", ... },
  "schemaVersion": 1
}
```

The first entry (`kind: root`) carries session metadata (`sessionId`, `projectRoot`, `model`, `createdAtMs`). Subsequent entries chain off it via `parentId`.

You can inspect a session file directly with any JSON-aware tool (`jq`, `less`, an editor) — the format is designed to be human-readable.

### Compaction

Long sessions can exhaust the LLM's context window. When context usage crosses the compaction threshold (default 70%, configurable via `compactionTriggerRatio`), the agent:

1. Summarizes the older half of the conversation via a dedicated LLM call.
2. Replaces the in-memory history with `[synthetic summary message] + recent turns verbatim`.
3. Writes a `Meta(label="compaction")` entry to the session file with the full summary text.

**Compaction is lossy for the working conversation but the session file keeps the full original history.** Future work (`/tree`) will let you browse back to any point before compaction.

Tune via `settings.json`:

```json
{
  "contextWindowTokens": 32000,
  "compactionTriggerRatio": 0.70,
  "compactionKeepRecentMessages": 12
}
```

When `contextWindowTokens` is 0 (unknown), the ratio-based policy silently disables itself and no proactive compaction happens.

## Context files (`AGENTS.md`)

The coding agent loads project-specific guidance from `AGENTS.md` files found in:

1. `$FRAGGLE_ROOT/coding/AGENTS.md` — global rules that apply to every project.
2. Every directory from the project root (nearest `.git` ancestor) down to the current working directory.

Files are concatenated into the system prompt in **outer → inner** order, so the nearest `AGENTS.md` to your `cwd` appears last and takes precedence semantically.

If an `AGENTS.md` is absent in a given directory, the agent falls back to `CLAUDE.md` in that directory for compatibility with Claude Code-style projects.

### Example

```
~/projects/webapp/
├── .git/
├── AGENTS.md              # root project rules
├── backend/
│   ├── AGENTS.md          # rules specific to backend work
│   └── src/
└── frontend/
    └── src/
```

Running `fraggle code` from `~/projects/webapp/backend/src` picks up both `~/projects/webapp/AGENTS.md` and `~/projects/webapp/backend/AGENTS.md`, in that order.

## System prompt layering

The final system prompt is composed in this order (top to bottom):

1. **Base prompt** — the first one found from:
   - `--system-prompt <text>` flag
   - `<cwd>/.fraggle/coding/SYSTEM.md` (project override)
   - `$FRAGGLE_ROOT/coding/SYSTEM.md` (global override)
   - bundled default (`CODING_SYSTEM.md` inside the jar)
2. **Workspace snapshot** — cwd, git branch/HEAD/status (best-effort; skipped in non-git directories).
3. **`AGENTS.md` content** — global first, then outer-to-inner project files.
4. **Available templates** — a list of `/name` entries from loaded prompt templates (see below).
5. **Append text** — the concatenation of:
   - `<cwd>/.fraggle/coding/APPEND_SYSTEM.md` (project)
   - `$FRAGGLE_ROOT/coding/APPEND_SYSTEM.md` (global)
   - `--append-system-prompt <text>` flag

Sections with no content are skipped entirely, so running in a non-git directory with no `AGENTS.md` produces a clean prompt without empty placeholder headers.

## Prompt templates

Reusable prompt templates live as Markdown files in:

- `$FRAGGLE_ROOT/coding/prompts/*.md` (global)
- `<cwd>/.fraggle/coding/prompts/*.md` (project)

Each file becomes a `/name` command where `name` is the filename without the extension (lowercased). Templates may contain `{{variable}}` placeholders:

```markdown
<!-- ~/.fraggle/coding/prompts/review.md -->
Review {{path}} focusing on:
- thread safety
- error handling
- performance hot spots
```

The template is advertised to the model in the system prompt (so it knows `/review` is available) but not expanded until the user actually types `/review`. Variable substitution is literal; unknown variables are left as-is so you can see something went wrong.

## Tools

The coding agent ships with these tools by default:

| Tool | Description |
|---|---|
| `read_file` | Read a file with optional line range. |
| `write_file` | Overwrite a file. Supervised by default. |
| `append_file` | Append to a file. Supervised by default. |
| `edit_file` | Exact-string replacement (Claude Code style). Requires `old_string` to match exactly once in the file unless `replace_all = true`. |
| `list_files` | `ls`-equivalent directory listing. |
| `search_files` | Glob-based file search. |
| `file_exists` | Check if a path exists. |
| `delete_file` | Delete a file. Supervised by default. |
| `execute_command` | Run a shell command in the project directory. Supervised by default. |
| `fetch_webpage` | HTTP GET with optional Playwright rendering. |
| `fetch_api` | HTTP request returning raw response. |
| `get_current_time` | Current time in any timezone. |

### `edit_file` semantics

`edit_file` is the primary way the agent modifies source. It's an exact-string replacement:

- `old_string` must match byte-for-byte, including whitespace and newlines.
- Zero matches → error asking the agent to re-read the file.
- Multiple matches without `replace_all` → error asking for more surrounding context to disambiguate.
- Multiple matches with `replace_all: true` → every occurrence replaced.
- `old_string == new_string` → no-op with a clear message.

This is intentionally strict: a model that's careless about context will produce errors the user sees, rather than silent corruption.

### Disabling tools

```bash
# Only read-only tools
fraggle code --tools read_file,search_files,list_files,grep

# No tools at all
fraggle code --no-tools
```

## Configuration

### Shared config: `$FRAGGLE_ROOT/config/fraggle.yaml`

The LLM provider is configured once for all Fraggle apps:

```yaml
fraggle:
  provider:
    type: lmstudio
    url: http://localhost:1234/v1
    model: qwen3-coder-30b-a3b
    api_key: null
```

`fraggle code`, `fraggle run`, and `fraggle chat` all read the same `provider` section.

### Coding-agent settings: `$FRAGGLE_ROOT/coding/settings.json`

Coding-agent-specific behaviour lives in a JSON file:

```json
{
  "supervision": "ask",
  "contextWindowTokens": 32000,
  "compactionTriggerRatio": 0.70,
  "compactionKeepRecentMessages": 12,
  "maxIterations": 20,
  "model": null
}
```

All fields are optional. Missing fields fall back to built-in defaults. Unknown fields are ignored so settings files forward-compatibly survive Fraggle upgrades.

### Project overrides: `<cwd>/.fraggle/coding/settings.json`

Drop a `settings.json` in your project's `.fraggle/coding/` directory to override globals for that project only. Project values win, field by field. Fields you don't set fall through to the global settings, then to the defaults.

### CLI overrides

Command-line flags win over every file:

```
defaults  →  global settings.json  →  project settings.json  →  CLI flags
```

## Directory layout summary

```
$FRAGGLE_ROOT/
├── config/
│   └── fraggle.yaml               # shared provider config
├── data/                          # messenger assistant (memory, db)
└── coding/
    ├── settings.json              # global coding-agent settings
    ├── AGENTS.md                  # global AGENTS.md
    ├── SYSTEM.md                  # optional global system prompt override
    ├── APPEND_SYSTEM.md           # optional global append
    ├── prompts/                   # global /name templates
    │   ├── review.md
    │   └── refactor.md
    └── sessions/
        └── <project-hash>/
            ├── <uuid>.jsonl
            └── <uuid>.jsonl

<project-root>/
└── .fraggle/
    └── coding/
        ├── settings.json          # project-specific settings
        ├── SYSTEM.md              # project system prompt override
        ├── APPEND_SYSTEM.md       # project append
        └── prompts/               # project-specific templates
```

## Differences from pi-coding-agent

Fraggle's coding agent is modeled on [pi](https://github.com/badlogic/pi-mono/tree/main/packages/coding-agent) but intentionally smaller in scope for MVP.

**Present in Fraggle:**

- JSONL session files with tree structure (identical format concept)
- `AGENTS.md` context files walked cwd → git root
- Compaction with cumulative summaries
- Exact-string `edit_file` modeled on Claude Code's Edit tool
- Supervision modes (`ask` / `none`)
- Prompt templates with variable substitution
- Slash commands (`/new`, `/quit`, `/hotkeys`, `/session`)
- `@file` positional arguments
- Steering and follow-up message queues (inherited from the core agent loop)

**Deferred to later phases:**

- `/tree` interactive branch navigator
- Multi-provider support (Fraggle only speaks LM Studio today)
- Subscription OAuth (Anthropic Pro, GitHub Copilot, etc.)
- Agent skills (under construction in parallel — will land for both the messenger assistant and the coding agent together)
- Extensions / Kotlin Scripting modules
- Pi-package-style installation via git/npm
- `/share` gist upload and HTML export
- Thinking-level shorthand
- RPC and JSON output modes
- Image paste / drag-drop attachments
- Git checkpointing and auto-commit
- `--print` / `-p` non-interactive mode

## Troubleshooting

### "No model configured"

Set `provider.model` in `fraggle.yaml`, or pass `--model <id>` on the command line, or set `"model": "..."` in your coding-agent `settings.json`.

### The editor feels unresponsive

The TUI locks out typing while the agent is processing a turn. Watch the footer status — `thinking...` means the agent is running; `idle` means the editor will accept input. If the agent is stuck, press `Escape` to abort the current turn.

### Compaction fires too often / not often enough

Compaction is gated on both the policy AND the context-usage ratio. If you haven't set `contextWindowTokens` in `settings.json`, the ratio is always 0.0 and compaction never fires proactively. Set it to your model's actual context window size (e.g., `32000` for a 32K-context model) to enable ratio-based compaction.

To disable compaction entirely, set `compactionKeepRecentMessages` larger than any session you expect, or (future work) add a `never` compaction policy.

### I edited an `AGENTS.md` and the agent doesn't see the change

`AGENTS.md` files are loaded at startup. Exit with `Escape` and run `fraggle code -c` to pick up the change.

### Session file is corrupt

Malformed JSONL lines surface a clear error with the offending line number when the session is loaded. You can manually edit the file to fix the bad line, or delete the whole session file and start fresh — the coding agent never rewrites past entries, so edits and truncations are safe.

## See also

- [Installation](installation/getting-started.md)
- [Configuration reference](installation/configuration.md)
- [Architecture overview](architecture/overview.md)
- [Agent system (shared with messenger assistant)](architecture/agent.md)
- [Tools reference](architecture/tools.md)
