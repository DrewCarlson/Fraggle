package fraggle.tui.theme

import fraggle.tui.text.Ansi

/**
 * Color and style palette for fraggle-tui components.
 *
 * Themes are plain strings — each property is a pre-baked ANSI SGR escape that
 * a component can emit directly. This avoids a per-frame conversion layer and
 * keeps render paths allocation-free.
 *
 * Currently MVP ships one dark theme. A theme-registry layer with hot-reload
 * from JSON files under `$FRAGGLE_ROOT/coding/themes/` is possible future
 * work; the single [Theme] instance is held as a mutable `var` so themes can
 * be swapped at runtime without rebuilding the component tree.
 */
class Theme(
    // Role colors
    val foreground: String,
    val dim: String,
    val veryDim: String,
    val accent: String,
    val divider: String,

    // Message roles
    val userText: String,
    val assistantText: String,
    val toolCall: String,
    val toolResult: String,
    val toolError: String,

    // Status
    val error: String,
    val success: String,
    val warning: String,
    val cursor: String,

    // Markdown
    val mdHeading: String,
    val mdCode: String,
    val mdCodeBorder: String,
    val mdListBullet: String,
    val mdQuote: String,
    val mdQuoteBorder: String,
    val mdLink: String,
    val mdRule: String,
) {
    /** Wrap [text] in [color] … RESET. */
    fun fg(color: String, text: String): String = "$color$text${Ansi.RESET}"

    /** Wrap [text] in bold + RESET. */
    fun bold(text: String): String = "${Ansi.BOLD}$text${Ansi.RESET}"

    /** Wrap [text] in italic + RESET. */
    fun italic(text: String): String = "${Ansi.ITALIC}$text${Ansi.RESET}"

    companion object {
        val DARK: Theme = Theme(
            foreground = Ansi.fgRgb(230, 230, 230),
            dim = Ansi.fgRgb(140, 140, 140),
            veryDim = Ansi.fgRgb(90, 90, 90),
            accent = Ansi.fgRgb(0, 200, 200),
            divider = Ansi.fgRgb(80, 80, 80),

            userText = Ansi.fgRgb(0, 200, 200),
            assistantText = Ansi.fgRgb(230, 230, 230),
            toolCall = Ansi.fgRgb(230, 200, 80),
            toolResult = Ansi.fgRgb(170, 170, 170),
            toolError = Ansi.fgRgb(230, 80, 80),

            error = Ansi.fgRgb(230, 80, 80),
            success = Ansi.fgRgb(80, 200, 80),
            warning = Ansi.fgRgb(230, 200, 80),
            cursor = Ansi.fgRgb(230, 230, 230),

            mdHeading = Ansi.fgRgb(0, 200, 200),
            mdCode = Ansi.fgRgb(200, 170, 110),
            mdCodeBorder = Ansi.fgRgb(80, 80, 80),
            mdListBullet = Ansi.fgRgb(0, 200, 200),
            mdQuote = Ansi.fgRgb(170, 170, 170),
            mdQuoteBorder = Ansi.fgRgb(100, 100, 100),
            mdLink = Ansi.fgRgb(100, 150, 230),
            mdRule = Ansi.fgRgb(80, 80, 80),
        )
    }
}

/**
 * Process-wide default theme. Swap via [setTheme] to re-skin the UI.
 * Readers should re-read from this property per render (cheap — it's just a ref).
 */
@Volatile
var theme: Theme = Theme.DARK
    private set

fun setTheme(newTheme: Theme) {
    theme = newTheme
}
