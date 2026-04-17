package fraggle.tui.ui

import com.jakewharton.mosaic.terminal.KeyboardEvent
import fraggle.tui.text.visibleWidth
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorAutocompleteTest {

    // ── Key builders ───────────────────────────────────────────────────────
    //
    // Mirrors the helpers in EditorTest so the two files read the same.

    private fun key(cp: Int, ctrl: Boolean = false, shift: Boolean = false): KeyboardEvent {
        var modifiers = 0
        if (ctrl) modifiers = modifiers or KeyboardEvent.ModifierCtrl
        if (shift) modifiers = modifiers or KeyboardEvent.ModifierShift
        return KeyboardEvent(codepoint = cp, modifiers = modifiers)
    }

    private fun char(c: Char): KeyboardEvent = key(c.code)
    private fun enter(shift: Boolean = false): KeyboardEvent = key(13, shift = shift)
    private fun backspace(): KeyboardEvent = key(127)
    private val arrowLeft: KeyboardEvent = key(KeyboardEvent.Left)
    private val arrowRight: KeyboardEvent = key(KeyboardEvent.Right)
    private val arrowUp: KeyboardEvent = key(KeyboardEvent.Up)
    private val arrowDown: KeyboardEvent = key(KeyboardEvent.Down)
    private val tab: KeyboardEvent = key(9)
    private val esc: KeyboardEvent = key(27)

    private fun Editor.type(s: String) {
        for (ch in s) handleInput(char(ch))
    }

    // ── Test-only provider ─────────────────────────────────────────────────

    /**
     * In-test provider that supplies a fixed list for one trigger char.
     * Records every call to [suggest] so we can verify re-queries.
     */
    private class FakeProvider(
        private val triggerChar: Char,
        private val entries: (String) -> List<Autocompletion>,
    ) : AutocompleteProvider {
        data class Call(val trigger: Char, val prefix: String, val limit: Int)

        val calls: MutableList<Call> = mutableListOf()

        override fun handlesTrigger(trigger: Char): Boolean = trigger == triggerChar

        override fun suggest(trigger: Char, prefix: String, limit: Int): List<Autocompletion> {
            calls += Call(trigger, prefix, limit)
            return entries(prefix)
        }
    }

    private fun fixedList(vararg labels: String): FakeProvider =
        FakeProvider('@') { _ ->
            labels.map { Autocompletion(label = it, replacement = it) }
        }

    private fun emptyProvider(): FakeProvider = FakeProvider('@') { emptyList() }

    // ── Activation ─────────────────────────────────────────────────────────

    @Nested
    inner class Activation {
        @Test
        fun `at start of buffer activates popup`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("src/Foo.kt", "src/Bar.kt"))

            e.handleInput(char('@'))

            assertTrue(e.isAutocompleteActive(), "popup should be open after typing @ at buffer start")
            assertEquals("src/Foo.kt", e.autocompleteSelection()?.label)
        }

        @Test
        fun `at after whitespace activates`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("alpha", "beta"))

            e.type("hello ")
            e.handleInput(char('@'))

            assertTrue(e.isAutocompleteActive(), "popup should open after whitespace + @")
        }

        @Test
        fun `at mid-word does NOT activate`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("foo"))

            e.type("foo") // no whitespace
            e.handleInput(char('@'))

            assertFalse(e.isAutocompleteActive(),
                "popup must not open when @ follows a non-whitespace char (email-style)")
        }

        @Test
        fun `provider returning empty does not activate popup`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(emptyProvider())

            e.handleInput(char('@'))

            assertFalse(e.isAutocompleteActive(),
                "empty suggestion list should leave popup closed")
        }

        @Test
        fun `trigger char not handled by provider does not activate`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(FakeProvider('#') { listOf(Autocompletion("x", "x")) })

            e.handleInput(char('@'))

            assertFalse(e.isAutocompleteActive(),
                "@ should not activate when provider only handles #")
        }

        @Test
        fun `null provider disables autocomplete`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("a"))
            e.handleInput(char('@'))
            assertTrue(e.isAutocompleteActive())

            e.setAutocompleteProvider(null)
            assertFalse(e.isAutocompleteActive(), "setting provider to null should clear state")
        }
    }

    // ── Refresh on keystroke ───────────────────────────────────────────────

    @Nested
    inner class Refresh {
        @Test
        fun `typing more chars re-queries provider with extended prefix`() {
            val provider = FakeProvider('@') { prefix ->
                if (prefix.isEmpty()) listOf(Autocompletion("alpha", "alpha"), Autocompletion("beta", "beta"))
                else if (prefix.startsWith("a")) listOf(Autocompletion("alpha", "alpha"))
                else emptyList()
            }
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(provider)

            e.handleInput(char('@'))
            // initial call with prefix=""
            assertTrue(provider.calls.isNotEmpty())
            val callsBefore = provider.calls.size

            e.handleInput(char('a'))

            assertTrue(provider.calls.size > callsBefore, "should re-query after char typed")
            val lastCall = provider.calls.last()
            assertEquals("a", lastCall.prefix)
            assertTrue(e.isAutocompleteActive())
            assertEquals("alpha", e.autocompleteSelection()?.label)
        }

        @Test
        fun `whitespace after trigger dismisses popup`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("alpha"))

            e.handleInput(char('@'))
            assertTrue(e.isAutocompleteActive())

            e.handleInput(char(' '))

            assertFalse(e.isAutocompleteActive(),
                "space in prefix ends the token and dismisses popup")
        }

        @Test
        fun `backspacing the trigger char dismisses`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("alpha"))

            e.handleInput(char('@'))
            assertTrue(e.isAutocompleteActive())

            e.handleInput(backspace())

            assertFalse(e.isAutocompleteActive(),
                "deleting the trigger char should dismiss popup")
            assertEquals("", e.text())
        }

        @Test
        fun `refresh that returns empty list dismisses popup`() {
            val provider = FakeProvider('@') { prefix ->
                if (prefix.isEmpty()) listOf(Autocompletion("a", "a"))
                else emptyList()
            }
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(provider)

            e.handleInput(char('@'))
            assertTrue(e.isAutocompleteActive())

            e.handleInput(char('x')) // no completions for "x"

            assertFalse(e.isAutocompleteActive())
        }

        @Test
        fun `cursor moving left of anchor dismisses popup`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("alpha"))

            // Type "hi @" — trigger activates popup with empty prefix.
            e.type("hi ")
            e.handleInput(char('@'))
            assertTrue(e.isAutocompleteActive())

            // Move cursor onto the @ position (cursor was at anchor+1; left once → anchor).
            e.handleInput(arrowLeft)

            assertFalse(e.isAutocompleteActive(),
                "cursor at or before the anchor should dismiss popup")
        }

        @Test
        fun `cursor moving right within valid prefix keeps popup open`() {
            // Set up a buffer like "hi @abc" with cursor in the middle and an active popup.
            // We can't set the state directly, but we can simulate it by typing, dismissing
            // internal state by moving, and retyping — or simply: type "@", then "a", then
            // move left + right once; popup should remain active across that round-trip.
            val provider = FakeProvider('@') { prefix ->
                when {
                    prefix.isEmpty() -> listOf(Autocompletion("alpha", "alpha"))
                    prefix == "a" -> listOf(Autocompletion("alpha", "alpha"))
                    else -> emptyList()
                }
            }
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(provider)

            e.handleInput(char('@'))
            e.handleInput(char('a'))
            assertTrue(e.isAutocompleteActive())

            // Move left: cursor between @ and a. Prefix becomes "" — still valid, popup stays.
            e.handleInput(arrowLeft)
            assertTrue(e.isAutocompleteActive(),
                "cursor between @ and 'a' keeps popup open with empty prefix")

            // Move right: cursor back after 'a'. Prefix "a" — still valid.
            e.handleInput(arrowRight)
            assertTrue(e.isAutocompleteActive(),
                "cursor past the prefix keeps popup open")
        }
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    @Nested
    inner class Navigation {
        @Test
        fun `Down moves selection forward`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("a", "b", "c"))

            e.handleInput(char('@'))
            assertEquals("a", e.autocompleteSelection()?.label)

            e.handleInput(arrowDown)
            assertEquals("b", e.autocompleteSelection()?.label)

            e.handleInput(arrowDown)
            assertEquals("c", e.autocompleteSelection()?.label)
        }

        @Test
        fun `Up moves selection backward`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("a", "b", "c"))

            e.handleInput(char('@'))
            e.handleInput(arrowDown)
            e.handleInput(arrowDown)
            assertEquals("c", e.autocompleteSelection()?.label)

            e.handleInput(arrowUp)
            assertEquals("b", e.autocompleteSelection()?.label)
        }

        @Test
        fun `Down at bottom is bounded`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("only"))

            e.handleInput(char('@'))
            assertEquals("only", e.autocompleteSelection()?.label)

            e.handleInput(arrowDown)
            assertEquals("only", e.autocompleteSelection()?.label,
                "Down should clamp at last item")
        }

        @Test
        fun `Up at top is bounded`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("a", "b"))

            e.handleInput(char('@'))
            assertEquals("a", e.autocompleteSelection()?.label)
            e.handleInput(arrowUp)
            assertEquals("a", e.autocompleteSelection()?.label,
                "Up should clamp at index 0")
        }

        @Test
        fun `arrow keys fall through to buffer navigation when popup closed`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("a"))
            // No popup opened: arrowDown should act as normal cursor Down (no-op here, but
            // specifically: it must not return-true-without-doing-anything inappropriately).
            e.type("x")
            e.handleInput(arrowLeft)
            assertEquals(0, e.cursorPos())
        }
    }

    // ── Accept ─────────────────────────────────────────────────────────────

    @Nested
    inner class Accept {
        @Test
        fun `Tab accepts and inserts replacement plus trailing space`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(
                FakeProvider('@') { listOf(Autocompletion("src/Foo.kt", "src/Foo.kt", trailingSpace = true)) }
            )

            e.handleInput(char('@'))
            e.handleInput(tab)

            assertFalse(e.isAutocompleteActive(), "Tab accept should dismiss popup")
            assertEquals("@src/Foo.kt ", e.text(),
                "Tab must replace prefix with completion + trailing space; got '${e.text()}'")
        }

        @Test
        fun `Enter accepts (same as Tab) and does not submit while popup active`() {
            val submitted = mutableListOf<String>()
            val e = Editor(onSubmit = { submitted += it })
            e.focused = true
            e.setAutocompleteProvider(
                FakeProvider('@') { listOf(Autocompletion("x.kt", "x.kt")) }
            )

            e.handleInput(char('@'))
            e.handleInput(enter())

            assertTrue(submitted.isEmpty(),
                "Enter must not fire onSubmit while popup is active; got $submitted")
            assertFalse(e.isAutocompleteActive())
            assertEquals("@x.kt ", e.text())
        }

        @Test
        fun `accept replaces only the prefix range, keeping surrounding text`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(
                FakeProvider('@') { prefix ->
                    if (prefix.startsWith("al") || prefix.isEmpty()) listOf(Autocompletion("alpha", "alpha"))
                    else if (prefix == "a") listOf(Autocompletion("alpha", "alpha"))
                    else emptyList()
                }
            )

            e.type("read ")
            e.handleInput(char('@'))
            e.handleInput(char('a'))
            e.handleInput(tab)

            assertEquals("read @alpha ", e.text())
        }

        @Test
        fun `accept without trailing space inserts exactly the replacement`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(
                FakeProvider('@') { listOf(Autocompletion("dir/", "dir/", trailingSpace = false)) }
            )

            e.handleInput(char('@'))
            e.handleInput(tab)

            assertTrue(e.text().startsWith("@dir/"), "got '${e.text()}'")
            assertFalse(e.text().endsWith(" "),
                "no trailing space when trailingSpace=false; got '${e.text()}'")
        }

        @Test
        fun `directory accept keeps popup open for drill-in completion`() {
            val provider = FakeProvider('@') { prefix ->
                when {
                    prefix.isEmpty() -> listOf(
                        Autocompletion("src/", "src/", trailingSpace = false, continueCompletion = true),
                    )
                    prefix == "src/" -> listOf(
                        Autocompletion("src/Main.kt", "src/Main.kt", trailingSpace = true),
                    )
                    else -> emptyList()
                }
            }
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(provider)

            e.handleInput(char('@'))
            assertEquals("src/", e.autocompleteSelection()?.label)

            e.handleInput(tab)

            assertTrue(e.isAutocompleteActive(),
                "popup should remain open after accepting a directory entry")
            // Buffer shows the chosen dir after the @.
            assertEquals("@src/", e.text())
            // Selection is now the file inside the dir.
            assertEquals("src/Main.kt", e.autocompleteSelection()?.label)
        }

        @Test
        fun `Tab with no active popup falls through to default behavior`() {
            val e = Editor()
            e.focused = true
            // No provider → Tab does nothing (or whatever the default handler does).
            // We only assert that no crash occurs and buffer is unchanged.
            e.type("abc")
            e.handleInput(tab)
            assertEquals("abc", e.text())
        }
    }

    // ── Dismiss ────────────────────────────────────────────────────────────

    @Nested
    inner class Dismiss {
        @Test
        fun `Esc dismisses without accepting, buffer unchanged`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("alpha", "beta"))

            e.type("note ")
            e.handleInput(char('@'))
            e.handleInput(char('a'))
            assertTrue(e.isAutocompleteActive())

            val before = e.text()
            e.handleInput(esc)

            assertFalse(e.isAutocompleteActive())
            assertEquals(before, e.text(),
                "Esc must not modify the buffer; got '${e.text()}' vs '$before'")
        }

        @Test
        fun `autocompleteSelection returns null when inactive`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("a"))
            assertNull(e.autocompleteSelection())
            e.handleInput(char('@'))
            assertNotNull(e.autocompleteSelection())
            e.handleInput(esc)
            assertNull(e.autocompleteSelection())
        }
    }

    // ── Render ─────────────────────────────────────────────────────────────

    @Nested
    inner class Rendering {
        @Test
        fun `popup rows appear after body and every row matches width`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("src/Foo.kt", "src/Bar.kt", "README.md"))

            e.handleInput(char('@'))

            val width = 40
            val rendered = e.render(width)
            for ((idx, line) in rendered.withIndex()) {
                assertEquals(width, visibleWidth(line),
                    "row $idx should have visibleWidth=$width; got ${visibleWidth(line)}")
            }
            // Popup adds at least one row beyond divider+body.
            // Minimum: 1 divider + 1 body (buffer has "@") + 3 popup rows = 5 rows.
            assertTrue(rendered.size >= 5,
                "expected at least 5 rows (divider + body + 3 popup), got ${rendered.size}")
        }

        @Test
        fun `popup hides when dismissed`() {
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(fixedList("x", "y"))
            e.handleInput(char('@'))
            val withPopup = e.render(30)

            e.handleInput(esc)
            val withoutPopup = e.render(30)

            assertTrue(withPopup.size > withoutPopup.size,
                "popup rows should disappear after Esc; before=${withPopup.size} after=${withoutPopup.size}")
        }

        @Test
        fun `setAutocompleteCap limits visible popup rows`() {
            val provider = FakeProvider('@') {
                (1..20).map { Autocompletion(label = "item$it", replacement = "item$it") }
            }
            val e = Editor()
            e.focused = true
            e.setAutocompleteProvider(provider)
            e.setAutocompleteCap(3)

            e.handleInput(char('@'))

            // The provider is called with limit=3 (per setAutocompleteCap), so only 3 items
            // come back.
            val lastCall = provider.calls.last()
            assertEquals(3, lastCall.limit)

            // And when rendered, popup has at most 3 rows.
            val rendered = e.render(40)
            // Divider + body (1 row since just "@") + popup rows. Popup rows ≤ 3.
            val popupRows = rendered.size - 2
            assertTrue(popupRows in 1..3,
                "expected 1..3 popup rows, got $popupRows (total=${rendered.size})")
        }
    }
}
