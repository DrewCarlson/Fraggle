package fraggle.coding

import fraggle.agent.compaction.CompactionPolicy
import fraggle.agent.compaction.NeverCompactionPolicy
import fraggle.agent.loop.LlmBridge
import fraggle.agent.loop.ToolCallExecutor
import fraggle.agent.loop.ToolCallResult
import fraggle.agent.loop.ToolDefinition
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.StopReason
import fraggle.agent.message.TokenUsage
import fraggle.agent.message.ToolCall
import fraggle.coding.session.Session
import fraggle.coding.session.SessionEntry
import fraggle.coding.session.SessionFile
import fraggle.coding.session.SessionManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodingAgentTest {

    /** A scripted LlmBridge that returns canned responses in order. */
    private class ScriptedLlmBridge(
        private val responses: List<AgentMessage.Assistant>,
    ) : LlmBridge {
        var callCount = 0
            private set

        val calls = mutableListOf<Triple<String, List<AgentMessage>, List<ToolDefinition>>>()

        override suspend fun call(
            systemPrompt: String,
            messages: List<AgentMessage>,
            tools: List<ToolDefinition>,
        ): AgentMessage.Assistant {
            calls += Triple(systemPrompt, messages, tools)
            val response = responses.getOrNull(callCount)
                ?: error("ScriptedLlmBridge exhausted after $callCount calls")
            callCount++
            return response
        }
    }

    /** A no-op tool executor that returns empty results for every call. */
    private class NoopToolCallExecutor : ToolCallExecutor {
        override suspend fun execute(toolCall: ToolCall, chatId: String): ToolCallResult =
            ToolCallResult(content = "(tool skipped)", isError = false)

        override fun getToolDefinitions(): List<ToolDefinition> = emptyList()
    }

    /**
     * A tool executor that returns canned outputs for named tools, so we can
     * test the session persistence of tool-call turns without wiring a full
     * CodingToolRegistry.
     */
    private class MapToolCallExecutor(
        private val outputs: Map<String, String>,
    ) : ToolCallExecutor {
        override suspend fun execute(toolCall: ToolCall, chatId: String): ToolCallResult {
            val output = outputs[toolCall.name]
                ?: return ToolCallResult(content = "no output", isError = true)
            return ToolCallResult(content = output, isError = false)
        }

        override fun getToolDefinitions(): List<ToolDefinition> = emptyList()
    }

    private fun sessionAt(dir: Path): Session {
        val project = dir.resolve("proj").also { java.nio.file.Files.createDirectories(it) }
        val manager = SessionManager(sessionsRoot = dir.resolve("sessions"), projectRoot = project)
        return manager.createNew(model = "qwen3-test")
    }

    private fun assistantReply(
        text: String,
        toolCalls: List<ToolCall> = emptyList(),
        stopReason: StopReason = StopReason.STOP,
        usage: TokenUsage? = null,
    ): AgentMessage.Assistant =
        AgentMessage.Assistant(
            content = listOf(ContentPart.Text(text)),
            toolCalls = toolCalls,
            stopReason = stopReason,
            usage = usage,
        )

    private fun options(
        bridge: LlmBridge,
        toolExecutor: ToolCallExecutor = NoopToolCallExecutor(),
        compactionPolicy: CompactionPolicy = NeverCompactionPolicy,
        keepRecent: Int = 12,
        contextWindowTokens: Int = 0,
        workDir: Path = Path.of("/tmp"),
    ): CodingAgentOptions = CodingAgentOptions(
        model = "qwen3-test",
        workDir = workDir,
        llmBridge = bridge,
        toolCallExecutor = toolExecutor,
        systemPrompt = "You are a test coding agent.",
        maxIterations = 5,
        compactionPolicy = compactionPolicy,
        compactionKeepRecentMessages = keepRecent,
        contextWindowTokens = contextWindowTokens,
    )

    private fun readEntries(session: Session): List<SessionEntry> =
        SessionFile(session.path).readAll()

    @Nested
    inner class SingleTurn {
        @Test
        fun `user prompt + assistant reply produces two new session entries`(@TempDir dir: Path) = runTest {
            val bridge = ScriptedLlmBridge(listOf(assistantReply("hi, I'm the agent")))
            val session = sessionAt(dir)
            val agent = CodingAgent(options(bridge), session)

            agent.prompt("hello")

            val entries = readEntries(session)
            // root + user + assistant
            assertEquals(
                3,
                entries.size,
                "expected root + user + assistant, got: ${entries.map { it.payload::class.simpleName }}"
            )
            val user = entries[1].payload as SessionEntry.Payload.User
            val assistant = entries[2].payload as SessionEntry.Payload.Assistant
            assertEquals("hello", user.text)
            assertEquals("hi, I'm the agent", assistant.text)
        }

        @Test
        fun `parentId chain follows the write order`(@TempDir dir: Path) = runTest {
            val bridge = ScriptedLlmBridge(listOf(assistantReply("ack")))
            val session = sessionAt(dir)
            val agent = CodingAgent(options(bridge), session)

            agent.prompt("q")
            val entries = readEntries(session)

            // root has no parent; each subsequent entry points at the previous
            assertNull(entries[0].parentId)
            assertEquals(entries[0].id, entries[1].parentId)
            assertEquals(entries[1].id, entries[2].parentId)
        }

        @Test
        fun `usage is persisted on assistant entries`(@TempDir dir: Path) = runTest {
            val bridge = ScriptedLlmBridge(
                listOf(
                    assistantReply(
                        text = "response",
                        usage = TokenUsage(promptTokens = 42, completionTokens = 8, totalTokens = 50),
                    ),
                )
            )
            val session = sessionAt(dir)
            val agent = CodingAgent(options(bridge), session)

            agent.prompt("q")
            val entries = readEntries(session)

            val assistant = entries.last().payload as SessionEntry.Payload.Assistant
            assertNotNull(assistant.usage)
            assertEquals(42, assistant.usage.inputTokens)
            assertEquals(8, assistant.usage.outputTokens)
            assertEquals(50, assistant.usage.totalTokens)
        }
    }

    @Nested
    inner class MultiTurn {
        @Test
        fun `second prompt produces two more entries chained on the first assistant`(@TempDir dir: Path) = runTest {
            val bridge = ScriptedLlmBridge(
                listOf(
                    assistantReply("first answer"),
                    assistantReply("second answer"),
                )
            )
            val session = sessionAt(dir)
            val agent = CodingAgent(options(bridge), session)

            agent.prompt("first question")
            agent.prompt("second question")

            val entries = readEntries(session)
            // root + (user1, assistant1) + (user2, assistant2) = 5
            assertEquals(5, entries.size)
            assertEquals("first question", (entries[1].payload as SessionEntry.Payload.User).text)
            assertEquals("first answer", (entries[2].payload as SessionEntry.Payload.Assistant).text)
            assertEquals("second question", (entries[3].payload as SessionEntry.Payload.User).text)
            assertEquals("second answer", (entries[4].payload as SessionEntry.Payload.Assistant).text)
        }
    }

    @Nested
    inner class ToolCalls {
        @Test
        fun `tool call + tool result + follow-up assistant all persist`(@TempDir dir: Path) = runTest {
            // Turn 1: assistant requests a tool call.
            val toolCall = ToolCall(id = "call-1", name = "read_file", arguments = """{"path":"foo.kt"}""")
            val firstAssistant = assistantReply(
                text = "I'll read the file",
                toolCalls = listOf(toolCall),
                stopReason = StopReason.STOP,
            )
            // Turn 2 (after the tool result): assistant finishes.
            val secondAssistant = assistantReply("the file contained hello world")

            val bridge = ScriptedLlmBridge(listOf(firstAssistant, secondAssistant))
            val session = sessionAt(dir)
            val agent = CodingAgent(
                options(
                    bridge = bridge,
                    toolExecutor = MapToolCallExecutor(mapOf("read_file" to "hello world")),
                ),
                session,
            )

            agent.prompt("what's in foo.kt")

            val entries = readEntries(session)
            // root + user + assistant(toolCalls) + tool_result + assistant = 5
            val payloadClasses = entries.map { it.payload::class.simpleName }
            assertEquals(
                listOf("Root", "User", "Assistant", "ToolResult", "Assistant"),
                payloadClasses,
            )
            val a1 = entries[2].payload as SessionEntry.Payload.Assistant
            assertEquals(1, a1.toolCalls.size)
            assertEquals("read_file", a1.toolCalls[0].name)
            assertEquals("call-1", a1.toolCalls[0].id)
            assertEquals("""{"path":"foo.kt"}""", a1.toolCalls[0].argsJson)

            val tr = entries[3].payload as SessionEntry.Payload.ToolResult
            assertEquals("call-1", tr.callId)
            assertEquals("read_file", tr.toolName)
            assertEquals("hello world", tr.output)
        }
    }

    @Nested
    inner class Compaction {
        @Test
        fun `compaction fires when policy says yes, writes a Meta entry, and replaces agent state`(@TempDir dir: Path) =
            runTest {
                // Scripted LLM calls:
                //  call 0 — first prompt response
                //  call 1 — second prompt response
                //  call 2 — compaction summary (triggered before the third prompt)
                //  call 3 — third prompt response
                val bridge = ScriptedLlmBridge(
                    listOf(
                        assistantReply("first"),
                        assistantReply("second"),
                        assistantReply("[COMPACTED SUMMARY]"),
                        assistantReply("third"),
                    )
                )

                // Fire compaction exactly once, on the first call where there's
                // actually something to elide (>= 4 messages in state, since
                // keepRecent=2 means the head needs to be at least 2 messages).
                var fired = false
                val oneShotPolicy = CompactionPolicy { messages, _ ->
                    if (!fired && messages.size >= 4) {
                        fired = true
                        true
                    } else {
                        false
                    }
                }

                val session = sessionAt(dir)
                val agent = CodingAgent(
                    options(
                        bridge = bridge,
                        compactionPolicy = oneShotPolicy,
                        keepRecent = 2,
                    ),
                    session,
                )

                agent.prompt("q1")  // state after: [user1, assistant1] (2)
                agent.prompt("q2")  // state after: [..., user2, assistant2] (4)
                agent.prompt("q3")  // maybeCompact sees 4 messages → fires

                val entries = readEntries(session)
                val metaEntries = entries.filter { it.payload is SessionEntry.Payload.Meta }
                assertEquals(1, metaEntries.size, "expected exactly one Meta entry for compaction")
                val meta = metaEntries.single().payload as SessionEntry.Payload.Meta
                assertEquals("compaction", meta.label)
                assertEquals("[COMPACTED SUMMARY]", meta.summary)

                // The session file still has the full history, including all prior user/assistant turns
                val userEntries = entries.filter { it.payload is SessionEntry.Payload.User }
                val assistantEntries = entries.filter { it.payload is SessionEntry.Payload.Assistant }
                assertEquals(3, userEntries.size, "all three user turns should be in the file")
                assertEquals(3, assistantEntries.size, "all three assistant turns should be in the file")

                // The LlmBridge was called 4 times: 3 regular + 1 compaction summarization
                assertEquals(4, bridge.callCount)
            }

        @Test
        fun `compaction failure does not break the turn`(@TempDir dir: Path) = runTest {
            // Scripted LLM calls:
            //  call 0 — q1 response
            //  call 1 — compaction summary (returns empty → Compactor reports Failed)
            //  call 2 — q2 response
            val bridge = ScriptedLlmBridge(
                listOf(
                    assistantReply("first"),
                    assistantReply(""),  // empty compaction response → Failed
                    assistantReply("second"),
                )
            )
            // Fire compaction once when there are >= 2 messages (keepRecent=1 so
            // the head will have exactly 1 message — still enough to call the LLM).
            var fired = false
            val oneShotPolicy = CompactionPolicy { messages, _ ->
                if (!fired && messages.size >= 2) {
                    fired = true
                    true
                } else {
                    false
                }
            }

            val session = sessionAt(dir)
            val agent = CodingAgent(
                options(bridge = bridge, compactionPolicy = oneShotPolicy, keepRecent = 1),
                session,
            )

            agent.prompt("q1")  // state after: [user1, assistant1] (2)
            agent.prompt("q2")  // maybeCompact fires → LLM returns empty → Failed; q2 proceeds

            val entries = readEntries(session)
            // No Meta entry from a failed compaction
            assertTrue(entries.none { it.payload is SessionEntry.Payload.Meta })
            // Both turns still persisted
            val assistants = entries.filter { it.payload is SessionEntry.Payload.Assistant }
            assertEquals(2, assistants.size)
            assertEquals("first", (assistants[0].payload as SessionEntry.Payload.Assistant).text)
            assertEquals("second", (assistants[1].payload as SessionEntry.Payload.Assistant).text)
        }
    }

    @Nested
    inner class EventSubscription {
        @Test
        fun `subscribers see every AgentEvent from the underlying Agent`(@TempDir dir: Path) = runTest {
            val bridge = ScriptedLlmBridge(listOf(assistantReply("ack")))
            val session = sessionAt(dir)
            val agent = CodingAgent(options(bridge), session)

            val eventTypes = mutableListOf<String>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                agent.events()
                    .collect { event ->
                        eventTypes += event::class.simpleName ?: "?"
                    }
            }

            agent.prompt("hello")

            // We don't assert exact order or completeness — just that at least
            // some lifecycle events reached the subscriber. The underlying
            // AgentEvent taxonomy is tested in agent-core.
            assertTrue(eventTypes.isNotEmpty(), "subscriber should have received events")
            assertTrue(eventTypes.any { it.contains("Message") }, "should see Message* events: $eventTypes")
        }
    }
}
