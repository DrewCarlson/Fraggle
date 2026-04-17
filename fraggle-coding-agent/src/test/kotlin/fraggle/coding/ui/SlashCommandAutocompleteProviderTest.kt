package fraggle.coding.ui

import fraggle.tui.ui.Autocompletion
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SlashCommandAutocompleteProviderTest {

    private fun completion(label: String, description: String? = null): Autocompletion =
        Autocompletion(label = label, replacement = label, description = description)

    @Nested
    inner class TriggerHandling {
        @Test
        fun `only handles forward-slash`() {
            val p = SlashCommandAutocompleteProvider { emptyList() }
            assertTrue(p.handlesTrigger('/'))
            assertFalse(p.handlesTrigger('@'))
            assertFalse(p.handlesTrigger('?'))
        }

        @Test
        fun `suggest on unhandled trigger returns empty`() {
            val p = SlashCommandAutocompleteProvider { listOf(completion("quit")) }
            assertEquals(emptyList(), p.suggest('@', "", 10))
        }
    }

    @Nested
    inner class PrefixFiltering {
        @Test
        fun `empty prefix matches every entry (startsWith)`() {
            val p = SlashCommandAutocompleteProvider {
                listOf(completion("hotkeys"), completion("quit"), completion("session"))
            }
            val result = p.suggest('/', "", 10).map { it.label }
            assertEquals(listOf("hotkeys", "quit", "session"), result)
        }

        @Test
        fun `prefix startsWith match ranks above substring-only match`() {
            val p = SlashCommandAutocompleteProvider {
                listOf(
                    completion("session"),           // startsWith 'se'
                    completion("verbose"),           // contains 'se' but doesn't start with it
                    completion("setup"),             // startsWith 'se'
                )
            }
            val result = p.suggest('/', "se", 10).map { it.label }
            // startsWith tier alphabetized before substring tier.
            assertEquals(listOf("session", "setup", "verbose"), result)
        }

        @Test
        fun `case-insensitive matching`() {
            val p = SlashCommandAutocompleteProvider {
                listOf(completion("HotKeys"), completion("QUIT"))
            }
            val result = p.suggest('/', "h", 10).map { it.label }
            assertEquals(listOf("HotKeys"), result)
        }

        @Test
        fun `no match returns empty`() {
            val p = SlashCommandAutocompleteProvider { listOf(completion("hotkeys"), completion("quit")) }
            assertEquals(emptyList(), p.suggest('/', "xyz", 10))
        }

        @Test
        fun `skill entries are filtered alongside built-ins`() {
            val p = SlashCommandAutocompleteProvider {
                listOf(
                    completion("hotkeys"),
                    completion("skill:code-review", description = "review a file"),
                    completion("skill:refactor", description = "suggest refactors"),
                )
            }
            val result = p.suggest('/', "skill:", 10).map { it.label }
            assertEquals(listOf("skill:code-review", "skill:refactor"), result)
        }

        @Test
        fun `limit truncates the result list`() {
            val p = SlashCommandAutocompleteProvider {
                (1..10).map { completion("cmd$it") }
            }
            val result = p.suggest('/', "", 3)
            assertEquals(3, result.size)
        }
    }

    @Nested
    inner class Liveness {
        @Test
        fun `completions are re-fetched on every suggest call`() {
            var calls = 0
            val p = SlashCommandAutocompleteProvider {
                calls++
                listOf(completion("v$calls"))
            }
            val first = p.suggest('/', "", 10).single().label
            val second = p.suggest('/', "", 10).single().label
            // Each call returns the freshly-built list — matches the
            // "newly-installed skills appear without restart" contract.
            assertEquals("v1", first)
            assertEquals("v2", second)
            assertEquals(2, calls)
        }
    }
}
