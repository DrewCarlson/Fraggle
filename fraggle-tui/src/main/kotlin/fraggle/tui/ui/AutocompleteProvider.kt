package fraggle.tui.ui

/**
 * Generic completion supplier for the [Editor] autocomplete popup.
 *
 * The editor watches for trigger characters (typically `@` for files,
 * `/` for slash commands). When one fires, it calls [suggest] with the
 * prefix the user has typed AFTER the trigger — e.g. typing `@src/Fo`
 * calls `suggest('@', "src/Fo", …)`. The provider is expected to be
 * synchronous and cheap; the editor queries on every keystroke while
 * autocomplete is active.
 *
 * Providers are pluggable so the editor stays content-agnostic:
 * file-path completion, slash commands, shell history, emoji — any
 * string-to-string mapping fits the contract.
 */
interface AutocompleteProvider {

    /**
     * True when [trigger] should open the autocomplete popup. A provider
     * that only handles `@` returns true only for `@`; one that handles both
     * `@` and `/` returns true for both.
     */
    fun handlesTrigger(trigger: Char): Boolean

    /**
     * Return up to [limit] completions for [prefix] under [trigger].
     *
     * An empty list dismisses the popup. A provider may return an empty
     * list at any time (e.g. once the user has typed more characters than
     * any known path matches) and the editor closes the popup silently.
     *
     * [prefix] does NOT include the trigger character itself.
     */
    fun suggest(trigger: Char, prefix: String, limit: Int): List<Autocompletion>
}

/**
 * One row in the autocomplete popup.
 *
 * @property label Short, display-first-column text (usually the same as [replacement]).
 * @property replacement The text that replaces everything from the trigger
 *   character to the cursor when the user accepts this completion.
 * @property description Optional secondary column shown in dim color next
 *   to the label (e.g. full path for file completions, help text for
 *   slash commands).
 * @property trailingSpace When true, the editor inserts a space after the
 *   replacement. Files should set this to true so the user can continue
 *   typing their message; directory entries should set it to false so the
 *   user can continue completing into a subdirectory.
 * @property continueCompletion When true, the editor keeps the popup open
 *   with the newly-extended prefix after applying the completion. Used for
 *   directory entries — picking `src/` should immediately show the entries
 *   inside `src/`.
 */
data class Autocompletion(
    val label: String,
    val replacement: String,
    val description: String? = null,
    val trailingSpace: Boolean = true,
    val continueCompletion: Boolean = false,
)
