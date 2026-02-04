# Memory System

Fraggle includes a hierarchical memory system that persists information across conversations.

## Memory Scopes

Memory is organized into three scopes:

### Global Memory

Shared across all conversations and users. Useful for:

- General knowledge the agent should always have
- Configuration or preferences that apply universally
- Facts learned that benefit all users

**Storage path:** `$FRAGGLE_ROOT/data/memory/global/`

### Chat Memory

Per-conversation memory. Useful for:

- Context about the specific chat or group
- Ongoing project information
- Chat-specific preferences

**Storage path:** `$FRAGGLE_ROOT/data/memory/chats/{chat_id}/`

### User Memory

Per-user memory that persists across chats. Useful for:

- User preferences and settings
- Personal information the user has shared
- History of user-specific interactions

**Storage path:** `$FRAGGLE_ROOT/data/memory/users/{user_id}/`

## Storage Format

Memories are stored as human-readable markdown files:

```
data/memory/
├── global/
│   └── general.md
├── chats/
│   └── group-abc123/
│       └── project.md
└── users/
    └── +1234567890/
        └── preferences.md
```

### File Structure

Each memory file uses markdown format:

```markdown
# Project Notes

## Current Sprint
- Working on feature X
- Blocked on dependency Y

## Team Members
- Alice: Frontend
- Bob: Backend

---
Last updated: 2024-01-15
```

## Memory Operations

The agent can interact with memory through dedicated skills (when enabled):

### Reading Memory

The agent automatically retrieves relevant memory when building context for responses.

### Writing Memory

The agent can store new information when asked to remember something:

```
User: Remember that my favorite color is blue.
Agent: I'll remember that your favorite color is blue.
```

### Memory in Context

Relevant memories are included in the system prompt, giving the agent context about:

- Previous conversations
- User preferences
- Ongoing projects or tasks

## Configuration

```yaml
fraggle:
  memory:
    base_dir: ./data/memory
```

| Option     | Description                           | Default          |
|------------|---------------------------------------|------------------|
| `base_dir` | Directory where memory files stored   | `./data/memory`  |

## Memory Limits

To prevent context overflow, memory is summarized or truncated:

- Recent memories are prioritized
- Large memory files are summarized
- Per-scope limits prevent any single scope from dominating

## Use Cases

### Personal Assistant

Store user preferences:
- Preferred name/pronouns
- Time zone
- Communication style preferences

### Team Collaboration

Store project context:
- Current goals and tasks
- Team member roles
- Decision history

### Knowledge Base

Store reference information:
- Documentation links
- Common procedures
- FAQ answers

## Best Practices

1. **Be Specific** - Store specific facts, not vague summaries
2. **Update Regularly** - Replace outdated information
3. **Scope Appropriately** - Use the right scope for the information
4. **Review Periodically** - Memory files can be manually edited
