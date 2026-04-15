package fraggle.agent.compaction

import fraggle.agent.loop.LlmBridge
import fraggle.agent.loop.ToolDefinition
import fraggle.agent.message.AgentMessage
import fraggle.agent.message.ContentPart
import fraggle.agent.message.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmCompactorTest {

    /**
     * Captures every call made to the bridge so tests can assert what the
     * compactor sent to the summarizer LLM.
     */
    private class RecordingLlmBridge(
        private val respondWith: (systemPrompt: String, messages: List<AgentMessage>) -> String,
    ) : LlmBridge {
        data class Call(
            val systemPrompt: String,
            val messages: List<AgentMessage>,
            val tools: List<ToolDefinition>,
        )

        val calls = mutableListOf<Call>()

        override suspend fun call(
            systemPrompt: String,
            messages: List<AgentMessage>,
            tools: List<ToolDefinition>,
        ): AgentMessage.Assistant {
            calls += Call(systemPrompt, messages, tools)
            val text = respondWith(systemPrompt, messages)
            return AgentMessage.Assistant(content = listOf(ContentPart.Text(text)))
        }
    }

    /** A bridge that throws on every call. */
    private class ExplodingLlmBridge(private val message: String) : LlmBridge {
        override suspend fun call(
            systemPrompt: String,
            messages: List<AgentMessage>,
            tools: List<ToolDefinition>,
        ): AgentMessage.Assistant = throw RuntimeException(message)
    }

    @Nested
    inner class PolicyGate {
        @Test
        fun `returns NotNeeded when policy says no`() = runTest {
            val bridge = RecordingLlmBridge { _, _ -> "summary" }
            val compactor = LlmCompactor(
                llmBridge = bridge,
                policy = NeverCompactionPolicy,
                keepRecentMessages = 2,
            )
            val messages = List(20) { AgentMessage.User("msg $it") }

            val result = compactor.compact(
                messages = messages,
                usage = ContextUsage(usedTokens = 0, maxTokens = 0, messageCount = messages.size),
            )

            assertIs<CompactionResult.NotNeeded>(result)
            assertEquals(0, bridge.calls.size, "bridge should not be called when policy gates compaction")
        }

        @Test
        fun `returns NotNeeded when message count is at or below keepRecent even if policy says yes`() = runTest {
            val bridge = RecordingLlmBridge { _, _ -> "summary" }
            val compactor = LlmCompactor(
                llmBridge = bridge,
                policy = CompactionPolicy { _, _ -> true },
                keepRecentMessages = 5,
            )
            val messages = List(5) { AgentMessage.User("msg $it") }

            val result = compactor.compact(messages, ContextUsage(0, 0, 5))

            assertIs<CompactionResult.NotNeeded>(result, "nothing to elide")
            assertEquals(0, bridge.calls.size)
        }
    }

    @Nested
    inner class SuccessPath {
        @Test
        fun `compacts when policy says yes and there is something to elide`() = runTest {
            val bridge = RecordingLlmBridge { _, _ -> "TLDR: user asked about auth; agent read config." }
            val compactor = LlmCompactor(
                llmBridge = bridge,
                policy = CompactionPolicy { _, _ -> true },
                keepRecentMessages = 2,
            )
            val messages = listOf<AgentMessage>(
                AgentMessage.User("first question"),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("first answer"))),
                AgentMessage.User("second question"),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("second answer"))),
                AgentMessage.User("latest"),
                AgentMessage.Assistant(content = listOf(ContentPart.Text("latest answer"))),
            )

            val result = compactor.compact(messages, ContextUsage(0, 0, messages.size))

            val compacted = assertIs<CompactionResult.Compacted>(result)
            assertEquals(2, compacted.recentMessages.size, "keepRecentMessages = 2")
            assertEquals(4, compacted.compactedCount, "the other 4 were summarized")
            assertEquals("TLDR: user asked about auth; agent read config.", compacted.summary)
            // The tail should be the LAST 2 messages verbatim
            assertEquals(messages.takeLast(2), compacted.recentMessages)
        }

        @Test
        fun `summary request contains every older message rendered by role`() = runTest {
            val bridge = RecordingLlmBridge { _, _ -> "summary" }
            val compactor = LlmCompactor(
                llmBridge = bridge,
                policy = CompactionPolicy { _, _ -> true },
                keepRecentMessages = 1,
            )
            val messages = listOf<AgentMessage>(
                AgentMessage.User("hello there"),
                AgentMessage.Assistant(
                    content = listOf(ContentPart.Text("let me read a file")),
                    toolCalls = listOf(ToolCall(id = "c1", name = "read_file", arguments = "{}")),
                ),
                AgentMessage.ToolResult(
                    toolCallId = "c1",
                    toolName = "read_file",
                    text = "file contents",
                ),
                AgentMessage.User("latest and kept"),
            )

            compactor.compact(messages, ContextUsage(0, 0, messages.size))

            assertEquals(1, bridge.calls.size, "should call the bridge exactly once for summarization")
            val requestMessages = bridge.calls.single().messages
            assertEquals(1, requestMessages.size, "summarization is a single user message")
            val body = (requestMessages.single() as AgentMessage.User)
                .content.filterIsInstance<ContentPart.Text>().single().text

            assertTrue(body.contains("[User] hello there"), "body missing user line: $body")
            assertTrue(body.contains("[Assistant] let me read a file (tool calls: read_file)"), "body missing assistant+tools line: $body")
            assertTrue(body.contains("[Tool result: read_file] file contents"), "body missing tool result line: $body")
            // The kept-recent message must NOT appear in the summarization input
            assertTrue(!body.contains("latest and kept"), "kept-tail message leaked into summarization input: $body")
        }

        @Test
        fun `summarizer system prompt is forwarded to the bridge`() = runTest {
            val customPrompt = "You are CodingSummarizer v2. Capture file edits and decisions."
            val bridge = RecordingLlmBridge { _, _ -> "summary" }
            val compactor = LlmCompactor(
                llmBridge = bridge,
                policy = CompactionPolicy { _, _ -> true },
                keepRecentMessages = 1,
                summarizerSystemPrompt = customPrompt,
            )
            compactor.compact(
                messages = List(5) { AgentMessage.User("msg $it") },
                usage = ContextUsage(0, 0, 5),
            )
            assertEquals(customPrompt, bridge.calls.single().systemPrompt)
        }

        @Test
        fun `tools list is always empty — summarizer does not call tools`() = runTest {
            val bridge = RecordingLlmBridge { _, _ -> "summary" }
            val compactor = LlmCompactor(
                llmBridge = bridge,
                policy = CompactionPolicy { _, _ -> true },
                keepRecentMessages = 1,
            )
            compactor.compact(
                messages = List(5) { AgentMessage.User("msg $it") },
                usage = ContextUsage(0, 0, 5),
            )
            assertEquals(emptyList(), bridge.calls.single().tools)
        }
    }

    @Nested
    inner class Cumulative {
        @Test
        fun `previousSummary is prepended when provided`() = runTest {
            val bridge = RecordingLlmBridge { _, _ -> "cumulative summary" }
            val compactor = LlmCompactor(
                llmBridge = bridge,
                policy = CompactionPolicy { _, _ -> true },
                keepRecentMessages = 1,
            )
            compactor.compact(
                messages = listOf(
                    AgentMessage.User("old1"),
                    AgentMessage.User("old2"),
                    AgentMessage.User("latest"),
                ),
                usage = ContextUsage(0, 0, 3),
                previousSummary = "In rounds 1-5, the user asked about refactoring.",
            )

            val body = (bridge.calls.single().messages.single() as AgentMessage.User)
                .content.filterIsInstance<ContentPart.Text>().single().text

            assertTrue(body.contains("Previous conversation summary:"), "body: $body")
            assertTrue(body.contains("In rounds 1-5, the user asked about refactoring."), "body: $body")
            // And the new messages are still present
            assertTrue(body.contains("[User] old1"))
            assertTrue(body.contains("[User] old2"))
        }

        @Test
        fun `null previousSummary produces no previous-summary header`() = runTest {
            val bridge = RecordingLlmBridge { _, _ -> "summary" }
            val compactor = LlmCompactor(
                llmBridge = bridge,
                policy = CompactionPolicy { _, _ -> true },
                keepRecentMessages = 1,
            )
            compactor.compact(
                messages = List(3) { AgentMessage.User("msg $it") },
                usage = ContextUsage(0, 0, 3),
                previousSummary = null,
            )
            val body = (bridge.calls.single().messages.single() as AgentMessage.User)
                .content.filterIsInstance<ContentPart.Text>().single().text
            assertTrue(!body.contains("Previous conversation summary:"), "unexpected header: $body")
        }
    }

    @Nested
    inner class FailurePaths {
        @Test
        fun `LLM exception becomes a Failed result rather than throwing`() = runTest {
            val compactor = LlmCompactor(
                llmBridge = ExplodingLlmBridge("network down"),
                policy = CompactionPolicy { _, _ -> true },
                keepRecentMessages = 1,
            )
            val result = compactor.compact(
                messages = List(5) { AgentMessage.User("msg $it") },
                usage = ContextUsage(0, 0, 5),
            )
            val failed = assertIs<CompactionResult.Failed>(result)
            assertTrue(failed.reason.contains("network down"), "expected LLM error in reason: ${failed.reason}")
        }

        @Test
        fun `empty summary becomes a Failed result`() = runTest {
            val bridge = RecordingLlmBridge { _, _ -> "   \n  \n" }
            val compactor = LlmCompactor(
                llmBridge = bridge,
                policy = CompactionPolicy { _, _ -> true },
                keepRecentMessages = 1,
            )
            val result = compactor.compact(
                messages = List(5) { AgentMessage.User("msg $it") },
                usage = ContextUsage(0, 0, 5),
            )
            val failed = assertIs<CompactionResult.Failed>(result)
            assertTrue(failed.reason.contains("empty", ignoreCase = true), "reason: ${failed.reason}")
        }
    }

    @Test
    fun `keepRecentMessages below one is rejected at construction`() {
        val bridge = RecordingLlmBridge { _, _ -> "x" }
        val thrown = runCatching {
            LlmCompactor(bridge, policy = NeverCompactionPolicy, keepRecentMessages = 0)
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "expected IAE, got $thrown")
        assertNull(null) // keeps kotlin.test import warm
    }
}
