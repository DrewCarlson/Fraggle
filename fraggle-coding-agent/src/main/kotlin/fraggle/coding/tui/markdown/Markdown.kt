package fraggle.coding.tui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import fraggle.coding.tui.Theme

/**
 * Renders a markdown string as a stack of Mosaic [Text] composables.
 *
 * The input is parsed fresh on every composition (recomposing on streaming
 * updates is cheap; pi-mono's TS implementation takes the same approach in
 * `packages/tui/src/components/markdown.ts`). Block layout (headings, lists,
 * code blocks, blockquotes, rules) is rendered here; inline styling
 * (bold/italic/code/links) is baked into the [AnnotatedString]s produced by
 * [MarkdownRenderer].
 *
 * Mosaic handles its own text wrapping, so we don't need the column-width
 * math pi's implementation uses.
 *
 * @param text raw markdown text (possibly incomplete while streaming).
 * @param fallbackColor color applied to any text that doesn't get a more
 *   specific span style (e.g. plain paragraph text).
 * @param theme element-level styling; defaults to [DefaultMarkdownTheme].
 */
@Composable
fun Markdown(
    text: String,
    fallbackColor: Color = Theme.assistantText,
    theme: MarkdownTheme = DefaultMarkdownTheme,
) {
    // MarkdownRenderer has no per-composition state, so we keep one instance
    // across recompositions rather than allocating on each streaming tick.
    val renderer = remember(theme) { MarkdownRenderer(theme) }
    val blocks = renderer.render(text)
    Column {
        for ((index, block) in blocks.withIndex()) {
            RenderBlock(block, fallbackColor, theme)
            // Insert a blank line between blocks so paragraphs/lists/code
            // blocks don't run together, matching pi's spacing.
            if (index < blocks.lastIndex && needsSeparator(block, blocks[index + 1])) {
                Text("")
            }
        }
    }
}

@Composable
private fun RenderBlock(block: MdBlock, fallback: Color, theme: MarkdownTheme) {
    when (block) {
        is MdBlock.Paragraph -> Text(block.text, color = fallback)
        is MdBlock.Heading -> {
            // ATX prefix for visual weight at deeper heading levels.
            val prefix = if (block.level >= 3) "#".repeat(block.level) + " " else ""
            val withPrefix = if (prefix.isEmpty()) {
                block.text
            } else {
                buildAnnotatedString {
                    withStyle(theme.heading) { append(prefix) }
                    append(block.text)
                }
            }
            Text(
                withPrefix,
                color = Theme.mdHeading,
                textStyle = if (block.level == 1) TextStyle.Bold + TextStyle.Invert else TextStyle.Bold,
            )
        }
        is MdBlock.ListItem -> {
            Row {
                if (block.indent > 0) Text("  ".repeat(block.indent), color = fallback)
                Text(block.bullet, color = Theme.mdListBullet, textStyle = TextStyle.Bold)
                Text(block.text, color = fallback)
            }
        }
        is MdBlock.CodeBlock -> {
            Column {
                // Top border with optional language label.
                val topBorder = if (block.lang.isNotEmpty()) "┌─ ${block.lang} " else "┌─"
                Text(topBorder, color = Theme.mdCodeBlockBorder)
                for (line in block.lines) {
                    Row {
                        Text("│ ", color = Theme.mdCodeBlockBorder)
                        Text(line, color = Theme.mdCodeBlock)
                    }
                }
                Text("└─", color = Theme.mdCodeBlockBorder)
            }
        }
        is MdBlock.Quote -> {
            Column {
                for (child in block.children) {
                    Row {
                        Text("│ ", color = Theme.mdQuoteBorder)
                        // Render the child block inline beside the gutter.
                        // We flatten to a simple Text so the gutter aligns
                        // with the first visual line; deeply-nested block
                        // structures inside quotes are rare enough that this
                        // simplification is fine for MVP.
                        QuoteChild(child, fallback, theme)
                    }
                }
            }
        }
        MdBlock.Rule -> Text("──────", color = Theme.mdRule)
        MdBlock.Blank -> Text("")
    }
}

@Composable
private fun QuoteChild(block: MdBlock, fallback: Color, theme: MarkdownTheme) {
    when (block) {
        is MdBlock.Paragraph -> Text(block.text, color = Theme.mdQuote, textStyle = TextStyle.Italic)
        is MdBlock.Heading -> Text(block.text, color = Theme.mdHeading, textStyle = TextStyle.Bold)
        is MdBlock.ListItem -> {
            Row {
                Text(block.bullet, color = Theme.mdListBullet)
                Text(block.text, color = Theme.mdQuote)
            }
        }
        else -> RenderBlock(block, fallback, theme)
    }
}

/**
 * Whether a blank line should separate two adjacent blocks. Consecutive list
 * items stay packed tight; everything else gets a gap.
 */
private fun needsSeparator(a: MdBlock, b: MdBlock): Boolean {
    if (a is MdBlock.ListItem && b is MdBlock.ListItem) return false
    return true
}
