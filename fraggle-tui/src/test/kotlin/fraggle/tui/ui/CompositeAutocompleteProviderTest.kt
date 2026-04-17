package fraggle.tui.ui

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CompositeAutocompleteProviderTest {

    private class FakeProvider(
        private val triggers: Set<Char>,
        private val response: Map<Char, List<Autocompletion>> = emptyMap(),
    ) : AutocompleteProvider {
        var calls: Int = 0
            private set

        override fun handlesTrigger(trigger: Char): Boolean = trigger in triggers

        override fun suggest(trigger: Char, prefix: String, limit: Int): List<Autocompletion> {
            calls++
            return response[trigger].orEmpty()
        }
    }

    @Nested
    inner class TriggerRouting {
        @Test
        fun `handlesTrigger returns true when any delegate handles it`() {
            val composite = CompositeAutocompleteProvider(
                FakeProvider(setOf('@')),
                FakeProvider(setOf('/')),
            )
            assertTrue(composite.handlesTrigger('@'))
            assertTrue(composite.handlesTrigger('/'))
            assertFalse(composite.handlesTrigger('#'))
        }

        @Test
        fun `delegates to the first provider that handles the trigger`() {
            val first = FakeProvider(
                triggers = setOf('@'),
                response = mapOf('@' to listOf(Autocompletion("A", "A"))),
            )
            val second = FakeProvider(
                triggers = setOf('@'),
                response = mapOf('@' to listOf(Autocompletion("B", "B"))),
            )
            val composite = CompositeAutocompleteProvider(first, second)

            val out = composite.suggest('@', "", 10)
            assertEquals(listOf(Autocompletion("A", "A")), out)
            assertEquals(1, first.calls)
            assertEquals(0, second.calls, "second provider must not be called when first handles")
        }

        @Test
        fun `returns empty when no provider handles the trigger`() {
            val composite = CompositeAutocompleteProvider(FakeProvider(setOf('@')))
            assertEquals(emptyList(), composite.suggest('#', "x", 10))
        }

        @Test
        fun `routes each trigger to its dedicated delegate`() {
            val atProvider = FakeProvider(
                triggers = setOf('@'),
                response = mapOf('@' to listOf(Autocompletion("file.kt", "file.kt"))),
            )
            val slashProvider = FakeProvider(
                triggers = setOf('/'),
                response = mapOf('/' to listOf(Autocompletion("quit", "quit"))),
            )
            val composite = CompositeAutocompleteProvider(atProvider, slashProvider)

            val atResult = composite.suggest('@', "", 10)
            val slashResult = composite.suggest('/', "", 10)

            assertEquals("file.kt", atResult.single().label)
            assertEquals("quit", slashResult.single().label)
        }
    }

    @Nested
    inner class Isolation {
        @Test
        fun `constructor copies the list so external mutation is not visible`() {
            val mutableList = mutableListOf<AutocompleteProvider>(FakeProvider(setOf('@')))
            val composite = CompositeAutocompleteProvider(mutableList)
            mutableList.clear()

            assertTrue(
                composite.handlesTrigger('@'),
                "clearing the source list after construction must not affect the composite",
            )
        }
    }
}
