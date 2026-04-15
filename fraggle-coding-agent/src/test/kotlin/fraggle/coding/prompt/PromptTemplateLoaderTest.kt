package fraggle.coding.prompt

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptTemplateLoaderTest {

    @Nested
    inner class Discovery {
        @Test
        fun `missing directory returns empty list`(@TempDir dir: Path) {
            val loader = PromptTemplateLoader()
            val result = loader.loadFromDirectory(dir.resolve("does-not-exist"))
            assertEquals(emptyList(), result)
        }

        @Test
        fun `non-directory path returns empty list`(@TempDir dir: Path) {
            val file = dir.resolve("not-a-dir.md").also { it.writeText("content") }
            val result = PromptTemplateLoader().loadFromDirectory(file)
            assertEquals(emptyList(), result)
        }

        @Test
        fun `picks up all md files in a directory sorted alphabetically`(@TempDir dir: Path) {
            dir.resolve("review.md").writeText("review body")
            dir.resolve("refactor.md").writeText("refactor body")
            dir.resolve("analyze.md").writeText("analyze body")

            val loaded = PromptTemplateLoader().loadFromDirectory(dir)
            assertEquals(listOf("analyze", "refactor", "review"), loaded.map { it.name })
        }

        @Test
        fun `non-md files are ignored`(@TempDir dir: Path) {
            dir.resolve("keep.md").writeText("content")
            dir.resolve("ignore.txt").writeText("plain text")
            dir.resolve("ignore.json").writeText("{}")
            dir.resolve("README").writeText("no extension")

            val loaded = PromptTemplateLoader().loadFromDirectory(dir)
            assertEquals(listOf("keep"), loaded.map { it.name })
        }

        @Test
        fun `MD extension is case-insensitive`(@TempDir dir: Path) {
            dir.resolve("Upper.MD").writeText("x")
            dir.resolve("Lower.md").writeText("y")

            val loaded = PromptTemplateLoader().loadFromDirectory(dir)
            assertEquals(setOf("upper", "lower"), loaded.map { it.name }.toSet())
        }

        @Test
        fun `filename is lowercased for the template name`(@TempDir dir: Path) {
            dir.resolve("Review.md").writeText("content")
            val loaded = PromptTemplateLoader().loadFromDirectory(dir)
            assertEquals("review", loaded.single().name)
        }

        @Test
        fun `subdirectories are not recursed into`(@TempDir dir: Path) {
            dir.resolve("top.md").writeText("top")
            val nested = dir.resolve("nested").also { it.createDirectories() }
            nested.resolve("inner.md").writeText("inner — should be ignored")

            val loaded = PromptTemplateLoader().loadFromDirectory(dir)
            assertEquals(listOf("top"), loaded.map { it.name })
        }
    }

    @Nested
    inner class VariableExtraction {
        @Test
        fun `extracts simple variables in order`(@TempDir dir: Path) {
            dir.resolve("t.md").writeText("Hello {{name}}, welcome to {{project}}.")
            val loaded = PromptTemplateLoader().loadFromDirectory(dir).single()
            assertEquals(listOf("name", "project"), loaded.variables)
        }

        @Test
        fun `deduplicates repeated variables preserving first-appearance order`(@TempDir dir: Path) {
            dir.resolve("t.md").writeText("{{a}} {{b}} {{a}} {{c}} {{b}}")
            val loaded = PromptTemplateLoader().loadFromDirectory(dir).single()
            assertEquals(listOf("a", "b", "c"), loaded.variables)
        }

        @Test
        fun `tolerates whitespace inside braces`(@TempDir dir: Path) {
            dir.resolve("t.md").writeText("{{ name }} and {{  spaced  }}")
            val loaded = PromptTemplateLoader().loadFromDirectory(dir).single()
            assertEquals(listOf("name", "spaced"), loaded.variables)
        }

        @Test
        fun `rejects invalid variable names`(@TempDir dir: Path) {
            dir.resolve("t.md").writeText("{{ valid }} {{ not-a-var }} {{ 1badstart }}")
            val loaded = PromptTemplateLoader().loadFromDirectory(dir).single()
            // Only the well-formed name makes it through
            assertEquals(listOf("valid"), loaded.variables)
        }

        @Test
        fun `no variables returns empty list`(@TempDir dir: Path) {
            dir.resolve("t.md").writeText("just a plain template")
            val loaded = PromptTemplateLoader().loadFromDirectory(dir).single()
            assertEquals(emptyList(), loaded.variables)
        }
    }

    @Nested
    inner class Expansion {
        @Test
        fun `expand substitutes known variables`(@TempDir dir: Path) {
            dir.resolve("t.md").writeText("Review {{path}} focusing on {{concern}}.")
            val loaded = PromptTemplateLoader().loadFromDirectory(dir).single()
            val expanded = loaded.expand(mapOf("path" to "src/Foo.kt", "concern" to "thread safety"))
            assertEquals("Review src/Foo.kt focusing on thread safety.", expanded)
        }

        @Test
        fun `expand leaves unknown variables literal`(@TempDir dir: Path) {
            dir.resolve("t.md").writeText("Hello {{name}}, from {{place}}.")
            val loaded = PromptTemplateLoader().loadFromDirectory(dir).single()
            val expanded = loaded.expand(mapOf("name" to "world"))
            assertTrue(expanded.contains("Hello world"))
            assertTrue(expanded.contains("{{place}}"), "unknown variable should stay literal: $expanded")
        }

        @Test
        fun `expand handles replacement values with regex-special characters`(@TempDir dir: Path) {
            dir.resolve("t.md").writeText("Pattern is {{p}}")
            val loaded = PromptTemplateLoader().loadFromDirectory(dir).single()
            // $ and \ are regex-replacement-special; Regex.escapeReplacement must handle them
            val expanded = loaded.expand(mapOf("p" to "a\$b\\c"))
            assertEquals("Pattern is a\$b\\c", expanded)
        }

        @Test
        fun `expand tolerates braces with whitespace in the template`(@TempDir dir: Path) {
            dir.resolve("t.md").writeText("Value = {{ x }}")
            val loaded = PromptTemplateLoader().loadFromDirectory(dir).single()
            val expanded = loaded.expand(mapOf("x" to "42"))
            assertEquals("Value = 42", expanded)
        }
    }
}
