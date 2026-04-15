package fraggle.coding.tui

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorStateTest {

    @Nested
    inner class ConstructionInvariants {
        @Test
        fun `empty state is valid`() {
            val s = EditorState()
            assertEquals("", s.text)
            assertEquals(0, s.cursor)
            assertTrue(s.isEmpty)
        }

        @Test
        fun `cursor at end of text is valid`() {
            val s = EditorState("hello", 5)
            assertEquals(5, s.cursor)
        }

        @Test
        fun `cursor out of range throws`() {
            assertThrows<IllegalArgumentException> { EditorState("hi", -1) }
            assertThrows<IllegalArgumentException> { EditorState("hi", 3) }
        }
    }

    @Nested
    inner class Typing {
        @Test
        fun `type inserts at cursor and advances it`() {
            val s = EditorState().type('h').type('i')
            assertEquals("hi", s.text)
            assertEquals(2, s.cursor)
            assertFalse(s.isEmpty)
        }

        @Test
        fun `type inserts in the middle of the buffer`() {
            val s = EditorState("hello", cursor = 2).type('X')
            assertEquals("heXllo", s.text)
            assertEquals(3, s.cursor)
        }

        @Test
        fun `typeString inserts multi-char payloads`() {
            val s = EditorState("hi", cursor = 2).typeString(" world")
            assertEquals("hi world", s.text)
            assertEquals(8, s.cursor)
        }

        @Test
        fun `typeString of empty string is a no-op`() {
            val s = EditorState("hello", cursor = 3)
            assertEquals(s, s.typeString(""))
        }

        @Test
        fun `newline inserts a literal newline`() {
            val s = EditorState("hi", cursor = 2).newline().type('.')
            assertEquals("hi\n.", s.text)
            assertEquals(4, s.cursor)
        }
    }

    @Nested
    inner class Deletion {
        @Test
        fun `backspace removes the character behind the cursor`() {
            val s = EditorState("hello", cursor = 5).backspace()
            assertEquals("hell", s.text)
            assertEquals(4, s.cursor)
        }

        @Test
        fun `backspace at cursor 0 is a no-op`() {
            val s = EditorState("hello", cursor = 0).backspace()
            assertEquals("hello", s.text)
            assertEquals(0, s.cursor)
        }

        @Test
        fun `delete removes the character at the cursor`() {
            val s = EditorState("hello", cursor = 2).delete()
            assertEquals("helo", s.text)
            assertEquals(2, s.cursor)
        }

        @Test
        fun `delete at end of text is a no-op`() {
            val s = EditorState("hi", cursor = 2).delete()
            assertEquals("hi", s.text)
        }

        @Test
        fun `clear wipes everything`() {
            val s = EditorState("hello world", cursor = 5).clear()
            assertEquals("", s.text)
            assertEquals(0, s.cursor)
            assertTrue(s.isEmpty)
        }
    }

    @Nested
    inner class SingleLineNavigation {
        @Test
        fun `moveLeft decrements and stops at 0`() {
            val s = EditorState("hi", cursor = 2).moveLeft()
            assertEquals(1, s.cursor)
            assertEquals(0, s.moveLeft().moveLeft().moveLeft().cursor)
        }

        @Test
        fun `moveRight increments and stops at end`() {
            val s = EditorState("hi", cursor = 0).moveRight()
            assertEquals(1, s.cursor)
            assertEquals(2, s.moveRight().moveRight().moveRight().cursor)
        }

        @Test
        fun `moveHome and moveEnd on a single-line buffer`() {
            val s = EditorState("hello", cursor = 3)
            assertEquals(0, s.moveHome().cursor)
            assertEquals(5, s.moveEnd().cursor)
        }
    }

    @Nested
    inner class MultiLineNavigation {
        //  "abc\ndef\nghij"
        //   0123 4567 89..
        //   row0 row1 row2
        private val buf = EditorState("abc\ndef\nghij", cursor = 0)

        @Test
        fun `moveDown preserves column when target line is long enough`() {
            // From start of row 0, moveDown should land on start of row 1
            val s = buf.moveDown()
            assertEquals(4, s.cursor) // 'd' on row 1
        }

        @Test
        fun `moveDown clamps column when target line is shorter`() {
            // Start at col 3 on row 0 (end of "abc")
            val s = EditorState("abc\nde", cursor = 3).moveDown()
            // Row 1 is "de" (length 2), so cursor clamps to end of row 1 = offset 6
            assertEquals(6, s.cursor)
        }

        @Test
        fun `moveDown from last line stays put`() {
            // Start at offset 8 (start of "ghij" on row 2)
            val s = EditorState("abc\ndef\nghij", cursor = 8).moveDown()
            assertEquals(8, s.cursor)
        }

        @Test
        fun `moveUp preserves column`() {
            // Start at "g" on row 2 (offset 8, col 0)
            val s = EditorState("abc\ndef\nghij", cursor = 8).moveUp()
            // Row 1 is "def" — cursor lands at col 0 of row 1 = offset 4
            assertEquals(4, s.cursor)
        }

        @Test
        fun `moveUp clamps column when target line is shorter`() {
            // Start at "j" on row 2 (offset 11, col 3)
            val s = EditorState("abc\ndef\nghij", cursor = 11).moveUp()
            // Row 1 is "def" (length 3), clamp col to 3 → offset 7 (end of row 1)
            assertEquals(7, s.cursor)
        }

        @Test
        fun `moveUp from first line stays put`() {
            val s = EditorState("abc\ndef", cursor = 1).moveUp()
            assertEquals(1, s.cursor)
        }

        @Test
        fun `moveHome and moveEnd on a multi-line buffer work per line`() {
            // Cursor at offset 5 → row 1 col 1 ("e")
            val s = EditorState("abc\ndef\nghij", cursor = 5)
            assertEquals(4, s.moveHome().cursor) // start of row 1
            assertEquals(7, s.moveEnd().cursor)  // end of row 1 ("def")
        }
    }

    @Nested
    inner class Immutability {
        @Test
        fun `every operation returns a fresh instance`() {
            val original = EditorState("hi", cursor = 1)
            original.type('X')
            original.backspace()
            original.moveLeft()
            // Original is unchanged
            assertEquals(EditorState("hi", cursor = 1), original)
        }
    }
}
