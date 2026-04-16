package fraggle.coding.ui

import com.jakewharton.mosaic.terminal.KeyboardEvent
import fraggle.tui.text.stripAnsi
import fraggle.tui.text.visibleWidth
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CodingApp]'s pure helper components and supporting types.
 *
 * End-to-end orchestrator tests would require a real Tty / Terminal; those
 * are covered by Wave 4's integration verification. The tests here cover:
 *  - [SlashCommandRegistry] / [SlashCommand] / [SlashCommandParse] — ported
 *    verbatim from the old `fraggle.coding.tui.SlashCommandTest`.
 *  - The two internal helper components ([NoticeLine], [ErrorLine]) that
 *    power the bottom chrome's ephemeral rows.
 *  - [ApprovalOverlay]'s new input handling (y/n/Esc/Ctrl+C).
 */
class CodingAppTest {

    // ────────────────────────────────────────────────────────────────────
    // Slash commands — port of SlashCommandTest.
    // ────────────────────────────────────────────────────────────────────

    private fun registryWith(names: List<String>): SlashCommandRegistry =
        SlashCommandRegistry(
            names.map { name ->
                SlashCommand(name = name, description = "desc for $name", handler = { })
            },
        )

    @Nested
    inner class SlashCommandParsing {
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
    inner class SlashCommandDispatch {
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
    inner class SlashCommandBuiltIn {
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

    // ────────────────────────────────────────────────────────────────────
    // Bottom-chrome helper components.
    // ────────────────────────────────────────────────────────────────────

    @Nested
    inner class NoticeLineRendering {
        @Test
        fun `single line notice is padded to full width`() {
            val row = NoticeLine("hello world")
            for (line in row.render(80)) {
                assertEquals(80, visibleWidth(line))
            }
        }

        @Test
        fun `notice text appears in accent color`() {
            val row = NoticeLine("welcome")
            val out = row.render(60)
            assertEquals(1, out.size)
            assertTrue(out[0].contains(codingTheme.accent))
            assertTrue(stripAnsi(out[0]).contains("welcome"))
        }

        @Test
        fun `long notice wraps and still obeys width contract`() {
            val long = "this is a very long notice that will definitely wrap across multiple lines"
            val row = NoticeLine(long)
            for (w in listOf(20, 40, 60)) {
                for (line in row.render(w)) {
                    assertTrue(visibleWidth(line) <= w, "width $w: ${stripAnsi(line)}")
                }
            }
        }

        @Test
        fun `zero width returns empty`() {
            assertEquals(emptyList(), NoticeLine("hi").render(0))
        }
    }

    @Nested
    inner class ErrorLineRendering {
        @Test
        fun `error line includes the error prefix and message`() {
            val row = ErrorLine("something went wrong")
            val out = row.render(80)
            val first = stripAnsi(out[0])
            assertTrue(first.contains("!"))
            assertTrue(first.contains("error"))
            assertTrue(first.contains("something went wrong"))
        }

        @Test
        fun `error uses error color`() {
            val row = ErrorLine("boom")
            val out = row.render(60)
            assertTrue(out[0].contains(codingTheme.error))
        }

        @Test
        fun `newlines in error are flattened to spaces`() {
            val row = ErrorLine("line1\nline2\nline3")
            val out = row.render(200)
            val first = stripAnsi(out[0])
            assertTrue(first.contains("line1 line2 line3"), "got: $first")
        }

        @Test
        fun `long error wraps across lines and respects width contract`() {
            val long = "an unusually long error description that will not fit on a single line"
            val row = ErrorLine(long)
            for (w in listOf(30, 40, 60)) {
                for (line in row.render(w)) {
                    assertTrue(visibleWidth(line) <= w, "width $w: ${stripAnsi(line)}")
                }
            }
        }

        @Test
        fun `every rendered line is padded to viewport width`() {
            val row = ErrorLine("x")
            for (line in row.render(40)) {
                assertEquals(40, visibleWidth(line))
            }
        }
    }

    @Nested
    inner class HotkeysHelpConstants {
        @Test
        fun `hotkeys help contains key action entries`() {
            val help = HOTKEYS_HELP.joinToString("\n")
            assertTrue(help.contains("Enter"))
            assertTrue(help.contains("Esc"))
            assertTrue(help.contains("Ctrl+C"))
            assertTrue(help.contains("slash commands"))
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // ApprovalOverlay input handling (new in Wave 3B).
    // ────────────────────────────────────────────────────────────────────

    @Nested
    inner class ApprovalOverlayInput {
        @Test
        fun `y triggers onApprove`() {
            var approved = 0
            val overlay = ApprovalOverlay(
                approval = PendingApproval("t", "{}"),
                onApprove = { approved++ },
                onDeny = { },
            )
            assertTrue(overlay.handleInput(keyChar('y')))
            assertEquals(1, approved)
        }

        @Test
        fun `uppercase Y also approves`() {
            var approved = 0
            val overlay = ApprovalOverlay(
                approval = PendingApproval("t", "{}"),
                onApprove = { approved++ },
                onDeny = { },
            )
            assertTrue(overlay.handleInput(keyChar('Y')))
            assertEquals(1, approved)
        }

        @Test
        fun `n triggers onDeny`() {
            var denied = 0
            val overlay = ApprovalOverlay(
                approval = PendingApproval("t", "{}"),
                onApprove = { },
                onDeny = { denied++ },
            )
            assertTrue(overlay.handleInput(keyChar('n')))
            assertEquals(1, denied)
        }

        @Test
        fun `Esc triggers onDeny`() {
            var denied = 0
            val overlay = ApprovalOverlay(
                approval = PendingApproval("t", "{}"),
                onApprove = { },
                onDeny = { denied++ },
            )
            assertTrue(overlay.handleInput(keyCode(27)))
            assertEquals(1, denied)
        }

        @Test
        fun `Ctrl+C triggers onDeny`() {
            var denied = 0
            val overlay = ApprovalOverlay(
                approval = PendingApproval("t", "{}"),
                onApprove = { },
                onDeny = { denied++ },
            )
            assertTrue(overlay.handleInput(keyChar('c', ctrl = true)))
            assertEquals(1, denied)
        }

        @Test
        fun `arbitrary keys are swallowed without firing callbacks`() {
            var approved = 0
            var denied = 0
            val overlay = ApprovalOverlay(
                approval = PendingApproval("t", "{}"),
                onApprove = { approved++ },
                onDeny = { denied++ },
            )
            assertTrue(overlay.handleInput(keyChar('a')))
            assertTrue(overlay.handleInput(keyChar('z')))
            assertTrue(overlay.handleInput(keyCode(KeyboardEvent.Up)))
            assertEquals(0, approved)
            assertEquals(0, denied)
        }

        @Test
        fun `overlay implements Focusable`() {
            val overlay = ApprovalOverlay(
                approval = PendingApproval("t", "{}"),
            )
            // Smoke test: accessor should be writable.
            overlay.focused = true
            assertTrue(overlay.focused)
            overlay.focused = false
            assertFalse(overlay.focused)
        }
    }

    // ── test helpers ────────────────────────────────────────────────────

    private fun keyChar(c: Char, ctrl: Boolean = false): KeyboardEvent {
        val mods = if (ctrl) KeyboardEvent.ModifierCtrl else 0
        return KeyboardEvent(codepoint = c.code, modifiers = mods)
    }

    private fun keyCode(cp: Int): KeyboardEvent = KeyboardEvent(codepoint = cp)
}
