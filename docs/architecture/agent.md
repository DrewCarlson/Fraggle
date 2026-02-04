# Agent System

The FraggleAgent implements a ReAct-style (Reasoning + Acting) loop for reliable tool use with LLMs.

## ReAct Loop

The agent processes messages through an iterative loop:

```
┌──────────────────────────────────────────────┐
│                  User Message                │
└──────────────────────────────────────────────┘
                       │
                       v
┌──────────────────────────────────────────────┐
│            Build Context                     │
│  - System prompt (from prompt files)         │
│  - Available tools (from skill registry)     │
│  - Conversation history                      │
│  - Memory context                            │
└──────────────────────────────────────────────┘
                       │
                       v
┌──────────────────────────────────────────────┐
│              LLM Request                     │
│  Send context + message to LLM provider      │
└──────────────────────────────────────────────┘
                       │
                       v
              ┌───────────────────┐
              │  Tool Call?       │
              └───────────────────┘
                 │           │
                Yes          No
                 │           │
                 v           v
┌────────────────────┐  ┌────────────────────┐
│  Execute Skill     │  │  Return Response   │
│  Add result to     │  │                    │
│  context           │  │                    │
└────────────────────┘  └────────────────────┘
         │
         │ (loop back)
         v
    [LLM Request]
```

## Iteration Limits

The agent has a configurable maximum iteration count (`max_iterations`) to prevent infinite loops. Each tool call counts as one iteration.

```yaml
fraggle:
  agent:
    max_iterations: 10  # Default
```

When the limit is reached, the agent forces a final response from the LLM.

## System Prompt Construction

The system prompt is built from multiple sources:

1. **SYSTEM.md** - Core instructions and capabilities
2. **IDENTITY.md** - Agent personality and behavior
3. **USER.md** - User-specific instructions
4. **Tool Definitions** - Auto-generated from skill registry
5. **Memory Context** - Relevant memories from storage

Files are loaded from `prompts_dir` and concatenated with separators.

## Conversation History

The agent maintains conversation history for context:

```yaml
fraggle:
  agent:
    max_history_messages: 20  # Messages to include
```

History is pruned to fit within the context window while preserving recent exchanges.

## Error Handling

When a skill execution fails:

1. The error is formatted as a tool result
2. The LLM receives the error and can retry or adapt
3. After repeated failures, the agent may explain the issue to the user

## Temperature and Sampling

Control LLM response randomness:

```yaml
fraggle:
  agent:
    temperature: 0.7    # 0.0 = deterministic, 2.0 = very random
    max_tokens: 4096    # Maximum response length
```

## Context Structure

Each LLM request includes:

```json
{
  "messages": [
    {"role": "system", "content": "...system prompt..."},
    {"role": "user", "content": "Previous user message"},
    {"role": "assistant", "content": "Previous response"},
    {"role": "user", "content": "Current message"}
  ],
  "tools": [
    {
      "name": "read_file",
      "description": "Read the contents of a file...",
      "parameters": {...}
    }
  ]
}
```

## Tool Result Format

When a tool is executed, the result is added to the conversation:

```json
{
  "role": "tool",
  "tool_call_id": "call_abc123",
  "content": "File contents here..."
}
```

The LLM then continues reasoning with the tool result in context.

## Attachments

Some skills (like `send_image` and `screenshot_page`) produce attachments rather than text responses. These are handled specially:

1. Skill returns `SkillResult.ImageAttachment`
2. Agent extracts the attachment data
3. Bridge sends the attachment alongside the text response

## Provider Compatibility

The agent adapts its behavior based on the LLM provider:

- **OpenAI/LM Studio** - Uses OpenAI function calling format
- **Anthropic** - Uses Claude tool use format

The core loop remains the same; only the API format changes.
