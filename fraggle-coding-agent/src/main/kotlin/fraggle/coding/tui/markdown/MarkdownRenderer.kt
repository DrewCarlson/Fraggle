package fraggle.coding.tui.markdown

import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * One laid-out block of rendered markdown. The composable layer turns each
 * [MdBlock] into one or more Mosaic `Text` components stacked in a `Column`.
 *
 * This mirrors the token-walking approach in pi-mono's
 * `packages/tui/src/components/markdown.ts`, but instead of producing
 * ANSI-wrapped strings we produce Mosaic [AnnotatedString]s so the TUI
 * can lay them out and handle wrapping natively.
 */
sealed class MdBlock {
    /** A paragraph of inline-styled text. May span multiple visual lines after wrapping. */
    data class Paragraph(val text: AnnotatedString) : MdBlock()

    /** ATX or setext heading, level 1-6. */
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock()

    /**
     * A single list item. Nested items appear as separate [ListItem] entries
     * with a higher [indent]. [bullet] is already the rendered marker ("- ",
     * "1. ", etc.) so the composable can print it verbatim in the bullet color.
     */
    data class ListItem(val indent: Int, val bullet: String, val text: AnnotatedString) : MdBlock()

    /** A fenced or indented code block. [lang] is empty for indented blocks. */
    data class CodeBlock(val lines: List<String>, val lang: String) : MdBlock()

    /** Blockquote — a flat list of child blocks rendered with a left gutter. */
    data class Quote(val children: List<MdBlock>) : MdBlock()

    /** Horizontal rule (`---`). */
    object Rule : MdBlock()

    /** Blank line separator between blocks. */
    object Blank : MdBlock()
}

/**
 * Parses [text] with the CommonMark-compliant JetBrains markdown parser
 * (GFM flavour, so strikethrough works) and produces a flat list of
 * [MdBlock]s ready for the [Markdown] composable to render.
 *
 * Re-parsing on every streaming tick is cheap enough at typical LLM output
 * sizes; pi-mono's TS implementation does the same.
 */
class MarkdownRenderer(private val theme: MarkdownTheme) {

    fun render(text: String): List<MdBlock> {
        if (text.isEmpty()) return emptyList()
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(text)
        val out = mutableListOf<MdBlock>()
        walkBlocks(tree, text, out, listIndent = 0)
        return out
    }

