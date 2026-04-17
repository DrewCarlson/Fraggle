package fraggle.provider

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThinkingLevelTest {

    @Nested
    inner class AsLmStudioReasoning {
        @Test
        fun `OFF maps to off`() {
            assertEquals("off", ThinkingLevel.OFF.asLmStudioReasoning())
        }

        @Test
        fun `LOW maps to low`() {
            assertEquals("low", ThinkingLevel.LOW.asLmStudioReasoning())
        }

        @Test
        fun `MEDIUM maps to medium`() {
            assertEquals("medium", ThinkingLevel.MEDIUM.asLmStudioReasoning())
        }

        @Test
        fun `HIGH maps to high`() {
            assertEquals("high", ThinkingLevel.HIGH.asLmStudioReasoning())
        }

        @Test
        fun `ON maps to on`() {
            assertEquals("on", ThinkingLevel.ON.asLmStudioReasoning())
        }

        @Test
        fun `MINIMAL degrades to low for LM Studio`() {
            assertEquals("low", ThinkingLevel.MINIMAL.asLmStudioReasoning())
        }
    }

    @Nested
    inner class FromUserInput {
        @Test
        fun `empty string returns null`() {
            assertNull(thinkingLevelFromUserInput(""))
        }

        @Test
        fun `default returns null`() {
            assertNull(thinkingLevelFromUserInput("default"))
        }

        @Test
        fun `auto returns null`() {
            assertNull(thinkingLevelFromUserInput("auto"))
        }

        @Test
        fun `off parses to OFF`() {
            assertEquals(ThinkingLevel.OFF, thinkingLevelFromUserInput("off"))
        }

        @Test
        fun `on parses to ON`() {
            assertEquals(ThinkingLevel.ON, thinkingLevelFromUserInput("on"))
        }

        @Test
        fun `low medium high parse correctly`() {
            assertEquals(ThinkingLevel.LOW, thinkingLevelFromUserInput("low"))
            assertEquals(ThinkingLevel.MEDIUM, thinkingLevelFromUserInput("medium"))
            assertEquals(ThinkingLevel.HIGH, thinkingLevelFromUserInput("high"))
        }

        @Test
        fun `minimal parses to MINIMAL`() {
            assertEquals(ThinkingLevel.MINIMAL, thinkingLevelFromUserInput("minimal"))
        }

        @Test
        fun `parsing is case insensitive`() {
            assertEquals(ThinkingLevel.HIGH, thinkingLevelFromUserInput("HIGH"))
            assertEquals(ThinkingLevel.MEDIUM, thinkingLevelFromUserInput("Medium"))
            assertEquals(ThinkingLevel.OFF, thinkingLevelFromUserInput("OFF"))
        }

        @Test
        fun `surrounding whitespace is ignored`() {
            assertEquals(ThinkingLevel.LOW, thinkingLevelFromUserInput("  low  "))
        }

        @Test
        fun `unknown level throws`() {
            assertThrows<IllegalStateException> {
                thinkingLevelFromUserInput("turbo")
            }
        }
    }
}
