package fraggle.executor.supervision

/**
 * A parsed shell command with its executable name and arguments.
 */
data class ParsedCommand(
    val executable: String,
    val args: List<String>,
)

/**
 * Conservative shell command parser that extracts all commands from a shell string.
 *
 * Security principle: when in doubt, extract MORE commands. Unrecognized executables
 * won't match any value pattern, causing the rule to fall through
 * to the interactive handler. Parse failures return an empty list (no match).
 *
 * Supported constructs:
 * - Command chains: `&&`, `||`, `;`
 * - Pipes: `|`
 * - Command substitution: `$(...)` and backticks
 * - Subshells: `(...)`
 * - Process substitution: `<(...)` and `>(...)`
 * - Single/double quoting (prevents operator splitting)
 * - Backslash escaping
 * - Redirections (stripped, not treated as commands)
 * - Comments (`#` in unquoted context)
 * - Leading variable assignments (`VAR=val cmd`)
 */
object ShellCommandParser {

    fun parse(input: String): List<ParsedCommand> {
        return try {
            val tokens = tokenize(input)
            extractCommands(tokens)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // -- Tokenizer --

    private sealed class Token {
        data class Word(val value: String) : Token()
        data class Operator(val value: String) : Token()
        data class Substitution(val inner: String) : Token()
    }

    private enum class QuoteState { NONE, SINGLE, DOUBLE }

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val buf = StringBuilder()
        var i = 0
        var quoteState = QuoteState.NONE
        // Track substitutions found inside the current word
        val pendingSubstitutions = mutableListOf<String>()

        fun flushWord() {
            if (buf.isNotEmpty()) {
                tokens.add(Token.Word(buf.toString()))
                buf.clear()
            }
            for (sub in pendingSubstitutions) {
                tokens.add(Token.Substitution(sub))
            }
            pendingSubstitutions.clear()
        }

        while (i < input.length) {
            val c = input[i]

            when (quoteState) {
                QuoteState.SINGLE -> {
                    if (c == '\'') {
                        quoteState = QuoteState.NONE
                    } else {
                        buf.append(c)
                    }
                    i++
                }

                QuoteState.DOUBLE -> {
                    when (c) {
                        '"' -> {
                            quoteState = QuoteState.NONE
                            i++
                        }
                        '\\' if i + 1 < input.length -> {
                            val next = input[i + 1]
                            if (next in "\"\\\$`\n") {
                                buf.append(next)
                            } else {
                                buf.append(c)
                                buf.append(next)
                            }
                            i += 2
                        }
                        '$' if i + 1 < input.length && input[i + 1] == '(' -> {
                            val (inner, endIdx) = extractParenContent(input, i + 1)
                            if (inner != null) {
                                buf.append("<substitution>")
                                pendingSubstitutions.add(inner)
                                i = endIdx
                            } else {
                                buf.append(c)
                                i++
                            }
                        }
                        '`' -> {
                            val endIdx = input.indexOf('`', i + 1)
                            if (endIdx != -1) {
                                val inner = input.substring(i + 1, endIdx)
                                buf.append("<substitution>")
                                pendingSubstitutions.add(inner)
                                i = endIdx + 1
                            } else {
                                buf.append(c)
                                i++
                            }
                        }
                        else -> {
                            buf.append(c)
                            i++
                        }
                    }
                }

                QuoteState.NONE -> {
                    when {
                        c == '#' -> {
                            // Comment — ignore rest of line
                            flushWord()
                            break
                        }
                        c == '\'' -> {
                            quoteState = QuoteState.SINGLE
                            i++
                        }
                        c == '"' -> {
                            quoteState = QuoteState.DOUBLE
                            i++
                        }
                        c == '\\' && i + 1 < input.length -> {
                            buf.append(input[i + 1])
                            i += 2
                        }
                        c == '$' && i + 1 < input.length && input[i + 1] == '(' -> {
                            val (inner, endIdx) = extractParenContent(input, i + 1)
                            if (inner != null) {
                                buf.append("<substitution>")
                                pendingSubstitutions.add(inner)
                                i = endIdx
                            } else {
                                buf.append(c)
                                i++
                            }
                        }
                        c == '`' -> {
                            val endIdx = input.indexOf('`', i + 1)
                            if (endIdx != -1) {
                                val inner = input.substring(i + 1, endIdx)
                                buf.append("<substitution>")
                                pendingSubstitutions.add(inner)
                                i = endIdx + 1
                            } else {
                                buf.append(c)
                                i++
                            }
                        }
                        c == '(' -> {
                            // Subshell
                            val (inner, endIdx) = extractParenContent(input, i)
                            if (inner != null) {
                                flushWord()
                                tokens.add(Token.Substitution(inner))
                                i = endIdx
                            } else {
                                buf.append(c)
                                i++
                            }
                        }
                        // Process substitution: <(...) or >(...)
                        (c == '<' || c == '>') && i + 1 < input.length && input[i + 1] == '(' -> {
                            val (inner, endIdx) = extractParenContent(input, i + 1)
                            if (inner != null) {
                                // Don't add process substitution content to buf — it's a separate command
                                pendingSubstitutions.add(inner)
                                i = endIdx
                            } else {
                                buf.append(c)
                                i++
                            }
                        }
                        // Operators: &&, ||, ;, |
                        c == '&' && i + 1 < input.length && input[i + 1] == '&' -> {
                            flushWord()
                            tokens.add(Token.Operator("&&"))
                            i += 2
                        }
                        c == '|' && i + 1 < input.length && input[i + 1] == '|' -> {
                            flushWord()
                            tokens.add(Token.Operator("||"))
                            i += 2
                        }
                        c == '|' -> {
                            flushWord()
                            tokens.add(Token.Operator("|"))
                            i++
                        }
                        c == ';' -> {
                            flushWord()
                            tokens.add(Token.Operator(";"))
                            i++
                        }
                        // Redirections: >>, >&, <, >, 2>, 2>&1 etc.
                        c == '>' -> {
                            flushWord()
                            i = skipRedirection(input, i)
                        }
                        c == '<' -> {
                            flushWord()
                            i = skipRedirection(input, i)
                        }
                        c.isDigit() && i + 1 < input.length && input[i + 1] == '>' -> {
                            // fd redirect like 2>
                            flushWord()
                            i = skipRedirection(input, i + 1)
                        }
                        c.isWhitespace() -> {
                            flushWord()
                            i++
                        }
                        else -> {
                            buf.append(c)
                            i++
                        }
                    }
                }
            }
        }
        flushWord()
        return tokens
    }

    /**
     * Extract content inside balanced parentheses starting at `input[start]` which must be '('.
     * Returns (content, indexAfterCloseParen) or (null, -1) if unbalanced.
     */
    private fun extractParenContent(input: String, start: Int): Pair<String?, Int> {
        if (start >= input.length || input[start] != '(') return null to -1
        var depth = 1
        var i = start + 1
        var singleQuoted = false
        var doubleQuoted = false

        while (i < input.length && depth > 0) {
            val c = input[i]
            when {
                singleQuoted -> {
                    if (c == '\'') singleQuoted = false
                }
                doubleQuoted -> {
                    if (c == '"') doubleQuoted = false
                    else if (c == '\\' && i + 1 < input.length) i++ // skip escaped char
                }
                c == '\'' -> singleQuoted = true
                c == '"' -> doubleQuoted = true
                c == '\\' && i + 1 < input.length -> i++ // skip escaped char
                c == '(' -> depth++
                c == ')' -> depth--
            }
            i++
        }

        return if (depth == 0) {
            // content is between start+1 and i-1
            input.substring(start + 1, i - 1) to i
        } else {
            null to -1
        }
    }

    /**
     * Skip a redirection and its target word. Returns index after the target.
     * Handles: >, >>, >&, <, <<, 2>&1, etc.
     */
    private fun skipRedirection(input: String, start: Int): Int {
        var i = start
        // Skip the redirect operator characters
        while (i < input.length && input[i] in "><&") i++
        // Skip whitespace before target
        while (i < input.length && input[i].isWhitespace()) i++
        // Skip the target word
        while (i < input.length && !input[i].isWhitespace() && input[i] !in ";&|<>") i++
        return i
    }

    // -- Command extraction --

    private fun extractCommands(tokens: List<Token>): List<ParsedCommand> {
        val commands = mutableListOf<ParsedCommand>()
        val currentWords = mutableListOf<String>()

        fun flushCommand() {
            val words = skipAssignments(currentWords)
            if (words.isNotEmpty()) {
                commands.add(ParsedCommand(extractExecutable(words[0]), words.drop(1)))
            }
            currentWords.clear()
        }

        for (token in tokens) {
            when (token) {
                is Token.Operator -> flushCommand()
                is Token.Word -> currentWords.add(token.value)
                is Token.Substitution -> {
                    // Recursively parse the substitution
                    commands.addAll(parse(token.inner))
                }
            }
        }
        flushCommand()
        return commands
    }

    /**
     * Skip leading variable assignments (e.g., `PATH=/bin VAR=val command args`).
     * Returns the remaining words starting from the first non-assignment.
     */
    private fun skipAssignments(words: List<String>): List<String> {
        var i = 0
        while (i < words.size && isAssignment(words[i])) i++
        return words.subList(i, words.size)
    }

    private fun isAssignment(word: String): Boolean {
        val eqIdx = word.indexOf('=')
        if (eqIdx <= 0) return false
        // Variable name must be [a-zA-Z_][a-zA-Z0-9_]*
        val name = word.substring(0, eqIdx)
        return name.first().let { it.isLetter() || it == '_' } &&
            name.all { it.isLetterOrDigit() || it == '_' }
    }

    /**
     * Extract the executable name from a command word.
     * Strips path prefixes (e.g., `/usr/bin/ls` → `ls`).
     */
    private fun extractExecutable(word: String): String {
        val name = word.substringAfterLast('/')
        return name.ifEmpty { word }
    }
}
