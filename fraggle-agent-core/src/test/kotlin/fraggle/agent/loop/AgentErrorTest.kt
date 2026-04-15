package fraggle.agent.loop

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentErrorTest {

    @Nested
    inner class LlmErrorTests {
        @Test
        fun `carries message and cause`() {
            val cause = RuntimeException("connection refused")
            val error = AgentError.LlmError("Failed to call LLM", cause)
            assertEquals("Failed to call LLM", error.message)
            assertEquals(cause, error.cause)
        }

        @Test
        fun `cause is optional`() {
            val error = AgentError.LlmError("timeout")
            assertNull(error.cause)
        }

        @Test
        fun `is an Exception`() {
            val error: Exception = AgentError.LlmError("test")
            assertIs<AgentError>(error)
        }
    }

    @Nested
    inner class ToolErrorTests {
        @Test
        fun `carries tool name, message, and cause`() {
            val error = AgentError.ToolError("get_weather", "API key invalid")
            assertEquals("get_weather", error.toolName)
            assertEquals("API key invalid", error.message)
        }
    }

    @Nested
    inner class AbortedTests {
        @Test
        fun `has default message`() {
            val error = AgentError.Aborted()
            assertEquals("Agent run was cancelled", error.message)
        }

        @Test
        fun `accepts custom message`() {
            val error = AgentError.Aborted("User cancelled")
            assertEquals("User cancelled", error.message)
        }
    }

    @Nested
    inner class TimeoutTests {
        @Test
        fun `carries message`() {
            val error = AgentError.Timeout("LLM response timed out after 60s")
            assertTrue(error.message.contains("60s"))
        }
    }

    @Nested
    inner class PermissionDeniedTests {
        @Test
        fun `carries tool name and reason`() {
            val error = AgentError.PermissionDenied("delete_file", "Policy denies file deletion")
            assertEquals("delete_file", error.toolName)
            assertEquals("Policy denies file deletion", error.reason)
            assertTrue(error.message.contains("delete_file"))
            assertTrue(error.message.contains("Policy denies"))
        }
    }

    @Nested
    inner class MaxIterationsTests {
        @Test
        fun `carries iteration count`() {
            val error = AgentError.MaxIterationsReached(10)
            assertEquals(10, error.iterations)
            assertTrue(error.message.contains("10"))
        }
    }

    @Nested
    inner class SealedHierarchy {
        @Test
        fun `exhaustive when matching`() {
            val errors: List<AgentError> = listOf(
                AgentError.LlmError("llm"),
                AgentError.ToolError("tool", "err"),
                AgentError.Aborted(),
                AgentError.Timeout("timeout"),
                AgentError.PermissionDenied("t", "r"),
                AgentError.MaxIterationsReached(5),
            )

            val types = errors.map { error ->
                when (error) {
                    is AgentError.LlmError -> "llm"
                    is AgentError.ToolError -> "tool"
                    is AgentError.Aborted -> "aborted"
                    is AgentError.Timeout -> "timeout"
                    is AgentError.PermissionDenied -> "denied"
                    is AgentError.MaxIterationsReached -> "max_iter"
                }
            }

            assertEquals(listOf("llm", "tool", "aborted", "timeout", "denied", "max_iter"), types)
        }

        @Test
        fun `can be caught as Exception`() {
            val caught = try {
                throw AgentError.LlmError("test")
            } catch (e: AgentError) {
                e
            }
            assertIs<AgentError.LlmError>(caught)
        }

        @Test
        fun `can be caught by specific type`() {
            val caught = try {
                throw AgentError.PermissionDenied("tool", "denied")
            } catch (e: AgentError.PermissionDenied) {
                e
            }
            assertEquals("tool", caught.toolName)
        }
    }
}
