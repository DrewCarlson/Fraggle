package fraggle.tui.ui

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LatexPreprocessorTest {

    @Nested
    inner class MathUnwrapping {
        @Test
        fun `dollar-wrapped approx converts to unicode`() {
            val result = LatexPreprocessor.process("the sheet is \$\\approx 70\\%\$ open.")
            assertEquals("the sheet is ≈ 70% open.", result)
        }

        @Test
        fun `double-dollar display math unwraps too`() {
            val result = LatexPreprocessor.process("observe \$\$\\sum x_i\$\$ in the limit")
            assertEquals("observe ∑ x_i in the limit", result)
        }

        @Test
        fun `greek letters convert inside math`() {
            val result = LatexPreprocessor.process("let \$\\alpha + \\beta = \\gamma\$")
            assertEquals("let α + β = γ", result)
        }

        @Test
        fun `non-math dollar signs stay intact`() {
            // `$5` and `$10` are currency — no LaTeX markers inside the
            // $...$ pair, so leave them alone.
            val result = LatexPreprocessor.process("That costs \$5, this costs \$10")
            assertEquals("That costs \$5, this costs \$10", result)
        }

        @Test
        fun `unmatched dollar sign is left alone`() {
            val result = LatexPreprocessor.process("price is \$99 forever")
            assertEquals("price is \$99 forever", result)
        }

        @Test
        fun `escaped dollar is converted outside math too`() {
            val result = LatexPreprocessor.process("prefixed with \\\$ to avoid LaTeX")
            assertEquals("prefixed with \$ to avoid LaTeX", result)
        }
    }

    @Nested
    inner class CommandSubstitution {
        @Test
        fun `long arrows beat short relations`() {
            // `\leftarrow` must not be mis-matched by `\le` when the table is
            // walked — ordering in the command table handles this.
            val result = LatexPreprocessor.process("\$a \\leftarrow b\$")
            assertEquals("a ← b", result)
        }

        @Test
        fun `uppercase Greek precedes lowercase in lookup order`() {
            val result = LatexPreprocessor.process("\$\\Pi \\neq \\pi\$")
            assertEquals("Π ≠ π", result)
        }

        @Test
        fun `escaped punctuation is preserved outside math`() {
            val result = LatexPreprocessor.process("literal \\% and \\& here")
            assertEquals("literal % and & here", result)
        }

        @Test
        fun `unknown commands pass through untouched inside math`() {
            // `\zonk` isn't in our table — emit the literal command sans `$`s
            // so the user at least sees something readable.
            val result = LatexPreprocessor.process("test \$\\zonk + 1\$ done")
            assertEquals("test \\zonk + 1 done", result)
        }

        @Test
        fun `commands outside dollar delimiters also convert`() {
            // A bare `\approx` in prose (which LLMs occasionally emit) should
            // still turn into the symbol — otherwise we'd show raw TeX to the
            // user.
            val result = LatexPreprocessor.process("values \\approx 70")
            assertEquals("values ≈ 70", result)
        }
    }

    @Nested
    inner class NoOp {
        @Test
        fun `plain prose without dollars or backslashes is untouched`() {
            val text = "Just some normal text without math or commands."
            assertEquals(text, LatexPreprocessor.process(text))
        }

        @Test
        fun `empty input returns empty`() {
            assertEquals("", LatexPreprocessor.process(""))
        }

        @Test
        fun `newline inside single-dollar breaks unwrap attempt`() {
            // A `$` at the start of one line and another `$` on a following
            // line shouldn't be interpreted as math — dollars on different
            // lines are almost always separate prose tokens.
            val text = "start \$total\nnext line \$end"
            assertEquals(text, LatexPreprocessor.process(text))
        }
    }
}
