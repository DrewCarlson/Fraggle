package fraggle.agent.skill

/**
 * Minimal `.gitignore`-style pattern matcher used during skill discovery.
 *
 * This is **not** a full git ignore implementation. It supports the subset of the
 * spec that shows up in practice when a user points `skills_dir` at a directory
 * that already contains unrelated files (repos, caches, fixtures):
 *
 *  - Plain names (`fixtures`) match at any depth under the rule's prefix directory.
 *  - Leading `/` anchors the pattern to the prefix directory.
 *  - Trailing `/` restricts the match to directories.
 *  - `*` matches any run of characters except `/`.
 *  - `**` matches any number of characters including `/`.
 *  - `?` matches a single non-`/` character.
 *  - Leading `!` negates a rule, reinstating a previously ignored path.
 *  - Lines starting with `#` and blank lines are comments.
 *  - `\!` and `\#` escape the special leading characters.
 *
 * Rules added via [add] are scoped to a `prefix` (the path from the scan root to
 * the directory containing the `.gitignore` file, with a trailing `/` — or an empty
 * string for the scan root). This lets a single shared matcher accumulate rules from
 * nested ignore files without a sibling subtree ever matching rules from its
 * neighbours — a rule at `a/b/.gitignore` only has effect under `a/b/`.
 *
 * Rule evaluation mirrors git: later rules override earlier ones, so a negation
 * rule must appear after the rule it reverses.
 */
internal class GitignoreMatcher {

    private data class Rule(
        val regex: Regex,
        val negated: Boolean,
        val dirOnly: Boolean,
    )

    private val rules = mutableListOf<Rule>()

    /**
     * Parse [lines] as a `.gitignore` file whose effective location is [prefix]
     * (path from the scan root to the containing directory, with trailing `/`, or
     * empty for the root).
     */
    fun add(lines: List<String>, prefix: String) {
        for (raw in lines) {
            val line = raw.trim().ifEmpty { continue }
            if (line.startsWith("#")) continue

            var pattern = line
            var negated = false
            if (pattern.startsWith("!")) {
                negated = true
                pattern = pattern.substring(1)
            } else if (pattern.startsWith("\\!") || pattern.startsWith("\\#")) {
                pattern = pattern.substring(1)
            }
            if (pattern.isEmpty()) continue

            val dirOnly = pattern.endsWith("/")
            if (dirOnly) pattern = pattern.dropLast(1)

            val anchored = pattern.startsWith("/")
            if (anchored) pattern = pattern.substring(1)

            if (pattern.isEmpty()) continue

            val floating = !anchored && !pattern.contains('/')
            val body = buildString {
                if (prefix.isNotEmpty()) append(Regex.escape(prefix))
                if (floating) append("(?:.*/)?")
                append(globToRegex(pattern))
            }
            // Allow a trailing path segment so directory rules match anything beneath them.
            val regex = Regex("^$body(?:/.*)?$")
            rules += Rule(regex, negated, dirOnly)
        }
    }

    /**
     * @param relativePath forward-slash separated path relative to the scan root.
     * @return whether [relativePath] should be ignored under the accumulated rules.
     */
    fun isIgnored(relativePath: String, isDirectory: Boolean): Boolean {
        var ignored = false
        for (rule in rules) {
            if (rule.dirOnly && !isDirectory) continue
            if (rule.regex.matches(relativePath)) {
                ignored = !rule.negated
            }
        }
        return ignored
    }

    private fun globToRegex(glob: String): String = buildString {
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                    append(".*")
                    i += 2
                    if (i < glob.length && glob[i] == '/') i++
                }
                c == '*' -> {
                    append("[^/]*")
                    i++
                }
                c == '?' -> {
                    append("[^/]")
                    i++
                }
                c in ".+()^$|{}[]\\" -> {
                    append('\\')
                    append(c)
                    i++
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }
}
