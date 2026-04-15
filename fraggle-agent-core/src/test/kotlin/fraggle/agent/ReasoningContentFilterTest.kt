package fraggle.agent

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ReasoningContentFilterTest {

    @Nested
    inner class Strip {

        @Test
        fun `returns text unchanged when no reasoning tags present`() {
            val input = "Hello, how can I help you today?"
            assertEquals(input, ReasoningContentFilter.strip(input))
        }

        @Test
        fun `strips think tags`() {
            val input = "<think>Let me reason about this...</think>The answer is 42."
            assertEquals("The answer is 42.", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `strips thinking tags`() {
            val input = "<thinking>Processing the request...</thinking>Here is the result."
            assertEquals("Here is the result.", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `strips reasoning tags`() {
            val input = "<reasoning>Step 1: analyze. Step 2: conclude.</reasoning>The conclusion is clear."
            assertEquals("The conclusion is clear.", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `strips reflection tags`() {
            val input = "<reflection>Was my answer correct? Yes.</reflection>Final answer: yes."
            assertEquals("Final answer: yes.", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `strips multiline reasoning content`() {
            val input = """
                <think>
                Let me think step by step:
                1. First consideration
                2. Second consideration
                3. Final thought
                </think>
                Based on my analysis, the answer is 42.
            """.trimIndent()
            assertEquals("Based on my analysis, the answer is 42.", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `strips multiple reasoning blocks`() {
            val input = "<think>first thought</think>Part one. <think>second thought</think>Part two."
            assertEquals("Part one. Part two.", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `handles reasoning at end of text`() {
            val input = "Here is my answer.<thinking>I hope that was right.</thinking>"
            assertEquals("Here is my answer.", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `handles text that is only reasoning`() {
            val input = "<think>Just thinking out loud...</think>"
            assertEquals("", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `is case insensitive`() {
            val input = "<THINK>uppercase reasoning</THINK>Result."
            assertEquals("Result.", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `collapses excess blank lines after stripping`() {
            val input = "Before.\n\n<think>reasoning</think>\n\n\n\nAfter."
            assertEquals("Before.\n\nAfter.", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `preserves empty string`() {
            assertEquals("", ReasoningContentFilter.strip(""))
        }

        @Test
        fun `does not strip mismatched tags`() {
            val input = "<think>content</thinking>Still here."
            assertEquals(input, ReasoningContentFilter.strip(input))
        }

        @Test
        fun `handles nested content with angle brackets`() {
            val input = "<think>The user wants to compare x > y and a < b</think>x is greater than y when x=5, y=3."
            assertEquals("x is greater than y when x=5, y=3.", ReasoningContentFilter.strip(input))
        }
    }

    @Nested
    inner class `Gemma 4 channel tags` {

        @Test
        fun `strips gemma channel thought block`() {
            val input = "<|channel>thought\nLet me think about this step by step...<channel|>The answer is 42."
            assertEquals("The answer is 42.", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `strips multiline gemma channel thought block`() {
            val input = """
                <|channel>thought
                Step 1: Consider the input
                Step 2: Process it
                Step 3: Form conclusion
                <channel|>
                Here is my response.
            """.trimIndent()
            assertEquals("Here is my response.", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `strips gemma channel block alongside standard tags`() {
            val input = "<think>initial thought</think>Part one. <|channel>thought\nmore reasoning<channel|>Part two."
            assertEquals("Part one. Part two.", ReasoningContentFilter.strip(input))
        }

        @Test
        fun `does not strip channel block without thought marker`() {
            val input = "<|channel>other\nsome content<channel|>Visible."
            assertEquals(input, ReasoningContentFilter.strip(input))
        }

        @Test
        fun `handles text that is only a gemma channel block`() {
            val input = "<|channel>thought\nJust reasoning, no output.<channel|>"
            assertEquals("", ReasoningContentFilter.strip(input))
        }
    }
}
