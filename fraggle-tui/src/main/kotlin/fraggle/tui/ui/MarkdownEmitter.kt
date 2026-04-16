package fraggle.tui.ui

import fraggle.tui.text.Ansi
import fraggle.tui.text.hardWrap
import fraggle.tui.text.truncateToWidth
import fraggle.tui.text.visibleWidth
import fraggle.tui.text.wordWrap
import fraggle.tui.theme.Theme

/**
 * Turns [MdBlock]s (produced by [MarkdownParser]) into ANSI-styled terminal
 * lines. Every returned line has `visibleWidth(line) <= width`.
 *
 * Styling is applied per-span at the point an [InlineText] is flattened to a
 * single string, then [fraggle.tui.text.wordWrap] (which preserves ANSI state
 * across line breaks) produces the wrapped output. That avoids the parser ever
 * needing to know about widths, and lets block-level prefixes (`│ `, bullets,
 * code borders) participate in the width budget cleanly.
 *
 * The emitter is width-agnostic in the data it takes — constructed once with a
 * theme + fallback prefix, then [emit] is called per render with a new width.
 */
internal class MarkdownEmitter(
    private val theme: Theme,
    private val fallback: String?,
) {

    /**
     * Convert [blocks] to ANSI lines. Inserts blank-line separators between
     * adjacent blocks (except between consecutive list items).
     */
    fun emit(blocks: List<MdBlock>, width: Int): List<String> {
        if (width <= 0) return emptyList()
        if (blocks.isEmpty()) return emptyList()

        val out = mutableListOf<String>()
        for ((index, block) in blocks.withIndex()) {
            out += renderBlock(block, width)
            if (index < blocks.lastIndex && needsSeparator(block, blocks[index + 1])) {
                out += ""
            }
        }
        return out
    }

    // ── Block dispatch ──────────────────────────────────────────────────────

    private fun renderBlock(block: MdBlock, width: Int): List<String> = when (block) {
        is MdBlock.Paragraph -> renderParagraph(block, width)
        is MdBlock.Heading -> renderHeading(block, width)
        is MdBlock.ListItem -> renderListItem(block, width)
        is MdBlock.CodeBlock -> renderCodeBlock(block, width)
        is MdBlock.Quote -> renderQuote(block, width)
        MdBlock.Rule -> renderRule(width)
        MdBlock.Blank -> listOf("")
    }

    private fun renderParagraph(block: MdBlock.Paragraph, width: Int): List<String> {
        val text = flattenInline(block.text, fallbackPrefix = fallback)
        if (text.isEmpty()) return listOf("")
        return wordWrap(text, width)
    }

    private fun renderHeading(block: MdBlock.Heading, width: Int): List<String> {
        // Visual weight by level — no "#" prefix. Three tiers, with deeper
        // levels mapping to the same style as level 3 (rare in practice; LLMs
        // don't usually need more than three heading depths to be readable in
        // a TUI). Dropping the "#" prefix matches Claude-Code / Slack style;
        val styleBase = buildString {
            append(theme.mdHeading)
            append(Ansi.BOLD)
            when (block.level) {
                1 -> append(Ansi.INVERSE)     // highlighted bar — document title
                2 -> append(Ansi.UNDERLINE)   // underlined — section break
                else -> Unit                  // plain bold + heading color — subsection
            }
        }

        // Heading inline text uses the heading style as its fallback; inline
        // spans like codespans within a heading still pop their own styles.
        val body = flattenInline(block.text, fallbackPrefix = styleBase)
        if (body.isEmpty()) return listOf("")
        return wordWrap(body, width)
    }

    private fun renderListItem(block: MdBlock.ListItem, width: Int): List<String> {
        val indentSpaces = "  ".repeat(block.indent.coerceAtLeast(0))
        val bulletStyled = if (block.bullet.isNotBlank()) {
            "${theme.mdListBullet}${Ansi.BOLD}${block.bullet}${Ansi.RESET}"
        } else {
            block.bullet
        }
        val bulletVisibleWidth = visibleWidth(indentSpaces) + visibleWidth(block.bullet)

        val body = flattenInline(block.text, fallbackPrefix = fallback)
        if (body.isEmpty()) {
            // Empty item (e.g. parent of a nested list) — just emit the bullet.
            val line = indentSpaces + bulletStyled
            return safeSingleLine(line, width)
        }

        // Wrap the body to what's left after indent+bullet. If bullet already
        // exceeds width, fall back to a single truncated line.
        val remaining = width - bulletVisibleWidth
        if (remaining <= 0) {
            return safeSingleLine(indentSpaces + bulletStyled + body, width)
        }

        val wrapped = wordWrap(body, remaining)
        val continuationIndent = " ".repeat(bulletVisibleWidth)
        val out = mutableListOf<String>()
        for ((i, line) in wrapped.withIndex()) {
            val prefix = if (i == 0) indentSpaces + bulletStyled else continuationIndent
            out += prefix + line
        }
        return out
    }

    private fun renderCodeBlock(block: MdBlock.CodeBlock, width: Int): List<String> {
        val borderStyle = theme.mdCodeBorder
        val codeStyle = theme.mdCode

        val top = buildString {
            append(borderStyle)
            append(if (block.lang.isNotEmpty()) "┌─ ${block.lang} " else "┌─")
            append(Ansi.RESET)
        }
        val bottom = "${borderStyle}└─${Ansi.RESET}"

        val out = mutableListOf<String>()
        out += safeSingleLine(top, width)

        // Content is "│ " + code line, wrapped to width. When the width is so
        // narrow the gutter would overflow on its own, we drop it and just emit
        // the (possibly truncated) code — a code block at width=1 has no room
        // for the border anyway.
        val gutterWidth = 2
        val hasRoomForGutter = width > gutterWidth
        val gutter = if (hasRoomForGutter) "${borderStyle}│ ${Ansi.RESET}" else ""
        val contentWidth = (width - if (hasRoomForGutter) gutterWidth else 0).coerceAtLeast(1)

        for (codeLine in block.lines) {
            val styled = if (codeLine.isEmpty()) {
                ""
            } else {
                "$codeStyle$codeLine${Ansi.RESET}"
            }
            val wrapped = if (styled.isEmpty()) listOf("") else hardWrap(styled, contentWidth)
            for (w in wrapped) {
                out += safeSingleLine(gutter + w, width)
            }
        }

        out += safeSingleLine(bottom, width)
        return out
    }

    private fun renderQuote(block: MdBlock.Quote, width: Int): List<String> {
        val borderStyle = theme.mdQuoteBorder
        val quoteStyle = theme.mdQuote

        val gutter = "${borderStyle}│ ${Ansi.RESET}"
        val gutterWidth = 2
        val contentWidth = (width - gutterWidth).coerceAtLeast(1)

        val out = mutableListOf<String>()
        for (child in block.children) {
            val childLines = renderQuoteChild(child, contentWidth, quoteStyle)
            for (line in childLines) {
                val composed = gutter + line
                out += safeSingleLine(composed, width)
            }
        }
        return out
    }

    /** Render a single child block inside a quote with the quote's body style. */
    private fun renderQuoteChild(
        block: MdBlock,
        contentWidth: Int,
        quoteStyle: String,
    ): List<String> = when (block) {
        is MdBlock.Paragraph -> {
            // Paragraphs inside quotes get italic + the quote color as fallback.
            val prefix = "$quoteStyle${Ansi.ITALIC}"
            val text = flattenInline(block.text, fallbackPrefix = prefix)
            if (text.isEmpty()) listOf("") else wordWrap(text, contentWidth)
        }
        is MdBlock.Heading -> {
            // Inline headings inside quotes keep heading color, no prefix.
            val prefix = "${theme.mdHeading}${Ansi.BOLD}"
            val text = flattenInline(block.text, fallbackPrefix = prefix)
            if (text.isEmpty()) listOf("") else wordWrap(text, contentWidth)
        }
        is MdBlock.ListItem -> {
            // Delegate, but with the quote colour as fallback.
            val savedFallback = fallback
            try {
                // Temporarily swap fallback via a fresh emitter-local call.
                renderListItemWith(block, contentWidth, fallbackPrefix = quoteStyle)
            } finally {
                // (no-op — we didn't actually mutate the emitter state)
                @Suppress("UNUSED_EXPRESSION") savedFallback
            }
        }
        is MdBlock.CodeBlock -> renderCodeBlock(block, contentWidth)
        is MdBlock.Quote -> renderQuote(block, contentWidth)
        MdBlock.Rule -> listOf("${theme.mdRule}${"─".repeat(contentWidth.coerceAtMost(6))}${Ansi.RESET}")
        MdBlock.Blank -> listOf("")
    }

    /**
     * Variant of [renderListItem] that uses a caller-supplied fallback prefix —
     * lets quote-nested lists pick up the quote colour while the outer
     * [renderListItem] stays pinned to the emitter's configured fallback.
     */
    private fun renderListItemWith(
        block: MdBlock.ListItem,
        width: Int,
        fallbackPrefix: String?,
    ): List<String> {
        val indentSpaces = "  ".repeat(block.indent.coerceAtLeast(0))
        val bulletStyled = if (block.bullet.isNotBlank()) {
            "${theme.mdListBullet}${Ansi.BOLD}${block.bullet}${Ansi.RESET}"
        } else {
            block.bullet
        }
        val bulletVisibleWidth = visibleWidth(indentSpaces) + visibleWidth(block.bullet)

        val body = flattenInline(block.text, fallbackPrefix = fallbackPrefix)
        if (body.isEmpty()) {
            val line = indentSpaces + bulletStyled
            return safeSingleLine(line, width)
        }

        val remaining = width - bulletVisibleWidth
        if (remaining <= 0) {
            return safeSingleLine(indentSpaces + bulletStyled + body, width)
        }

        val wrapped = wordWrap(body, remaining)
        val continuationIndent = " ".repeat(bulletVisibleWidth)
        val out = mutableListOf<String>()
        for ((i, line) in wrapped.withIndex()) {
            val prefix = if (i == 0) indentSpaces + bulletStyled else continuationIndent
            out += prefix + line
        }
        return out
    }

    private fun renderRule(width: Int): List<String> {
        val ruleLen = width.coerceAtMost(6).coerceAtLeast(1)
        return listOf("${theme.mdRule}${"─".repeat(ruleLen)}${Ansi.RESET}")
    }

    // ── Inline flattening ──────────────────────────────────────────────────

    /**
     * Collapse an [InlineText] to a single ANSI-styled string. Each span is
     * wrapped in its own SGR open + RESET so later wordWrap preserves styles
     * across line breaks via its own SGR tracker.
     *
     * [fallbackPrefix] is emitted around any plain-style span so e.g. the
     * heading colour stays applied to unstyled text inside a heading.
     */
    private fun flattenInline(inline: InlineText, fallbackPrefix: String?): String {
        if (inline.spans.isEmpty()) return ""
        val sb = StringBuilder()
        for (span in inline.spans) {
            if (span.text.isEmpty()) continue
            sb.append(stylize(span.text, span.style, fallbackPrefix))
        }
        return sb.toString()
    }

    /**
     * Wrap [text] in the SGR codes for [style] (plus the [fallbackPrefix] if
     * no inline styling is present). Always terminates with [Ansi.RESET].
     *
     * Newlines inside [text] are preserved — the caller (wordWrap) uses them
     * as hard breaks.
     */
    private fun stylize(text: String, style: InlineStyle, fallbackPrefix: String?): String {
        if (text.isEmpty()) return ""

        val prefix = StringBuilder()
        var hasStyle = false

        if (style.has(InlineStyleBit.CODE)) {
            prefix.append(theme.mdCode)
            hasStyle = true
        } else if (style.has(InlineStyleBit.LINK)) {
            prefix.append(theme.mdLink)
            prefix.append(Ansi.UNDERLINE)
            hasStyle = true
        } else if (!fallbackPrefix.isNullOrEmpty()) {
            prefix.append(fallbackPrefix)
            hasStyle = true
        }

        if (style.has(InlineStyleBit.BOLD)) {
            prefix.append(Ansi.BOLD)
            hasStyle = true
        }
        if (style.has(InlineStyleBit.ITALIC)) {
            prefix.append(Ansi.ITALIC)
            hasStyle = true
        }
        if (style.has(InlineStyleBit.STRIKE)) {
            prefix.append(Ansi.STRIKETHROUGH)
            hasStyle = true
        }

        if (!hasStyle) return text
        return prefix.toString() + text + Ansi.RESET
    }

    // ── Separators & safety ─────────────────────────────────────────────────

    private fun needsSeparator(a: MdBlock, b: MdBlock): Boolean {
        if (a is MdBlock.ListItem && b is MdBlock.ListItem) return false
        return true
    }

    /**
     * Emit [text] as a single line, but defensively truncate if it overshoots
     * the width contract. The runtime crashes on width overflow, so this is a
     * last-ditch guard for pathological inputs (width=1 with a decorated bullet
     * etc.).
     */
    private fun safeSingleLine(text: String, width: Int): List<String> {
        if (width <= 0) return emptyList()
        if (visibleWidth(text) <= width) return listOf(text)
        return listOf(truncateToWidth(text, width))
    }
}
