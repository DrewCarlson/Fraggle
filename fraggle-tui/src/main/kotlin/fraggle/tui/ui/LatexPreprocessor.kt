package fraggle.tui.ui

/**
 * Lightweight pre-processor that converts common LaTeX math fragments into
 * Unicode equivalents before markdown parsing.
 *
 * LLMs routinely emit `$\approx 70\%$` style math in chat responses. Terminal
 * UIs can't render LaTeX, so the raw source looks like "leaked syntax" to the
 * user. We handle the common cases by:
 *
 * 1. **Unwrapping `$...$` and `$$...$$` delimiters** when the content looks
 *    math-like (contains `\command` patterns, `^`, or `_`). Currency mentions
 *    like `$5` or `$this costs $10$` aren't unwrapped because they have no
 *    LaTeX markers.
 *
 * 2. **Substituting a curated set of LaTeX commands** with their Unicode
 *    equivalents. Covers Greek letters, relation operators, common symbols,
 *    arrows, and LaTeX-escaped punctuation (`\%` → `%`, `\$` → `$`).
 *
 * 3. **Leaving anything else alone.** Complex expressions that don't have a
 *    straightforward Unicode render (matrices, integrals with bounds,
 *    fractions) pass through with only the commands converted — the user
 *    still sees readable text even if not mathematically typeset.
 *
 * This is intentionally NOT a full LaTeX renderer. It's a targeted fix for
 * the common-case annoyance of seeing `$\approx$` in a chat message.
 */
internal object LatexPreprocessor {

    /**
     * Run [text] through the preprocessor. Safe on arbitrary input — the
     * function is total, non-throwing, and leaves non-LaTeX content untouched.
     */
    fun process(text: String): String {
        if (text.isEmpty()) return text
        if (!text.contains('$') && !text.contains('\\')) return text

        val unwrapped = unwrapMath(text)
        return substituteCommands(unwrapped)
    }

