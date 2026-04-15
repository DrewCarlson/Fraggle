# Agent System

`FraggleAgent` orchestrates message processing by constructing a stateful `Agent` (`fraggle.agent.Agent`) that runs a ReAct-style (Reasoning + Acting) loop for reliable tool use with LLMs.

## Agent Loop

The agent loop lives in `fraggle-agent/src/main/kotlin/fraggle/agent/loop/` (`runAgentLoop`, `AgentOptions`, `LlmBridge`, `ProviderLlmBridge`, `ToolCallExecutor`). It sends context to the LLM via an `LlmBridge`, dispatches tool calls, and iterates until a final response is produced. `FraggleAgent` is responsible for building the per-request input, wiring the bridge + registry, and managing memory.

```
┌──────────────────────────────────────────────┐
│                  User Message                │
└──────────────────────────────────────────────┘
                       │
                       v
┌──────────────────────────────────────────────┐
│            Build Input                       │
│  - Platform context (bridge info)            │
│  - Memory context (global, chat, user)       │
│  - Conversation history                      │
│  - Current message                           │
└──────────────────────────────────────────────┘
                       │
                       v
┌──────────────────────────────────────────────┐
│       Agent + runAgentLoop                   │
│  ReAct loop via LlmBridge until done         │
└──────────────────────────────────────────────┘
                       │
                       v
┌──────────────────────────────────────────────┐
│       Auto-Memory Extraction                 │
│  (if enabled) Extract & reconcile facts      │
└──────────────────────────────────────────────┘
                       │
                       v
┌──────────────────────────────────────────────┐
│            Return Response                   │
└──────────────────────────────────────────────┘
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

The system prompt is built from template files and set once at agent construction time:

1. **SYSTEM.md** - Core instructions and capabilities
2. **IDENTITY.md** - Agent personality and behavior
3. **USER.md** - User-specific instructions

Tool definitions are registered in a `FraggleToolRegistry`, which generates JSON schemas from each tool's `kotlinx.serialization` descriptor and passes them to the LLM alongside the system prompt on every call.

Files are loaded from `prompts_dir` and concatenated with separators.

## Per-Request Input

Each message is processed with a per-request input string that includes:

1. **Platform context** - Which bridge (Signal, Discord, etc.) and its capabilities
2. **Memory context** - Relevant facts from Global, Chat, and User scopes
3. **Conversation history** - Recent messages (configurable via `max_history_messages`)
4. **Current message** - The user's message text

## Conversation History

```yaml
fraggle:
  agent:
    max_history_messages: 20  # Messages to include
```

History is pruned to fit within the context window while preserving recent exchanges.

## Error Handling

When a tool execution fails:

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

## Attachments

Some tools (like `screenshot_page`) produce attachments rather than text responses. Tools collect attachments via `ToolExecutionContext`, and the agent includes them alongside the text response when sending back through the bridge.