    /**
     * Recursively walk composite children of [node], emitting one or more
     * [MdBlock]s per block-level child. List nesting is tracked via
     * [listIndent] so nested items appear with a deeper indent.
     */
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
                    if (inline.isNotBlank()) out.add(MdBlock.Paragraph(inline))
                }
                MarkdownElementTypes.ATX_1 -> out.add(MdBlock.Heading(1, headingText(child, src)))
                MarkdownElementTypes.ATX_2 -> out.add(MdBlock.Heading(2, headingText(child, src)))
                MarkdownElementTypes.ATX_3 -> out.add(MdBlock.Heading(3, headingText(child, src)))
                MarkdownElementTypes.ATX_4 -> out.add(MdBlock.Heading(4, headingText(child, src)))
                MarkdownElementTypes.ATX_5 -> out.add(MdBlock.Heading(5, headingText(child, src)))
                MarkdownElementTypes.ATX_6 -> out.add(MdBlock.Heading(6, headingText(child, src)))
                MarkdownElementTypes.SETEXT_1 -> out.add(MdBlock.Heading(1, setextText(child, src)))
                MarkdownElementTypes.SETEXT_2 -> out.add(MdBlock.Heading(2, setextText(child, src)))
                MarkdownElementTypes.UNORDERED_LIST -> renderList(child, src, out, listIndent, ordered = false)
                MarkdownElementTypes.ORDERED_LIST -> renderList(child, src, out, listIndent, ordered = true)
                MarkdownElementTypes.CODE_FENCE -> out.add(renderFencedCode(child, src))
                MarkdownElementTypes.CODE_BLOCK -> out.add(renderIndentedCode(child, src))
                MarkdownElementTypes.BLOCK_QUOTE -> {
                    val inner = mutableListOf<MdBlock>()
                    walkBlocks(child, src, inner, listIndent)
                    out.add(MdBlock.Quote(inner))
                }
                MarkdownTokenTypes.HORIZONTAL_RULE -> out.add(MdBlock.Rule)
                MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE -> Unit
                else -> {
                    // Descend into anything else (MARKDOWN_FILE, LINK_DEFINITION, etc.)
                    if (child.children.isNotEmpty()) {
                        walkBlocks(child, src, out, listIndent)
                    }
                }
            }
        }
    }

    private fun headingText(node: ASTNode, src: String): AnnotatedString = buildAnnotatedString {
        withStyle(theme.heading) {
            for (child in node.children) {
                when (child.type) {
                    MarkdownTokenTypes.ATX_HEADER, MarkdownTokenTypes.EOL -> Unit
                    MarkdownTokenTypes.ATX_CONTENT -> appendInlineChildren(this, child, src)
                    else -> appendInlineNode(this, child, src)
                }
            }
        }
    }.trim()

    private fun setextText(node: ASTNode, src: String): AnnotatedString = buildAnnotatedString {
        withStyle(theme.heading) {
            for (child in node.children) {
                when (child.type) {
                    MarkdownTokenTypes.SETEXT_1, MarkdownTokenTypes.SETEXT_2, MarkdownTokenTypes.EOL -> Unit
                    MarkdownTokenTypes.SETEXT_CONTENT -> appendInlineChildren(this, child, src)
                    else -> appendInlineNode(this, child, src)
                }
            }
        }
    }.trim()

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
            // Split the list item's own inline content from any nested lists/blocks.
            var emittedHead = false
            for (sub in child.children) {
                when (sub.type) {
                    MarkdownTokenTypes.LIST_BULLET, MarkdownTokenTypes.LIST_NUMBER,
                    MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.EOL -> Unit
                    MarkdownElementTypes.PARAGRAPH -> {
                        val inline = renderInline(sub, src)
                        if (!emittedHead) {
                            out.add(MdBlock.ListItem(listIndent, bullet, inline))
                            emittedHead = true
                        } else {
                            // Continuation paragraph — align under the bullet text.
                            out.add(
                                MdBlock.ListItem(
                                    indent = listIndent,
                                    bullet = " ".repeat(bullet.length),
                                    text = inline,
                                ),
                            )
                        }
                    }
                    MarkdownElementTypes.UNORDERED_LIST -> {
                        if (!emittedHead) {
                            out.add(MdBlock.ListItem(listIndent, bullet, EMPTY_ANNOTATED))
                            emittedHead = true
                        }
                        renderList(sub, src, out, listIndent + 1, ordered = false)
                    }
                    MarkdownElementTypes.ORDERED_LIST -> {
                        if (!emittedHead) {
                            out.add(MdBlock.ListItem(listIndent, bullet, EMPTY_ANNOTATED))
                            emittedHead = true
                        }
                        renderList(sub, src, out, listIndent + 1, ordered = true)
                    }
                    MarkdownElementTypes.CODE_FENCE -> {
                        if (!emittedHead) {
                            out.add(MdBlock.ListItem(listIndent, bullet, EMPTY_ANNOTATED))
                            emittedHead = true
                        }
                        out.add(renderFencedCode(sub, src))
                    }
                    MarkdownElementTypes.CODE_BLOCK -> {
                        if (!emittedHead) {
                            out.add(MdBlock.ListItem(listIndent, bullet, EMPTY_ANNOTATED))
                            emittedHead = true
                        }
                        out.add(renderIndentedCode(sub, src))
                    }
                    else -> {
                        // Fallback — recurse so nested blockquotes etc. are still emitted.
                        if (sub.children.isNotEmpty()) {
                            if (!emittedHead) {
                                out.add(MdBlock.ListItem(listIndent, bullet, EMPTY_ANNOTATED))
                                emittedHead = true
                            }
                            walkBlocks(sub, src, out, listIndent + 1)
                        }
                    }
                }
            }
            if (!emittedHead) {
                out.add(MdBlock.ListItem(listIndent, bullet, EMPTY_ANNOTATED))
            }
        }
    }

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
                        lines.add(currentLine.toString())
                        currentLine = StringBuilder()
                    }
                }
                else -> Unit
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return MdBlock.CodeBlock(lines, lang)
    }

    private fun renderIndentedCode(node: ASTNode, src: String): MdBlock.CodeBlock {
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.CODE_LINE -> currentLine.append(child.getTextInNode(src))
                MarkdownTokenTypes.EOL -> {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder()
                }
                else -> Unit
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return MdBlock.CodeBlock(lines, lang = "")
    }

    /** Flatten a paragraph/heading subtree into a single styled [AnnotatedString]. */
    private fun renderInline(node: ASTNode, src: String): AnnotatedString = buildAnnotatedString {
        appendInlineChildren(this, node, src)
    }

    private fun appendInlineChildren(builder: AnnotatedString.Builder, node: ASTNode, src: String) {
        for (child in node.children) appendInlineNode(builder, child, src)
    }

    private fun appendInlineNode(builder: AnnotatedString.Builder, node: ASTNode, src: String) {
        when (node.type) {
            MarkdownElementTypes.STRONG -> builder.withStyle(theme.bold) {
                appendInlineContent(builder, node, src)
            }
            MarkdownElementTypes.EMPH -> builder.withStyle(theme.italic) {
                appendInlineContent(builder, node, src)
            }
            GFMElementTypes.STRIKETHROUGH -> builder.withStyle(theme.strikethrough) {
                appendInlineContent(builder, node, src)
            }
            MarkdownElementTypes.CODE_SPAN -> builder.withStyle(theme.code) {
                appendCodeSpan(builder, node, src)
            }
            MarkdownElementTypes.INLINE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            MarkdownElementTypes.FULL_REFERENCE_LINK -> builder.withStyle(theme.link) {
                appendLinkText(builder, node, src)
            }
            MarkdownElementTypes.AUTOLINK -> builder.withStyle(theme.link) {
                val raw = node.getTextInNode(src).toString()
                builder.append(raw.trim('<', '>'))
            }
            MarkdownTokenTypes.HARD_LINE_BREAK -> builder.append('\n')
            MarkdownTokenTypes.EOL -> builder.append(' ')
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
     * Walks the inner children of an inline composite node (STRONG/EMPH/etc.)
     * while skipping the opening/closing marker tokens — the JetBrains parser
     * keeps them in the children list as separate nodes.
     */
    private fun appendInlineContent(
        builder: AnnotatedString.Builder,
        node: ASTNode,
        src: String,
    ) {
        // The parser represents the opening/closing `*` / `**` markers as EMPH
        // leaf tokens inside the composite. Simply skipping any EMPH leaf token
        // removes them without fragile index math.
        for (child in node.children) {
            if (child.children.isEmpty() && child.type == MarkdownTokenTypes.EMPH) continue
            if (child.children.isEmpty() && child.type == org.intellij.markdown.flavours.gfm.GFMTokenTypes.TILDE) continue
            appendInlineNode(builder, child, src)
        }
    }

    private fun appendCodeSpan(builder: AnnotatedString.Builder, node: ASTNode, src: String) {
        // Code span children: BACKTICK, text tokens, BACKTICK. Skip the backticks.
        for (child in node.children) {
            if (child.children.isEmpty() && child.type == MarkdownTokenTypes.BACKTICK) continue
            if (child.children.isEmpty()) {
                builder.append(child.getTextInNode(src).toString())
            } else {
                appendInlineChildren(builder, child, src)
            }
        }
    }

    private fun appendLinkText(builder: AnnotatedString.Builder, node: ASTNode, src: String) {
        // Prefer the LINK_TEXT child; if absent, fall back to the raw node text.
        val linkText = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
        if (linkText != null) {
            // LINK_TEXT children include the surrounding [ and ] brackets.
            for (child in linkText.children) {
                if (child.children.isEmpty() && (child.type == MarkdownTokenTypes.LBRACKET || child.type == MarkdownTokenTypes.RBRACKET)) continue
                appendInlineNode(builder, child, src)
            }
        } else {
            builder.append(node.getTextInNode(src).toString())
        }
    }
}

private val EMPTY_ANNOTATED: AnnotatedString = buildAnnotatedString { }

private fun AnnotatedString.isNotBlank(): Boolean = text.isNotBlank()

private fun AnnotatedString.trim(): AnnotatedString {
    val s = text
    var start = 0
    var end = s.length
    while (start < end && s[start].isWhitespace()) start++
    while (end > start && s[end - 1].isWhitespace()) end--
    if (start == 0 && end == s.length) return this
    return subSequence(start, end)
}
