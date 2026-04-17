package fraggle.coding.ui

import fraggle.tui.ui.Autocompletion
import fraggle.tui.ui.AutocompleteProvider

/**
 * Autocomplete source for `/` slash commands in the coding-agent editor.
 *
 * The provider is deliberately dumb: it delegates to a [completionsProvider]
 * lambda that returns the full current set of `/`-prefixed completions, then
 * prefix-filters + sorts them for the popup. Merging built-in slash commands
 * with skill invocations (`/skill:<name>`) is the caller's job — this keeps
 * the provider decoupled from the specific command/skill registry types and
 * makes it trivially testable.
 *
 * Every entry in the supplied list is expected to use the label format the
 * user sees after the `/` (e.g. `"hotkeys"`, `"quit"`, `"skill:code-review"`).
 * The `/` itself is consumed by the editor's autocomplete anchor — the
 * provider sees only what follows.
 */
class SlashCommandAutocompleteProvider(
    private val completionsProvider: () -> List<Autocompletion>,
) : AutocompleteProvider {

    override fun handlesTrigger(trigger: Char): Boolean = trigger == '/'

    override fun suggest(trigger: Char, prefix: String, limit: Int): List<Autocompletion> {
        if (trigger != '/') return emptyList()
        val needle = prefix.lowercase()

        // Two-tier scoring for nicer ordering:
        //  - Names that start with the prefix outrank substring matches.
        //  - Within each tier, sort alphabetically so the list is stable.
        val starts = mutableListOf<Autocompletion>()
        val contains = mutableListOf<Autocompletion>()
        for (entry in completionsProvider()) {
            val label = entry.label.lowercase()
            when {
                label.startsWith(needle) -> starts += entry
                needle.isNotEmpty() && label.contains(needle) -> contains += entry
            }
        }
        starts.sortBy { it.label.lowercase() }
        contains.sortBy { it.label.lowercase() }
        return (starts + contains).take(limit)
    }
}
