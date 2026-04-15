package fraggle.coding.tui.markdown

import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.UnderlineStyle
import fraggle.coding.tui.Theme

/**
 * Styling hooks for each markdown element kind. Mirrors the `MarkdownTheme`
 * interface from pi-mono's `packages/tui/src/components/markdown.ts`, but
 * returns Mosaic [SpanStyle]s instead of emitting ANSI-wrapped strings.
 *
 * Inline styles are applied by pushing a [SpanStyle] onto an
 * `AnnotatedString.Builder` during rendering. Block styles
 * ([heading], [codeBlock], [listBullet], [quoteBorder]) are applied to
 * whole lines emitted as separate Mosaic `Text` composables.
 */
interface MarkdownTheme {
    val heading: SpanStyle
    val bold: SpanStyle
    val italic: SpanStyle
    val strikethrough: SpanStyle
    val code: SpanStyle
    val codeBlock: SpanStyle
    val codeBlockBorder: SpanStyle
    val listBullet: SpanStyle
    val link: SpanStyle
    val quote: SpanStyle
    val quoteBorder: SpanStyle
    val rule: SpanStyle
}

/** Default theme derived from [Theme]. */
object DefaultMarkdownTheme : MarkdownTheme {
    override val heading = SpanStyle(color = Theme.mdHeading, textStyle = TextStyle.Bold)
    override val bold = SpanStyle(textStyle = TextStyle.Bold)
    override val italic = SpanStyle(textStyle = TextStyle.Italic)
    override val strikethrough = SpanStyle(textStyle = TextStyle.Strikethrough)
    override val code = SpanStyle(color = Theme.mdCode)
    override val codeBlock = SpanStyle(color = Theme.mdCodeBlock)
    override val codeBlockBorder = SpanStyle(color = Theme.mdCodeBlockBorder)
    override val listBullet = SpanStyle(color = Theme.mdListBullet, textStyle = TextStyle.Bold)
    override val link = SpanStyle(
        color = Theme.mdLink,
        underlineStyle = UnderlineStyle.Straight,
        underlineColor = Color.Unspecified,
    )
    override val quote = SpanStyle(color = Theme.mdQuote, textStyle = TextStyle.Italic)
    override val quoteBorder = SpanStyle(color = Theme.mdQuoteBorder)
    override val rule = SpanStyle(color = Theme.mdRule)
}
