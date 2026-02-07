# Memory System

Fraggle includes a hierarchical memory system that persists facts across conversations. Memory is stored as human-readable markdown and integrated with Koog's agent memory system.

## Memory Scopes

Memory is organized into three scopes:

### Global Memory

Shared across all conversations and users. Useful for:

- General knowledge the agent should always have
- Configuration or preferences that apply universally
- Facts learned that benefit all users

**Storage path:** `$FRAGGLE_ROOT/data/memory/global.md`

### Chat Memory

Per-conversation memory. Useful for:

- Context about the specific chat or group
- Ongoing project information
- Chat-specific preferences

**Storage path:** `$FRAGGLE_ROOT/data/memory/chats/{chat_id}/memory.md`

### User Memory

Per-user memory that persists across chats. Useful for:

- User preferences and settings
- Personal information the user has shared
- History of user-specific interactions

**Storage path:** `$FRAGGLE_ROOT/data/memory/users/{user_id}/memory.md`

## Storage Format

Each memory file is a markdown list of facts with timestamps:

```markdown
# Memory

- Name: Alice [created: 2025-01-15T10:30:00Z]
- Hobbies: guitar, programming [created: 2025-01-15T10:30:00Z, updated: 2025-02-07T14:00:00Z]
- Lives in: Berlin [created: 2025-02-01T08:00:00Z]
```

Facts use a concise `Key: Value` format. Each fact tracks when it was created and, if modified, when it was last updated.

## Automatic Memory

When `auto_memory` is enabled (the default), the agent automatically extracts and manages facts after each conversation exchange:

1. **Extraction** - The LLM identifies new personal facts from the exchange, using concise `Key: Value` format and avoiding duplicates of already-stored facts
2. **Reconciliation** - If existing facts are present, the LLM reconciles new facts with old ones: merging related facts, updating superseded information, preserving history (e.g., "Previously worked at: Google"), and removing duplicates
3. **Persistence** - Facts are saved to the appropriate scope file with timestamps

This means the agent builds up knowledge about users over time without explicit "remember this" commands.

## Memory in Context

All three memory scopes are loaded and included in the agent's input for each request, giving the agent awareness of:

- Global knowledge
- Chat-specific context
- User preferences and history

## Configuration

```yaml
fraggle:
  memory:
    base_dir: ./data/memory
  agent:
    auto_memory: true  # Enable automatic fact extraction
```

| Option | Description | Default |
|--------|-------------|---------|
| `base_dir` | Directory where memory files are stored | `./data/memory` |
| `auto_memory` | Automatically extract facts from conversations | `true` |

## Dashboard

The web dashboard provides a memory management interface where you can:

- Browse all memory scopes and their fact counts
- Search and filter facts
- Edit individual facts inline
- Delete individual facts
- Clear all facts in a scope

## Best Practices

1. **Be Specific** - The agent stores specific facts in `Key: Value` format, not vague summaries
2. **Scope Appropriately** - User-specific facts go in User scope, project context in Chat scope
3. **Review Periodically** - Memory files are human-readable markdown and can be manually edited
4. **Fact Reconciliation** - The agent merges related facts automatically (e.g., two hobby mentions become one combined list)
