package fraggle.tui.ui

/**
 * Internal data model shared between [MarkdownParser] and [MarkdownEmitter].
 *
 * A [StyledSpan] is a run of text with a single inline style. An [InlineText]
 * is an ordered list of spans — the parser builds these directly from the
 * JetBrains markdown AST, the emitter concatenates them into already-styled
 * ANSI lines and hands them to [fraggle.tui.text.wordWrap] for width-aware
 * wrapping.
 *
 * We deliberately avoid span merging / nested style stacks: every "push style"
 * scope in the parser is flattened into a single [InlineStyle] flag set on the
 * contained spans, which keeps the emitter trivial.
 *
 * Mirrors the data produced by `MarkdownRenderer` in fraggle-coding-agent
 * (`fraggle.coding.tui.markdown.MdBlock`), but with a string-based inline
 * representation instead of a Compose `AnnotatedString`.
 */

/**
 * A single inline style. Bitmask-composable via [InlineStyle.combine] so nested
 * spans (`**_bold italic_**`) can carry both flags.
 */
internal enum class InlineStyleBit(val bit: Int) {
    BOLD(1 shl 0),
    ITALIC(1 shl 1),
    STRIKE(1 shl 2),
    CODE(1 shl 3),
    LINK(1 shl 4),
}

/** Packed bitmask of [InlineStyleBit]s — Int is enough for five flags. */
@JvmInline
internal value class InlineStyle(val mask: Int) {
    fun has(bit: InlineStyleBit): Boolean = (mask and bit.bit) != 0
    fun plus(bit: InlineStyleBit): InlineStyle = InlineStyle(mask or bit.bit)

    companion object {
        val NONE: InlineStyle = InlineStyle(0)
    }
}

/** A run of identically-styled text. */
internal data class StyledSpan(val text: String, val style: InlineStyle = InlineStyle.NONE)

/**
 * A sequence of styled spans. Empty when the source inline content is empty.
 *
 * [isBlank] is a quick check used by the parser to suppress all-whitespace
 * paragraphs (e.g. from stray blank lines inside block structures).
 */
internal data class InlineText(val spans: List<StyledSpan>) {
    val isEmpty: Boolean get() = spans.isEmpty() || spans.all { it.text.isEmpty() }
    fun isBlank(): Boolean = spans.all { it.text.isBlank() }

    companion object {
        val EMPTY: InlineText = InlineText(emptyList())
    }
}

/**
 * One laid-out block. The emitter turns each of these into zero or more
 * already-ANSI-styled, width-constrained lines.
 */
internal sealed class MdBlock {
    /** A paragraph of inline-styled text. */
    data class Paragraph(val text: InlineText) : MdBlock()

    /** ATX or setext heading, level 1-6. */
    data class Heading(val level: Int, val text: InlineText) : MdBlock()

    /**
     * A single list item. Nested items appear as separate [ListItem] entries
     * with a higher [indent]. [bullet] is the rendered marker ("- ", "1. ",
     * "   " for continuation) so the emitter can print it verbatim.
     */
    data class ListItem(val indent: Int, val bullet: String, val text: InlineText) : MdBlock()

    /** A fenced or indented code block. [lang] is empty for indented blocks. */
    data class CodeBlock(val lines: List<String>, val lang: String) : MdBlock()

    /** Blockquote — a flat list of child blocks rendered with a left gutter. */
    data class Quote(val children: List<MdBlock>) : MdBlock()

    /** Horizontal rule (`---`). */
    data object Rule : MdBlock()

    /** Blank line separator between blocks. */
    data object Blank : MdBlock()
}
