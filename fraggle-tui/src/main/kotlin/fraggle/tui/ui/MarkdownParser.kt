package fraggle.tui.ui

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser as JbMarkdownParser

/**
 * Parses markdown source into a flat list of [MdBlock]s suitable for
 * [MarkdownEmitter] to lay out as ANSI-styled lines.
 *
 * This is a port of `fraggle.coding.tui.markdown.MarkdownRenderer` from
 * fraggle-coding-agent. Same JetBrains GFM parser, same block walker. The
 * difference is that inline content is materialized as [InlineText]
 * (plain-string spans + a bitmask style) instead of a Mosaic `AnnotatedString`.
 *
 * Call once per input; internally stateless aside from the parser instance.
 */
internal class MarkdownParser {

    private val flavour = GFMFlavourDescriptor()

    fun parse(text: String): List<MdBlock> {
        if (text.isEmpty()) return emptyList()
        val tree = JbMarkdownParser(flavour).buildMarkdownTreeFromString(text)
        val out = mutableListOf<MdBlock>()
        walkBlocks(tree, text, out, listIndent = 0)
        return out
    }

    // ── Block walker ────────────────────────────────────────────────────────

    private fun walkBlocks(
        node: ASTNode,
        src: String,
        out: MutableList<MdBlock>,
        listIndent: Int,
    ) {
        for (child in node.children) {
            when (child.type) {
                MarkdownElementTypes.PARAGRAPH -> {
                    val inline = renderInline(child, src)
                    if (!inline.isBlank()) out += MdBlock.Paragraph(inline)
                }
                MarkdownElementTypes.ATX_1 -> out += MdBlock.Heading(1, headingText(child, src))
                MarkdownElementTypes.ATX_2 -> out += MdBlock.Heading(2, headingText(child, src))
                MarkdownElementTypes.ATX_3 -> out += MdBlock.Heading(3, headingText(child, src))
                MarkdownElementTypes.ATX_4 -> out += MdBlock.Heading(4, headingText(child, src))
                MarkdownElementTypes.ATX_5 -> out += MdBlock.Heading(5, headingText(child, src))
                MarkdownElementTypes.ATX_6 -> out += MdBlock.Heading(6, headingText(child, src))
                MarkdownElementTypes.SETEXT_1 -> out += MdBlock.Heading(1, setextText(child, src))
                MarkdownElementTypes.SETEXT_2 -> out += MdBlock.Heading(2, setextText(child, src))
                MarkdownElementTypes.UNORDERED_LIST ->
                    renderList(child, src, out, listIndent, ordered = false)
                MarkdownElementTypes.ORDERED_LIST ->
                    renderList(child, src, out, listIndent, ordered = true)
                MarkdownElementTypes.CODE_FENCE -> out += renderFencedCode(child, src)
                MarkdownElementTypes.CODE_BLOCK -> out += renderIndentedCode(child, src)
                MarkdownElementTypes.BLOCK_QUOTE -> {
                    val inner = mutableListOf<MdBlock>()
                    walkBlocks(child, src, inner, listIndent)
                    out += MdBlock.Quote(inner)
                }
                MarkdownTokenTypes.HORIZONTAL_RULE -> out += MdBlock.Rule
                MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE -> Unit
                else -> {
                    // Descend into containers (MARKDOWN_FILE, LINK_DEFINITION, etc.)
                    if (child.children.isNotEmpty()) {
                        walkBlocks(child, src, out, listIndent)
                    }
                }
            }
        }
    }

    // ── Headings ────────────────────────────────────────────────────────────