    /**
     * Find `$...$` (or `$$...$$`) pairs. If the inner content looks like math
     * — has a backslash command, subscript (`_`), or superscript (`^`) — drop
     * the delimiters. Otherwise leave the text alone so currency mentions like
     * `$5 to $10` don't get mangled.
     */
    private fun unwrapMath(text: String): String {
        if (!text.contains('$')) return text

        val result = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '$') {
                result.append(c)
                i++
                continue
            }
            // Check for $$...$$ first.
            if (i + 1 < text.length && text[i + 1] == '$') {
                val close = text.indexOf("$$", startIndex = i + 2)
                if (close > 0) {
                    val inner = text.substring(i + 2, close)
                    if (looksLikeMath(inner)) {
                        result.append(inner)
                        i = close + 2
                        continue
                    }
                }
            }
            // Then $...$ on a single line.
            val close = findMatchingDollar(text, i + 1)
            if (close > 0) {
                val inner = text.substring(i + 1, close)
                if (looksLikeMath(inner)) {
                    result.append(inner)
                    i = close + 1
                    continue
                }
            }
            // Not math — leave the dollar alone.
            result.append(c)
            i++
        }
        return result.toString()
    }

    /**
     * Find the closing `$` on the same line as [from], not preceded by a
     * backslash escape. Returns -1 if none found.
     */
    private fun findMatchingDollar(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') return -1
            if (c == '$' && (i == 0 || text[i - 1] != '\\')) return i
            i++
        }
        return -1
    }

    /**
     * Heuristic for "this is LaTeX math, not prose with dollar signs":
     * presence of a backslash command, superscript, or subscript pattern.
     */
    private fun looksLikeMath(content: String): Boolean {
        if (content.isEmpty() || content.length > 200) return false
        // A backslash that's followed by a letter (i.e. `\approx`) is a strong
        // signal. Raw `\` escapes for punctuation (`\%`, `\$`) also count.
        for (i in content.indices) {
            if (content[i] == '\\' && i + 1 < content.length) return true
            if (content[i] == '^' || content[i] == '_') return true
        }
        return false
    }

    private fun substituteCommands(text: String): String {
        if (!text.contains('\\')) return text
        var result = text
        for ((latex, unicode) in COMMANDS) {
            if (result.contains(latex)) {
                result = result.replace(latex, unicode)
            }
        }
        return result
    }

    /**
     * Ordered so that longer commands are tried first (e.g. `\leftarrow`
     * before `\leq`). Keys include the leading backslash and end at a word
     * boundary — we rely on simple string replacement, so a command like
     * `\pi` would also match inside `\piano`. LaTeX rarely has that
     * collision in practice; if it becomes a problem we can switch to a
     * regex with `\b` boundary.
     */
    private val COMMANDS: List<Pair<String, String>> = listOf(
        // Escapes first — these resolve to single ASCII characters the user
        // expects to see literally.
        "\\%" to "%",
        "\\$" to "$",
        "\\&" to "&",
        "\\#" to "#",
        "\\_" to "_",
        "\\{" to "{",
        "\\}" to "}",

        // Arrows (long before short).
        "\\leftrightarrow" to "↔",
        "\\Leftrightarrow" to "⇔",
        "\\rightarrow" to "→",
        "\\leftarrow" to "←",
        "\\Rightarrow" to "⇒",
        "\\Leftarrow" to "⇐",
        "\\uparrow" to "↑",
        "\\downarrow" to "↓",
        "\\to" to "→",
        "\\mapsto" to "↦",

        // Relations.
        "\\approx" to "≈",
        "\\equiv" to "≡",
        "\\neq" to "≠",
        "\\leq" to "≤",
        "\\geq" to "≥",
        "\\ll" to "≪",
        "\\gg" to "≫",
        "\\propto" to "∝",
        "\\sim" to "∼",

        // Operators.
        "\\times" to "×",
        "\\div" to "÷",
        "\\cdot" to "·",
        "\\pm" to "±",
        "\\mp" to "∓",
        "\\star" to "⋆",
        "\\oplus" to "⊕",
        "\\otimes" to "⊗",

        // Set / logic.
        "\\forall" to "∀",
        "\\exists" to "∃",
        "\\nexists" to "∄",
        "\\notin" to "∉",
        "\\subseteq" to "⊆",
        "\\supseteq" to "⊇",
        "\\subset" to "⊂",
        "\\supset" to "⊃",
        "\\in" to "∈",
        "\\cup" to "∪",
        "\\cap" to "∩",
        "\\emptyset" to "∅",
        "\\varnothing" to "∅",

        // Calculus / misc symbols.
        "\\infty" to "∞",
        "\\partial" to "∂",
        "\\nabla" to "∇",
        "\\sum" to "∑",
        "\\prod" to "∏",
        "\\int" to "∫",
        "\\oint" to "∮",
        "\\sqrt" to "√",
        "\\degree" to "°",
        "\\circ" to "°",
        "\\ldots" to "…",
        "\\cdots" to "⋯",
        "\\dots" to "…",

        // Greek — uppercase first to avoid lowercase prefix collisions.
        "\\Alpha" to "Α",
        "\\Beta" to "Β",
        "\\Gamma" to "Γ",
        "\\Delta" to "Δ",
        "\\Epsilon" to "Ε",
        "\\Zeta" to "Ζ",
        "\\Eta" to "Η",
        "\\Theta" to "Θ",
        "\\Iota" to "Ι",
        "\\Kappa" to "Κ",
        "\\Lambda" to "Λ",
        "\\Mu" to "Μ",
        "\\Nu" to "Ν",
        "\\Xi" to "Ξ",
        "\\Omicron" to "Ο",
        "\\Pi" to "Π",
        "\\Rho" to "Ρ",
        "\\Sigma" to "Σ",
        "\\Tau" to "Τ",
        "\\Upsilon" to "Υ",
        "\\Phi" to "Φ",
        "\\Chi" to "Χ",
        "\\Psi" to "Ψ",
        "\\Omega" to "Ω",

        "\\alpha" to "α",
        "\\beta" to "β",
        "\\gamma" to "γ",
        "\\delta" to "δ",
        "\\varepsilon" to "ε",
        "\\epsilon" to "ε",
        "\\zeta" to "ζ",
        "\\eta" to "η",
        "\\theta" to "θ",
        "\\vartheta" to "ϑ",
        "\\iota" to "ι",
        "\\kappa" to "κ",
        "\\lambda" to "λ",
        "\\mu" to "μ",
        "\\nu" to "ν",
        "\\xi" to "ξ",
        "\\omicron" to "ο",
        "\\pi" to "π",
        "\\varpi" to "ϖ",
        "\\rho" to "ρ",
        "\\varrho" to "ϱ",
        "\\sigma" to "σ",
        "\\varsigma" to "ς",
        "\\tau" to "τ",
        "\\upsilon" to "υ",
        "\\phi" to "φ",
        "\\varphi" to "φ",
        "\\chi" to "χ",
        "\\psi" to "ψ",
        "\\omega" to "ω",
    )
}
