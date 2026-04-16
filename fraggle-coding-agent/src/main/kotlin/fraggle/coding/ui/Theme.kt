package fraggle.coding.ui

import fraggle.tui.theme.Theme

/**
 * Coding-agent theme alias.
 *
 * fraggle-tui's [fraggle.tui.theme.Theme] already defines every role the coding
 * agent needs (userText, assistantText, toolCall, toolResult, toolError,
 * accent, dim, veryDim, divider, error, warning, etc.). Rather than defining a
 * parallel palette here, we expose the global theme as [codingTheme] so the
 * coding-agent UI components have one import point.
 *
 * Themes live as a single process-wide `var` in fraggle-tui; swapping via
 * `fraggle.tui.theme.setTheme(...)` re-skins both fraggle-tui primitives and
 * these components simultaneously.
 */
val codingTheme: Theme get() = fraggle.tui.theme.theme