    private fun headingText(node: ASTNode, src: String): InlineText {
        val spans = mutableListOf<StyledSpan>()
        val builder = SpanBuilder(spans, InlineStyle.NONE)
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.ATX_HEADER, MarkdownTokenTypes.EOL -> Unit
                MarkdownTokenTypes.ATX_CONTENT -> appendInlineChildren(builder, child, src)
                else -> appendInlineNode(builder, child, src)
            }
        }
        return trim(InlineText(spans))
    }

    private fun setextText(node: ASTNode, src: String): InlineText {
        val spans = mutableListOf<StyledSpan>()
        val builder = SpanBuilder(spans, InlineStyle.NONE)
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.SETEXT_1,
                MarkdownTokenTypes.SETEXT_2,
                MarkdownTokenTypes.EOL,
                -> Unit
                MarkdownTokenTypes.SETEXT_CONTENT -> appendInlineChildren(builder, child, src)
                else -> appendInlineNode(builder, child, src)
            }
        }
        return trim(InlineText(spans))
    }

    // ── Lists ───────────────────────────────────────────────────────────────

    private fun renderList(
        node: ASTNode,
        src: String,
        out: MutableList<MdBlock>,
        listIndent: Int,
        ordered: Boolean,
    ) {
        var counter = 1
        for (child in node.children) {
            if (child.type != MarkdownElementTypes.LIST_ITEM) continue
            val bullet = if (ordered) "${counter++}. " else "- "
            var emittedHead = false
            for (sub in child.children) {
                when (sub.type) {
                    MarkdownTokenTypes.LIST_BULLET,
                    MarkdownTokenTypes.LIST_NUMBER,
                    MarkdownTokenTypes.WHITE_SPACE,
                    MarkdownTokenTypes.EOL,
                    -> Unit
                    MarkdownElementTypes.PARAGRAPH -> {
                        val inline = renderInline(sub, src)
                        if (!emittedHead) {
                            out += MdBlock.ListItem(listIndent, bullet, inline)
                            emittedHead = true
                        } else {
                            // Continuation paragraph — align under the bullet text.
                            out += MdBlock.ListItem(
                                indent = listIndent,
                                bullet = " ".repeat(bullet.length),
                                text = inline,
                            )
                        }
                    }
                    MarkdownElementTypes.UNORDERED_LIST -> {
                        if (!emittedHead) {
                            out += MdBlock.ListItem(listIndent, bullet, InlineText.EMPTY)
                            emittedHead = true
                        }
                        renderList(sub, src, out, listIndent + 1, ordered = false)
                    }
                    MarkdownElementTypes.ORDERED_LIST -> {
                        if (!emittedHead) {
                            out += MdBlock.ListItem(listIndent, bullet, InlineText.EMPTY)
                            emittedHead = true
                        }
                        renderList(sub, src, out, listIndent + 1, ordered = true)
                    }
                    MarkdownElementTypes.CODE_FENCE -> {
                        if (!emittedHead) {
                            out += MdBlock.ListItem(listIndent, bullet, InlineText.EMPTY)
                            emittedHead = true
                        }
                        out += renderFencedCode(sub, src)
                    }
                    MarkdownElementTypes.CODE_BLOCK -> {
                        if (!emittedHead) {
                            out += MdBlock.ListItem(listIndent, bullet, InlineText.EMPTY)
                            emittedHead = true
                        }
                        out += renderIndentedCode(sub, src)
                    }
                    else -> {
                        if (sub.children.isNotEmpty()) {
                            if (!emittedHead) {
                                out += MdBlock.ListItem(listIndent, bullet, InlineText.EMPTY)
                                emittedHead = true
                            }
                            walkBlocks(sub, src, out, listIndent + 1)
                        }
                    }
                }
            }
            if (!emittedHead) {
                out += MdBlock.ListItem(listIndent, bullet, InlineText.EMPTY)
            }
        }
    }

    // ── Code blocks ─────────────────────────────────────────────────────────

    private fun renderFencedCode(node: ASTNode, src: String): MdBlock.CodeBlock {
        var lang = ""
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        var seenContent = false
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.FENCE_LANG -> lang = child.getTextInNode(src).toString().trim()
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> {
                    currentLine.append(child.getTextInNode(src))
                    seenContent = true
                }
                MarkdownTokenTypes.EOL -> {
                    if (seenContent) {
                        lines += currentLine.toString()
                        currentLine = StringBuilder()
                    }
                }
                else -> Unit
            }
        }
        if (currentLine.isNotEmpty()) lines += currentLine.toString()
        return MdBlock.CodeBlock(lines, lang)
    }

    private fun renderIndentedCode(node: ASTNode, src: String): MdBlock.CodeBlock {
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.CODE_LINE -> currentLine.append(child.getTextInNode(src))
                MarkdownTokenTypes.EOL -> {
                    lines += currentLine.toString()
                    currentLine = StringBuilder()
                }
                else -> Unit
            }
        }
        if (currentLine.isNotEmpty()) lines += currentLine.toString()
        return MdBlock.CodeBlock(lines, lang = "")
    }

    // ── Inline ──────────────────────────────────────────────────────────────

    /**
     * Carries a target list of [StyledSpan]s plus the currently-active
     * [InlineStyle] so nested styled scopes (`**_bold italic_**`) can just
     * combine bits rather than using a stack.
     */
    private class SpanBuilder(val out: MutableList<StyledSpan>, val style: InlineStyle) {
        fun append(text: String) {
            if (text.isEmpty()) return
            // Coalesce with the previous span if it has the same style.
            val last = out.lastOrNull()
            if (last != null && last.style == style) {
                out[out.size - 1] = StyledSpan(last.text + text, style)
            } else {
                out += StyledSpan(text, style)
            }
        }

        fun nested(bit: InlineStyleBit): SpanBuilder = SpanBuilder(out, style.plus(bit))
    }

    private fun renderInline(node: ASTNode, src: String): InlineText {
        val spans = mutableListOf<StyledSpan>()
        appendInlineChildren(SpanBuilder(spans, InlineStyle.NONE), node, src)
        return InlineText(spans)
    }

    private fun appendInlineChildren(builder: SpanBuilder, node: ASTNode, src: String) {
        for (child in node.children) appendInlineNode(builder, child, src)
    }

    private fun appendInlineNode(builder: SpanBuilder, node: ASTNode, src: String) {
        when (node.type) {
            MarkdownElementTypes.STRONG ->
                appendInlineContent(builder.nested(InlineStyleBit.BOLD), node, src)
            MarkdownElementTypes.EMPH ->
                appendInlineContent(builder.nested(InlineStyleBit.ITALIC), node, src)
            GFMElementTypes.STRIKETHROUGH ->
                appendInlineContent(builder.nested(InlineStyleBit.STRIKE), node, src)
            MarkdownElementTypes.CODE_SPAN ->
                appendCodeSpan(builder.nested(InlineStyleBit.CODE), node, src)
            MarkdownElementTypes.INLINE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            MarkdownElementTypes.FULL_REFERENCE_LINK,
            -> appendLinkText(builder.nested(InlineStyleBit.LINK), node, src)
            MarkdownElementTypes.AUTOLINK -> {
                val raw = node.getTextInNode(src).toString().trim('<', '>')
                builder.nested(InlineStyleBit.LINK).append(raw)
            }
            MarkdownTokenTypes.HARD_LINE_BREAK -> builder.append("\n")
            MarkdownTokenTypes.EOL -> builder.append(" ")
            else -> {
                if (node.children.isEmpty()) {
                    builder.append(node.getTextInNode(src).toString())
                } else {
                    appendInlineChildren(builder, node, src)
                }
            }
        }
    }

    /**
     * Walks the inner children of a composite inline node (STRONG/EMPH/etc.)
     * while skipping the opening/closing marker tokens that the JetBrains
     * parser keeps as separate child nodes.
     */
    private fun appendInlineContent(builder: SpanBuilder, node: ASTNode, src: String) {
        for (child in node.children) {
            if (child.children.isEmpty() && child.type == MarkdownTokenTypes.EMPH) continue
            if (child.children.isEmpty() && child.type == GFMTokenTypes.TILDE) continue
            appendInlineNode(builder, child, src)
        }
    }

    private fun appendCodeSpan(builder: SpanBuilder, node: ASTNode, src: String) {
        for (child in node.children) {
            if (child.children.isEmpty() && child.type == MarkdownTokenTypes.BACKTICK) continue
            if (child.children.isEmpty()) {
                builder.append(child.getTextInNode(src).toString())
            } else {
                appendInlineChildren(builder, child, src)
            }
        }
    }

    private fun appendLinkText(builder: SpanBuilder, node: ASTNode, src: String) {
        val linkText = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
        if (linkText != null) {
            for (child in linkText.children) {
                if (child.children.isEmpty() && (child.type == MarkdownTokenTypes.LBRACKET || child.type == MarkdownTokenTypes.RBRACKET)) continue
                appendInlineNode(builder, child, src)
            }
        } else {
            builder.append(node.getTextInNode(src).toString())
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────────

    /** Trim leading/trailing whitespace across [InlineText]'s spans. */
    private fun trim(inline: InlineText): InlineText {
        if (inline.spans.isEmpty()) return inline
        val trimmed = inline.spans.toMutableList()

        // Left-trim
        while (trimmed.isNotEmpty()) {
            val first = trimmed[0]
            val ltrimmed = first.text.trimStart()
            if (ltrimmed.isEmpty()) {
                trimmed.removeAt(0)
            } else {
                if (ltrimmed != first.text) {
                    trimmed[0] = StyledSpan(ltrimmed, first.style)
                }
                break
            }
        }

        // Right-trim
        while (trimmed.isNotEmpty()) {
            val last = trimmed[trimmed.size - 1]
            val rtrimmed = last.text.trimEnd()
            if (rtrimmed.isEmpty()) {
                trimmed.removeAt(trimmed.size - 1)
            } else {
                if (rtrimmed != last.text) {
                    trimmed[trimmed.size - 1] = StyledSpan(rtrimmed, last.style)
                }
                break
            }
        }

        return InlineText(trimmed)
    }
}
