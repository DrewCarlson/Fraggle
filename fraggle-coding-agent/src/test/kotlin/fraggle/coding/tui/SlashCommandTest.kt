package fraggle.coding.tui

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SlashCommandTest {

    private fun registryWith(names: List<String>): SlashCommandRegistry =
        SlashCommandRegistry(
            names.map { name ->
                SlashCommand(name = name, description = "desc for $name", handler = { })
            },
        )

    @Nested
    inner class Parsing {
        @Test
        fun `non-slash input returns null so caller treats it as a prompt`() {
            val registry = registryWith(listOf("new", "quit"))
            assertNull(registry.parse("hello"))
            assertNull(registry.parse(""))
            assertNull(registry.parse("  hello /not-a-command"))
        }

        @Test
        fun `bare slash returns Unknown with empty name`() {
            val registry = registryWith(listOf("new"))
            val result = registry.parse("/")
            val unknown = assertIs<SlashCommandParse.Unknown>(result)
            assertEquals("", unknown.name)
        }

        @Test
        fun `known command with no args matches`() {
            val registry = registryWith(listOf("new", "quit"))
            val result = registry.parse("/new")
            val matched = assertIs<SlashCommandParse.Matched>(result)
            assertEquals("new", matched.command.name)
            assertEquals("", matched.args)
        }

        @Test
        fun `known command with args captures everything after the name`() {
            val registry = registryWith(listOf("name"))
            val result = registry.parse("/name my cool session")
            val matched = assertIs<SlashCommandParse.Matched>(result)
            assertEquals("name", matched.command.name)
            assertEquals("my cool session", matched.args)
        }

        @Test
        fun `command matching is case-insensitive`() {
            val registry = registryWith(listOf("new"))
            val result = registry.parse("/NEW")
            assertIs<SlashCommandParse.Matched>(result)
        }

        @Test
        fun `unknown command name returns Unknown with the typed name`() {
            val registry = registryWith(listOf("new", "quit"))
            val result = registry.parse("/foo bar")
            val unknown = assertIs<SlashCommandParse.Unknown>(result)
            assertEquals("foo", unknown.name)
        }

        @Test
        fun `leading whitespace after the slash is tolerated`() {
            val registry = registryWith(listOf("new"))
            val result = registry.parse("/  new")
            val matched = assertIs<SlashCommandParse.Matched>(result)
            assertEquals("new", matched.command.name)
        }
    }

    @Nested
    inner class HandlerDispatch {
        @Test
        fun `matched handler can be invoked manually with the parsed args`() {
            var captured: String? = null
            val registry = SlashCommandRegistry(
                listOf(
                    SlashCommand(
                        name = "say",
                        description = "echo",
                        handler = { args -> captured = args },
                    ),
                ),
            )
            val matched = registry.parse("/say hello there") as SlashCommandParse.Matched
            matched.command.handler(matched.args)
            assertEquals("hello there", captured)
        }
    }

    @Nested
    inner class BuiltIn {
        @Test
        fun `builtIn registers new, quit, hotkeys, session`() {
            var newCount = 0
            var quitCount = 0
            var hotkeysCount = 0
            var sessionCount = 0
            val registry = SlashCommandRegistry.builtIn(
                onNewSession = { newCount++ },
                onQuit = { quitCount++ },
                onHotkeys = { hotkeysCount++ },
                onSessionInfo = { sessionCount++ },
            )
            assertEquals(setOf("new", "quit", "hotkeys", "session"), registry.commands.map { it.name }.toSet())

            (registry.parse("/new") as SlashCommandParse.Matched).let { it.command.handler(it.args) }
            (registry.parse("/quit") as SlashCommandParse.Matched).let { it.command.handler(it.args) }
            (registry.parse("/hotkeys") as SlashCommandParse.Matched).let { it.command.handler(it.args) }
            (registry.parse("/session") as SlashCommandParse.Matched).let { it.command.handler(it.args) }

            assertEquals(1, newCount)
            assertEquals(1, quitCount)
            assertEquals(1, hotkeysCount)
            assertEquals(1, sessionCount)
        }

        @Test
        fun `builtIn commands sort alphabetically for display`() {
            val registry = SlashCommandRegistry.builtIn(
                onNewSession = {},
                onQuit = {},
                onHotkeys = {},
                onSessionInfo = {},
            )
            assertEquals(listOf("hotkeys", "new", "quit", "session"), registry.commands.map { it.name })
        }
    }
}
