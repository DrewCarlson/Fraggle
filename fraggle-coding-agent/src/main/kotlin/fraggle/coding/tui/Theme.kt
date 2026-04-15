package fraggle.coding.tui

import com.jakewharton.mosaic.ui.Color

/**
 * Color palette for the coding-agent TUI.
 *
 * Mosaic ships eight named colors (`Color.Black/Red/Green/.../White`) plus a
 * public `Color(r, g, b)` constructor. We use the named colors for anything
 * the user needs to distinguish quickly (user vs assistant messages, errors,
 * accents) and fall back to muted RGB greys for dim/structural elements like
 * hints and borders.
 *
 * MVP ships one dark theme. Future work could hot-reload user-authored
 * themes from JSON files under `$FRAGGLE_ROOT/coding/themes/`.
 */
object Theme {
    // Foreground roles
    val foreground: Color = Color.White
    val dim: Color = Color(140, 140, 140)
    val veryDim: Color = Color(90, 90, 90)

    // Message roles
    val userText: Color = Color.Cyan
    val assistantText: Color = Color.White
    val toolCall: Color = Color.Yellow
    val toolResult: Color = Color(170, 170, 170)
    val toolError: Color = Color.Red

    // Structural
    val accent: Color = Color.Cyan
    val divider: Color = Color(80, 80, 80)
    val error: Color = Color.Red
    val success: Color = Color.Green
    val warning: Color = Color.Yellow
    val cursor: Color = Color(140, 140, 140)

    // Markdown element colors — used by the Markdown composable when rendering
    // assistant LLM output. Keep these separate from the role colors above so
    // a theme author can re-skin markdown without touching message roles.
    val mdHeading: Color = Color.Cyan
    val mdCode: Color = Color(200, 170, 110)
    val mdCodeBlock: Color = Color(200, 170, 110)
    val mdCodeBlockBorder: Color = Color(80, 80, 80)
    val mdListBullet: Color = Color.Cyan
    val mdQuote: Color = Color(170, 170, 170)
    val mdQuoteBorder: Color = Color(100, 100, 100)
    val mdLink: Color = Color.Blue
    val mdRule: Color = Color(80, 80, 80)
}
