package fraggle.prompt

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptTemplateTest {

    @Nested
    inner class SectionParsing {

        @Test
        fun `parses sections from markdown headings`() {
            val template = PromptTemplate.parse(
                """
                ## First
                Content one.

                ## Second
                Content two.
                """.trimIndent()
            )

            assertEquals(setOf("first", "second"), template.sectionNames())
            assertEquals("Content one.", template.renderSection("first"))
            assertEquals("Content two.", template.renderSection("second"))
        }

        @Test
        fun `preserves preamble content before first heading`() {
            val template = PromptTemplate.parse(
                """
                Preamble text.

                ## Section
                Body.
                """.trimIndent()
            )

            assertTrue(template.hasSection(""))
            assertEquals("Preamble text.", template.renderSection(""))
        }

        @Test
        fun `handles content with no headings`() {
            val template = PromptTemplate.parse("Just plain text.\nNo headings here.")

            assertEquals(setOf(""), template.sectionNames())
            assertEquals("Just plain text.\nNo headings here.", template.renderSection(""))
        }

        @Test
        fun `handles multi-line section content`() {
            val template = PromptTemplate.parse(
                """
                ## Rules
                Rule 1: Be kind.
                Rule 2: Be helpful.
                Rule 3: Be accurate.
                """.trimIndent()
            )

            val content = template.renderSection("rules")!!
            assertTrue(content.contains("Rule 1"))
            assertTrue(content.contains("Rule 3"))
        }
    }

    @Nested
    inner class CaseInsensitiveLookup {

        @Test
        fun `section lookup is case-insensitive`() {
            val template = PromptTemplate.parse(
                """
                ## My Section
                Content here.
                """.trimIndent()
            )

            assertEquals("Content here.", template.renderSection("my section"))
            assertEquals("Content here.", template.renderSection("My Section"))
            assertEquals("Content here.", template.renderSection("MY SECTION"))
        }

        @Test
        fun `hasSection is case-insensitive`() {
            val template = PromptTemplate.parse("## Test\nBody.")

            assertTrue(template.hasSection("test"))
            assertTrue(template.hasSection("Test"))
            assertTrue(template.hasSection("TEST"))
        }
    }

    @Nested
    inner class VariableSubstitution {

        @Test
        fun `substitutes variables in renderSection`() {
            val template = PromptTemplate.parse(
                """
                ## Greeting
                Hello, {{name}}! Welcome to {{place}}.
                """.trimIndent()
            )

            val result = template.renderSection("greeting", mapOf("name" to "Alice", "place" to "Wonderland"))
            assertEquals("Hello, Alice! Welcome to Wonderland.", result)
        }

        @Test
        fun `substitutes variables in render`() {
            val template = PromptTemplate.parse(
                """
                ## Greeting
                Hello {{name}}.
                """.trimIndent()
            )

            val result = template.render(mapOf("name" to "Bob"))
            assertTrue(result.contains("Hello Bob."))
        }

        @Test
        fun `leaves unresolved variables as-is`() {
            val template = PromptTemplate.parse("## Test\n{{known}} and {{unknown}}")

            val result = template.renderSection("test", mapOf("known" to "resolved"))
            assertEquals("resolved and {{unknown}}", result)
        }

        @Test
        fun `handles empty variables map`() {
            val template = PromptTemplate.parse("## Test\nHello {{name}}.")

            val result = template.renderSection("test")
            assertEquals("Hello {{name}}.", result)
        }
    }

    @Nested
    inner class MissingSections {

        @Test
        fun `renderSection returns null for nonexistent section`() {
            val template = PromptTemplate.parse("## Existing\nContent.")

            assertNull(template.renderSection("nonexistent"))
        }

        @Test
        fun `hasSection returns false for nonexistent section`() {
            val template = PromptTemplate.parse("## Existing\nContent.")

            assertFalse(template.hasSection("nonexistent"))
        }
    }

    @Nested
    inner class EmptyContent {

        @Test
        fun `handles empty input`() {
            val template = PromptTemplate.parse("")

            assertEquals(setOf(""), template.sectionNames())
            assertEquals("", template.renderSection(""))
        }

        @Test
        fun `handles whitespace-only section body`() {
            val template = PromptTemplate.parse(
                """
                ## Empty

                ## Next
                Content.
                """.trimIndent()
            )

            assertEquals("", template.renderSection("empty"))
            assertEquals("Content.", template.renderSection("next"))
        }
    }
}
